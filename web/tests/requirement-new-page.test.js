import test from "node:test";
import assert from "node:assert/strict";
import { legacyRequirementTotal } from "../src/requirements.js";

test("新建需求无已保存对象时仍能显示表单，旧多分类总数不丢失", () => {
  assert.equal(legacyRequirementTotal(null), null);
  assert.equal(legacyRequirementTotal(undefined), null);
  assert.equal(legacyRequirementTotal({ structured: { categoryIds: [3, 4], quantity: 6 } }), 6);
  assert.equal(legacyRequirementTotal({ structured: { categoryIds: [3], quantity: 6 } }), null);
  assert.equal(legacyRequirementTotal({ structured: { categoryIds: [3, 4], quantity: 6, items: [] } }), null);
});
