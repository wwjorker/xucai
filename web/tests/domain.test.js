import test from "node:test";
import assert from "node:assert/strict";
import { toCents, safeUrl } from "../src/domain.js";

test("人民币金额准确转分，拒绝截断小数和越界值", () => {
  assert.equal(toCents("0.29"), 29);
  assert.equal(toCents("12.3"), 1230);
  assert.equal(toCents("1000000.00"), 100000000);
  for (const value of ["0", "-1", "0.001", "1e2", "1000000.01", "", "abc"])
    assert.throws(() => toCents(value));
});
test("资料和图片URL仅接受无嵌入凭据的HTTP地址", () => {
  assert.equal(safeUrl("https://example.com/spec"), "https://example.com/spec");
  for (const value of [
    "javascript:alert(1)",
    "data:text/html,hello",
    "https://name:password@example.com",
    "/local",
    null,
  ])
    assert.equal(safeUrl(value), null);
});
