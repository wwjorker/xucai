import test from "node:test";
import assert from "node:assert/strict";
import {
  categoryProgress,
  suggestedQuantity,
  draftBudget,
  filterCandidates,
  catalogSaleUnavailable,
} from "../src/plan-candidates.js";
const details = {
  categoryIds: [1, 3],
  items: [
    { categoryId: 1, quantity: 2 },
    { categoryId: 3, quantity: 2 },
  ],
  budgetCents: 10000,
};
const line = (categoryId, quantity, price = 1000) => ({
  product: { categoryId, sale: { price } },
  quantity,
});
test("正常发布未开售不阻断目录，实际售卖降级明确拒绝刷新", () => {
  const product = { status: "PUBLISHED", priceAvailable: false, sale: null };
  assert.equal(
    catalogSaleUnavailable([{ ...product, purchaseMessage: "暂未开售" }]),
    false,
  );
  assert.equal(
    catalogSaleUnavailable([{ ...product, purchaseMessage: "价格暂不可用" }]),
    true,
  );
});
test("分类余量扣除同类已选，不重复填满每款商品", () => {
  assert.equal(suggestedQuantity(details, [line(1, "1")], 1), "1");
  assert.equal(suggestedQuantity(details, [line(1, "2")], 1), "");
  assert.equal(categoryProgress(details, [line(1, "3")])[0].chosen, 3);
});
test("未知分类件数和旧多分类总数不猜分配", () => {
  assert.deepEqual(categoryProgress({ ...details, items: [] }, []), []);
  assert.equal(
    suggestedQuantity({ categoryIds: [1, 3], quantity: 4 }, [], 1),
    "",
  );
  assert.equal(
    suggestedQuantity({ categoryIds: [1], quantity: 2 }, [], 1),
    "2",
  );
  assert.equal(
    suggestedQuantity(
      { ...details, items: [{ categoryId: 1, quantity: null }] },
      [],
      1,
    ),
    "",
  );
});
test("已选件数非法时停止余量推断及整单预算估算", () => {
  for (const qty of ["", "1.5", "-1", "1e2", "10001"]) {
    assert.equal(suggestedQuantity(details, [line(1, qty)], 1), "");
    assert.equal(draftBudget(details, [line(1, qty)]).amount, null);
  }
});
test("全部类别共用总预算，超支保留负余额", () => {
  assert.deepEqual(draftBudget(details, [line(1, 2, 3000), line(3, 2, 2500)]), {
    amount: 11000,
    remaining: -1000,
  });
  assert.deepEqual(
    draftBudget({ ...details, budgetCents: null }, [line(1, 2)]),
    { amount: 2000, remaining: null },
  );
});
test("价格未知时不能展示虚假的可用预算", () => {
  assert.equal(
    draftBudget(details, [{ product: { sale: { price: null } }, quantity: 1 }])
      .remaining,
    null,
  );
});
test("默认筛选需求分类，名称匹配不冒充规格匹配", () => {
  const products = [
    {
      id: 1,
      categoryId: 1,
      name: "DELL P2425H",
      status: "PUBLISHED",
      sale: { onSale: true },
    },
    {
      id: 2,
      categoryId: 2,
      name: "打印机",
      status: "PUBLISHED",
      sale: { onSale: true },
    },
    {
      id: 3,
      categoryId: 1,
      name: "DELL 隐藏",
      status: "HIDDEN",
      sale: { onSale: true },
    },
  ];
  assert.deepEqual(
    filterCandidates(products, details).map((p) => p.id),
    [1],
  );
  assert.equal(
    filterCandidates(products, details, { category: "all" }).length,
    2,
  );
  assert.equal(
    filterCandidates(products, details, { keyword: "dell p24" }).length,
    1,
  );
  assert.equal(
    filterCandidates(products, details, { keyword: "HDMI" }).length,
    0,
  );
  assert.equal(filterCandidates(products, { categoryIds: [] }).length, 2);
});
