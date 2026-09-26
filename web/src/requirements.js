/** 新明细为空表示明确清空；旧多分类总数不能复制或平均分配。 */
export function legacyRequirementTotal(requirement) {
  const details = requirement?.structured;
  return details && details.items == null && details.categoryIds.length !== 1
    ? details.quantity
    : null;
}

export function requirementItems(details) {
  if (details.items != null)
    return details.items.map((item) => ({
      categoryId: item.categoryId,
      quantity: item.quantity ?? undefined,
    }));
  return details.categoryIds.map((categoryId) => ({
    categoryId,
    quantity:
      details.categoryIds.length === 1
        ? (details.quantity ?? undefined)
        : undefined,
  }));
}
export function requirementTotal(items) {
  if (
    !items.length ||
    items.some(
      (item) =>
        item.quantity == null ||
        item.quantity === "" ||
        !Number.isInteger(Number(item.quantity)) ||
        Number(item.quantity) < 1,
    )
  )
    return null;
  return items.reduce((sum, item) => sum + Number(item.quantity), 0);
}
export function parseRequirementQuantity(value, label) {
  if (value == null || value === "") return null;
  if (
    !/^[0-9]{1,5}$/.test(String(value)) ||
    Number(value) < 1 ||
    Number(value) > 10000
  )
    throw new Error(`${label}数量应为1至10000的整数，未知时请留空`);
  return Number(value);
}
function signature(items) {
  return JSON.stringify(
    items
      .map((item) => ({
        categoryId: item.categoryId,
        quantity:
          item.quantity == null || item.quantity === ""
            ? null
            : Number(item.quantity),
      }))
      .sort((a, b) => a.categoryId - b.categoryId),
  );
}
export function prepareRequirementQuantities(previous, items) {
  if (
    previous &&
    previous.items == null &&
    signature(requirementItems(previous)) === signature(items)
  )
    return {
      categoryIds: previous.categoryIds,
      quantity: previous.quantity ?? null,
      replacesLegacyTotal: false,
    };
  const quantity = requirementTotal(items);
  return {
    items,
    categoryIds: items.map((item) => item.categoryId),
    quantity,
    replacesLegacyTotal:
      previous?.items == null &&
      previous?.quantity != null &&
      quantity !== previous.quantity,
  };
}
