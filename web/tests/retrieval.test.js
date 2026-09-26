import test from "node:test";
import assert from "node:assert/strict";
import { RetrievalSession, locatedHitError } from "../src/retrieval.js";
const id = "00000000-0000-4000-8000-000000000001";
const memory = () => {
  const map = new Map();
  return { getItem: (k) => map.get(k), setItem: (k, v) => map.set(k, v) };
};
function session(storage, request, owner = "1", productId = "2") {
  return new RetrievalSession({
    storage,
    request,
    owner,
    productId,
    uuid: () => id,
  });
}
test("无法持久化时不发送收费请求", async () => {
  let calls = 0;
  const s = session(
    {
      getItem: () => null,
      setItem: () => {
        throw Error();
      },
    },
    async () => {
      calls++;
    },
  );
  await s.start("search", "有线鼠标");
  assert.equal(calls, 0);
  assert.equal(s.locked, true);
});
test("超时后刷新保留原UUID、正文，先查回再放开新操作", async () => {
  const storage = memory();
  let calls = 0;
  const s = session(storage, async (path, options) => {
    calls++;
    assert.ok(storage.getItem(s.key).includes(id));
    assert.equal(options.body.query, "有线鼠标");
    throw Error("超时");
  });
  await s.start("search", "有线鼠标");
  assert.equal(s.pending, true);
  await s.start("build");
  assert.equal(calls, 1);
  const loaded = session(storage, async (path, options) => {
    assert.match(path, /operations/);
    assert.equal(options.method, undefined);
    return {
      id,
      status: "DONE",
      result: { status: "SEARCHED", semantic: true, items: [] },
    };
  });
  await loaded.recover();
  assert.equal(loaded.pending, false);
  assert.equal(loaded.ticket.body.query, "有线鼠标");
});
test("404与RUNNING不解除未知操作，新请求不能绕过", async () => {
  const storage = memory();
  const s = session(storage, async () => {
    throw Error("404");
  });
  await s.start("build");
  await s.recover();
  assert.equal(s.pending, true);
  s.request = async () => ({ id, status: "RUNNING" });
  await s.recover();
  assert.equal(s.locked, true);
});
test("重发只使用原编号与原正文，不自动重复", async () => {
  const storage = memory();
  let first;
  const s = session(storage, async (path, options) => {
    first = options.body;
    throw Error("断网");
  });
  await s.start("search", "USB-C 视频");
  s.request = async (path, options) => {
    assert.deepEqual(options.body, first);
    return { id, status: "FAILED", error_code: "EMBEDDING_LIMIT" };
  };
  await s.send();
  assert.equal(s.pending, false);
});
test("账户和商品恢复记录彼此隔离，损坏记录锁定", async () => {
  const storage = memory(),
    s = session(storage, async () => {
      throw Error("断网");
    });
  await s.start("build");
  assert.equal(session(storage, async () => {}, "3").ticket, null);
  assert.equal(session(storage, async () => {}, "1", "24").ticket, null);
  storage.setItem(s.key, '{"kind":"search"}');
  assert.equal(session(storage, async () => {}).storageBlocked, true);
});
test("不同操作响应不能覆盖原请求，未知进程终态不会自动发起收费", async () => {
  let calls = 0;
  const s = session(memory(), async () => {
    calls++;
    return { id: "wrong", status: "DONE" };
  });
  await s.start("build");
  assert.equal(s.pending, true);
  s.request = async () => ({ id, status: "UNKNOWN" });
  await s.recover();
  assert.equal(s.ticket.response.status, "UNKNOWN");
  assert.equal(calls, 1);
});
test("原文核验拒绝变版、错商品、旧状态和否定丢失", () => {
  const c = {
    productId: "2",
    docId: "4",
    version: "1",
    ruleVersion: "v1",
    chunkId: "doc:4:v1:c1",
    charStart: 0,
    charEnd: 6,
    text: "不支持视频。",
  };
  const l = {
    ...c,
    current: true,
    located: true,
    indexState: "READY",
    productStatus: "PUBLISHED",
    content: c.text,
  };
  assert.equal(locatedHitError(c, l, "2"), "");
  for (const patch of [
    { version: "2" },
    { current: false },
    { productStatus: "HIDDEN" },
    { content: "支持视频。" },
    { charStart: 1 },
    { productId: "24" },
  ])
    assert.ok(locatedHitError(c, { ...l, ...patch }, "2"));
});
test("旧组件迟到响应不能覆盖新UUID的未知记录", async () => {
  const storage = memory();
  let resolveA, resolveB;
  const a = session(storage, () => new Promise((r) => (resolveA = r)));
  const pendingA = a.start("search", "问题A");
  const b = session(storage, async () => ({
    id,
    status: "DONE",
    result: { status: "SEARCHED" },
  }));
  await b.recover();
  const second = "00000000-0000-4000-8000-000000000002";
  b.uuid = () => second;
  b.request = () => new Promise((r) => (resolveB = r));
  const pendingB = b.start("search", "问题B");
  resolveA({ id, status: "DONE", result: { status: "SEARCHED" } });
  await pendingA;
  assert.equal(JSON.parse(storage.getItem(b.key)).body.requestId, second);
  assert.equal(session(storage, async () => {}).pending, true);
  await a.start("build");
  assert.equal(JSON.parse(storage.getItem(b.key)).body.requestId, second);
  resolveB({ id: second, status: "DONE", result: { status: "SEARCHED" } });
  await pendingB;
});
test("同UUID迟到RUNNING不能覆盖已查回的终态", async () => {
  const storage = memory();
  let release;
  const a = session(storage, () => new Promise((r) => (release = r)));
  const pending = a.start("build");
  const b = session(storage, async () => ({
    id,
    status: "DONE",
    result: { status: "READY" },
  }));
  await b.recover();
  release({ id, status: "RUNNING" });
  await pending;
  assert.equal(session(storage, async () => {}).ticket.response.status, "DONE");
});
