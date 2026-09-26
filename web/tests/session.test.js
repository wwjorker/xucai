import test from "node:test";
import assert from "node:assert/strict";

const deferred = () => {
  let resolve;
  const promise = new Promise((r) => (resolve = r));
  return { promise, resolve };
};
const ok = (data) =>
  new Response(JSON.stringify({ code: 200, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let sequence = 0;
async function fixture() {
  const mod = await import("../src/api.js?case=" + sequence++);
  globalThis.fetch = async (path, options) =>
    path.includes("/login")
      ? ok("original-token")
      : ok({ id: "1", name: "original" });
  await mod.login("buyer", { username: "original", password: "test-only" });
  return mod;
}
test("刷新候选账户核对期间不能发布令牌给并发保存", async () => {
  const oldFetch = globalThis.fetch;
  try {
    const mod = await fixture(),
      entered = deferred(),
      release = deferred();
    let sent;
    globalThis.fetch = async (path, options) => {
      if (path.includes("/refresh")) return ok("candidate-other");
      if (path.includes("/users/me")) {
        assert.equal(options.headers.authorization, "candidate-other");
        entered.resolve();
        await release.promise;
        return ok({ id: "2" });
      }
      sent = options.headers.authorization;
      return ok({ id: "saved" });
    };
    const refresh = mod.refreshSession("buyer");
    const rejected = assert.rejects(refresh, /账户已变化/);
    await entered.promise;
    assert.equal(mod.sessions.buyer.token, "original-token");
    await mod.api("/trade/requirements", {
      scope: "buyer",
      method: "POST",
      body: { title: "original draft" },
    });
    assert.equal(sent, "original-token");
    release.resolve();
    await rejected;
    assert.equal(mod.sessions.buyer.token, "");
  } finally {
    globalThis.fetch = oldFetch;
  }
});
test("恢复登录先核对候选身份，错误账户撤销后不留下全局令牌", async () => {
  const oldFetch = globalThis.fetch;
  try {
    const mod = await fixture(),
      entered = deferred(),
      release = deferred();
    let revoked;
    globalThis.fetch = async (path, options) => {
      if (path.includes("/login")) return ok("candidate-other");
      if (path.includes("/users/me")) {
        entered.resolve();
        await release.promise;
        return ok({ id: "2" });
      }
      if (path.includes("/logout")) {
        revoked = options.headers.authorization;
        return ok(null);
      }
      throw new Error("unexpected request");
    };
    const login = mod.login(
      "buyer",
      { username: "other", password: "test-only" },
      "1",
    );
    const rejected = assert.rejects(login, /请使用原账户/);
    await entered.promise;
    assert.equal(mod.sessions.buyer.token, "");
    assert.equal(mod.sessions.buyer.user, null);
    release.resolve();
    await rejected;
    assert.equal(revoked, "candidate-other");
    assert.equal(mod.sessions.buyer.token, "");
  } finally {
    globalThis.fetch = oldFetch;
  }
});
test("同账户刷新核对通过后才替换令牌", async () => {
  const oldFetch = globalThis.fetch;
  try {
    const mod = await fixture(),
      entered = deferred(),
      release = deferred();
    globalThis.fetch = async (path) => {
      if (path.includes("/refresh")) return ok("replacement-token");
      entered.resolve();
      await release.promise;
      return ok({ id: "1" });
    };
    const refresh = mod.refreshSession("buyer");
    await entered.promise;
    assert.equal(mod.sessions.buyer.token, "original-token");
    release.resolve();
    await refresh;
    assert.equal(mod.sessions.buyer.token, "replacement-token");
  } finally {
    globalThis.fetch = oldFetch;
  }
});
