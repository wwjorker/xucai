import test from "node:test";
import assert from "node:assert/strict";
import { chunkSelectionError } from "../src/plan-chunks.js";
const line = {
  product: {
    id: "1",
    documents: [{ id: "9", version: "2", content: "不支持视频。" }],
  },
};
const chunk = {
  productId: "1",
  docId: "9",
  version: "2",
  ruleVersion: "chunk-v1",
  charStart: 0,
  charEnd: 6,
  text: "不支持视频。",
};
test("片段只能进入同一商品的同一资料版本", () => {
  assert.equal(chunkSelectionError(line, chunk), "");
  assert.match(
    chunkSelectionError(line, { ...chunk, productId: "2" }),
    /不属于/,
  );
  assert.match(
    chunkSelectionError(line, { ...chunk, docId: "10" }),
    /版本已变化/,
  );
  assert.match(
    chunkSelectionError(line, { ...chunk, version: "3" }),
    /版本已变化/,
  );
});
test("选择器逐字核对原文，不能把否定片段改成肯定", () => {
  assert.match(
    chunkSelectionError(line, { ...chunk, text: "支持视频。" }),
    /不一致/,
  );
  assert.match(
    chunkSelectionError(line, { ...chunk, charStart: -1 }),
    /不一致/,
  );
  assert.match(
    chunkSelectionError(line, { ...chunk, ruleVersion: null }),
    /不一致/,
  );
});
test("超长合法片段提示手工摘录，不截断后冒充完整片段", () => {
  const text = "字".repeat(1001);
  const longLine = {
    product: { id: "1", documents: [{ id: "9", version: "2", content: text }] },
  };
  assert.match(
    chunkSelectionError(longLine, { ...chunk, text, charEnd: text.length }),
    /超过1000字/,
  );
});
