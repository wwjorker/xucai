import test from "node:test";
import assert from "node:assert/strict";
import {
  requirementItems,
  requirementTotal,
  parseRequirementQuantity,
  prepareRequirementQuantities,
} from "../src/requirements.js";

test("旧多分类和无分类只改其他字段时保留原总量", () => {
  for (const categoryIds of [[3, 4, 6], []]) {
    const old = { categoryIds, quantity: 6 };
    const prepared = prepareRequirementQuantities(old, requirementItems(old));
    assert.equal(prepared.quantity, 6);
    assert.equal(prepared.items, undefined);
    assert.equal(prepared.replacesLegacyTotal, false);
  }
});
test("改变分类明细使旧总量未知或变化时必须确认，等量补齐无需确认", () => {
  const old = { categoryIds: [3, 4], quantity: 6 };
  assert.equal(
    prepareRequirementQuantities(old, [
      { categoryId: 3, quantity: 2 },
      { categoryId: 4, quantity: null },
    ]).replacesLegacyTotal,
    true,
  );
  assert.equal(prepareRequirementQuantities(old, []).replacesLegacyTotal, true);
  assert.equal(
    prepareRequirementQuantities(old, [
      { categoryId: 3, quantity: 2 },
      { categoryId: 4, quantity: 4 },
    ]).replacesLegacyTotal,
    false,
  );
});

test("旧多分类总数不复制或平均分配，单分类可无歧义读取", () => {
  assert.deepEqual(
    requirementItems({ categoryIds: [3, 4, 6], quantity: 6 }),
    [3, 4, 6].map((categoryId) => ({ categoryId, quantity: undefined })),
  );
  assert.deepEqual(requirementItems({ categoryIds: [3], quantity: 3 }), [
    { categoryId: 3, quantity: 3 },
  ]);
});
test("显式空明细不复活旧分类；新明细不受旧汇总影响", () => {
  assert.deepEqual(
    requirementItems({ items: [], categoryIds: [3], quantity: 6 }),
    [],
  );
  assert.deepEqual(
    requirementItems({
      items: [{ categoryId: 4, quantity: 2 }],
      categoryIds: [3],
      quantity: 6,
    }),
    [{ categoryId: 4, quantity: 2 }],
  );
});
test("只有数量齐全才计算总数，空或小数不能悄悄变成整数", () => {
  assert.equal(requirementTotal([{ quantity: 2 }, { quantity: "3" }]), 5);
  for (const quantity of [undefined, null, "", 1.5, 0])
    assert.equal(requirementTotal([{ quantity: 2 }, { quantity }]), null);
  assert.equal(parseRequirementQuantity("", "鼠标"), null);
  assert.equal(parseRequirementQuantity("2", "鼠标"), 2);
  for (const value of ["1.5", "-1", "0", "10001", "2e2", " "])
    assert.throws(() => parseRequirementQuantity(value, "鼠标"));
});
