import {
  requirementItems,
  prepareRequirementQuantities,
} from "./requirements.js";

export function suggestionBlockReason(run, current) {
  if (run?.result?.candidate || run?.promptVersion?.startsWith("candidate-"))
    return "候选参考不能填入采购条件";
  if (!current || current.status !== "OPEN")
    return "只有进行中的需求可以填入建议";
  if (run?.status !== "DONE" || !run.result?.conditions)
    return "这条记录没有可采用的建议";
  if (
    String(run.requirementId) !== String(current.id) ||
    String(run.requirementRevision) !== String(current.revision)
  )
    return "建议属于旧版本，请基于当前已保存需求重新整理";
  return "";
}

export function suggestionForm(run, current) {
  const reason = suggestionBlockReason(run, current);
  if (reason) throw new Error(reason);
  const d = run.result.conditions;
  const items = requirementItems(d);
  const roundTrip = prepareRequirementQuantities(current.structured, items);
  if ((roundTrip.quantity ?? null) !== (d.quantity ?? null))
    throw new Error(
      "建议中的总件数尚未分配到各类设备，请参照建议手工填写分类数量",
    );
  return {
    budget: d.budgetCents != null ? String(Number(d.budgetCents) / 100) : "",
    items,
    usage: d.usage,
    mustHave: d.mustHave.join("\n"),
    optional: d.optional.join("\n"),
  };
}
