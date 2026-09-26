// 已选择商品的资料快照是表单依据；不能把最新片段悄悄塞进旧快照。
export function chunkSelectionError(line, item) {
  if (String(line?.product?.id) !== String(item?.productId))
    return "片段不属于当前商品，请重新查找。";
  const doc = line.product.documents.find(
    (d) =>
      String(d.id) === String(item.docId) &&
      String(d.version) === String(item.version),
  );
  if (!doc) return "资料版本已变化，请关闭窗口并重新读取商品后再选取。";
  if (typeof item.text !== "string" || item.text.length > 1000)
    return "本段超过1000字，请查看原文后手工摘录所需内容；手工摘录不标记片段出处。";
  if (
    !item.ruleVersion ||
    !Number.isInteger(item.charStart) ||
    !Number.isInteger(item.charEnd) ||
    item.charStart < 0 ||
    item.charEnd <= item.charStart ||
    doc.content.slice(item.charStart, item.charEnd) !== item.text
  )
    return "片段与所选资料原文不一致，请重新读取商品。";
  return "";
}
