import test from "node:test";
import assert from "node:assert/strict";
import { replyWithoutCardInventory, conversationTitle, historyTitle } from "../src/assistantPresentation.js";

test("matching inventory is replaced by cards without losing the recommendation", () => {
  const text = "商品选项：\n选项 1：M100 · ¥39.0\n\n四件156元，不支持蓝牙。";
  assert.equal(replyWithoutCardInventory(text, [{name:"M100",price:39}]), "四件156元，不支持蓝牙。");
  for (const cards of [[], [{name:"M185",price:39}], [{name:"M100",price:79}]])
    assert.equal(replyWithoutCardInventory(text,cards),text);
  assert.equal(replyWithoutCardInventory("商品选项：\n选项 1：M100 · ¥",[{name:"M100",price:39}]),"商品选项：\n选项 1：M100 · ¥");
});
test("titles use the user's first message and preserve renamed history", () => {
  assert.equal(conversationTitle("  预算200元\n买四个鼠标 "),"预算200元 买四个鼠标");
  assert.equal([...conversationTitle("鼠".repeat(70))].length,48);
  assert.equal(historyTitle({title:"我的选购"}),"我的选购");
  assert.match(historyTitle({title:"新对话",createdAt:"2026-09-26T10:20:00+08:00"}),/^未命名对话 · /);
});
