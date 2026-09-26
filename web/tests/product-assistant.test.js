import test from "node:test";
import assert from "node:assert/strict";
import { requestProductAssistant, productAssistantIntent, productQuestion } from "../src/productAssistant.js";

test("product handoff preserves large IDs and identifies the requesting account", () => {
  requestProductAssistant({ id: "9223372036854775807", name: "设备甲" }, 7);
  assert.deepEqual(productAssistantIntent.value, { owner: "7", product: { id: "9223372036854775807", name: "设备甲" } });
  assert.throws(() => requestProductAssistant({ id: "../other", name: "设备" }, 7));
  assert.throws(() => requestProductAssistant({ id: "1", name: "设备" }, null));
  productAssistantIntent.value = null;
});
test("product question carries its name and preserves the question", () => {
  assert.equal(productQuestion({ name: "罗技 C270" }, " 支持USB吗？ "), "我正在查看商品「罗技 C270」。关于这款商品：支持USB吗？");
  assert.equal(productQuestion(null, "查看订单"), "查看订单");
  assert.throws(() => productQuestion({ name: "设备" }, " "));
  assert.throws(() => productQuestion({ name: "设备" }, "字".repeat(4000)));
});
