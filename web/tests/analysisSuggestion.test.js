import test from "node:test";
import assert from "node:assert/strict";
import {
  suggestionBlockReason,
  suggestionForm,
} from "../src/analysisSuggestion.js";
const empty = {
  categoryIds: [],
  quantity: null,
  usage: "",
  mustHave: [],
  optional: [],
};
const current = { id: "40", revision: "1", status: "OPEN", structured: empty };
const conditions = {
  ...empty,
  budgetCents: "300000",
  quantity: 2,
  categoryIds: [1],
  items: [{ categoryId: 1, quantity: 2 }],
  mustHave: ["HDMI"],
};
const run = {
  requirementId: "40",
  requirementRevision: "1",
  status: "DONE",
  result: { conditions },
};
test("商品候选参考不能当作需求抽取建议采用", () => {
  for (const candidate of [
    { ...run, promptVersion: "candidate-reference-v1" },
    { ...run, result: { ...run.result, candidate: { status: "REFERENCE" } } },
  ]) {
    assert.match(suggestionBlockReason(candidate, current), /候选参考/);
    assert.throws(() => suggestionForm(candidate, current), /候选参考/);
  }
});
test("只转入条件且深复制，不修改历史建议", () => {
  const f = suggestionForm(run, current);
  assert.equal(f.budget, "3000");
  assert.equal(f.title, undefined);
  assert.equal(f.rawText, undefined);
  f.items[0].quantity = 9;
  assert.equal(run.result.conditions.items[0].quantity, 2);
});
test("不同需求、旧版本、已关闭和未完成都拒绝", () => {
  for (const r of [
    { ...run, requirementId: "41" },
    { ...run, requirementRevision: "2" },
    { ...run, status: "FAILED" },
  ])
    assert.ok(suggestionBlockReason(r, current));
  assert.ok(suggestionBlockReason(run, { ...current, status: "CLOSED" }));
});
test("未知数量不自动填0或猜测", () => {
  const f = suggestionForm(
    {
      ...run,
      result: {
        conditions: {
          ...empty,
          categoryIds: [3],
          items: [{ categoryId: 3, quantity: null }],
        },
      },
    },
    current,
  );
  assert.equal(f.items[0].quantity, undefined);
  assert.equal(f.budget, "");
});
test("新多分类总数未分配时拒绝静默丢失", () => {
  assert.throws(
    () =>
      suggestionForm(
        {
          ...run,
          result: {
            conditions: { ...empty, quantity: 6, categoryIds: [3, 4] },
          },
        },
        current,
      ),
    /尚未分配/,
  );
});
test("旧多分类总量可由既有保存逻辑无损保留", () => {
  const d = { ...empty, quantity: 6, categoryIds: [3, 4] };
  assert.equal(
    suggestionForm(
      { ...run, result: { conditions: d } },
      { ...current, structured: d },
    ).items.length,
    2,
  );
});
