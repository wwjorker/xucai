import { requirementItems } from "./requirements.js";

// 旧接口以 purchaseMessage 区分交易降级和正常尚未建售卖行；两者都有 priceAvailable=false。
export function catalogSaleUnavailable(products) {
  return products.some(
    (product) =>
      product.status === "PUBLISHED" &&
      product.purchaseMessage === "价格暂不可用",
  );
}

export function validQuantity(value) {
  return (
    /^[0-9]{1,5}$/.test(String(value)) &&
    Number(value) >= 1 &&
    Number(value) <= 10000
  );
}

export function knownPrice(product) {
  const price = product.sale?.price;
  return (
    product.priceAvailable !== false &&
    price != null &&
    price !== "" &&
    Number.isSafeInteger(Number(price)) &&
    Number(price) > 0
  );
}

// 只使用已保存条件，不从原文、人数或总预算推断分类件数。
export function categoryProgress(details, lines) {
  return requirementItems(details).map((item) => {
    const selected = lines.filter(
      (line) => Number(line.product.categoryId) === Number(item.categoryId),
    );
    const known = selected.every((line) => validQuantity(line.quantity));
    const quantity = validQuantity(item.quantity)
      ? Number(item.quantity)
      : null;
    const chosen = known
      ? selected.reduce((sum, line) => sum + Number(line.quantity), 0)
      : null;
    return {
      categoryId: item.categoryId,
      quantity,
      chosen,
      remaining:
        quantity != null && chosen != null
          ? Math.max(0, quantity - chosen)
          : null,
    };
  });
}

export function suggestedQuantity(details, lines, categoryId) {
  const row = categoryProgress(details, lines).find(
    (row) => Number(row.categoryId) === Number(categoryId),
  );
  return row?.remaining > 0 ? String(row.remaining) : "";
}

export function draftBudget(details, lines) {
  const complete = lines.every(
    (line) => validQuantity(line.quantity) && knownPrice(line.product),
  );
  const amount = complete
    ? lines.reduce(
        (sum, line) =>
          sum + Number(line.quantity) * Number(line.product.sale.price),
        0,
      )
    : null;
  const budget =
    details.budgetCents == null ? null : Number(details.budgetCents);
  return {
    amount,
    remaining: amount != null && budget != null ? budget - amount : null,
  };
}

export function filterCandidates(
  products,
  details,
  { category = "required", keyword = "" } = {},
) {
  const ids = new Set(
    requirementItems(details).map((item) => Number(item.categoryId)),
  );
  const words = keyword.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  return products.filter(
    (product) =>
      product.status === "PUBLISHED" &&
      product.sale?.onSale &&
      (category === "all" ||
        (category === "required" &&
          (!ids.size || ids.has(Number(product.categoryId)))) ||
        String(product.categoryId) === String(category)) &&
      words.every((word) => product.name.toLocaleLowerCase().includes(word)),
  );
}
