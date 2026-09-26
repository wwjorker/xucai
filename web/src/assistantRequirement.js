import { requirementItems } from "./requirements.js";

export function assistantRequirementForm(turn) {
  const draft = turn?.result?.requirementDraft;
  if (turn?.status !== "DONE" || !draft?.title || !draft?.rawText || !draft?.structured)
    throw new Error("这条对话没有可用的采购需求草稿，请回到助手重新整理。");
  const d = draft.structured;
  return {
    title: draft.title, rawText: draft.rawText,
    budget: d.budgetCents == null ? "" : String(Number(d.budgetCents) / 100),
    items: requirementItems(d), usage: d.usage,
    mustHave: d.mustHave.join("\n"), optional: d.optional.join("\n"),
  };
}
