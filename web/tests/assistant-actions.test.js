import test from "node:test";
import assert from "node:assert/strict";
import { createAssistantRequestGate } from "../src/assistantRequestGate.js";
import { retryQuestion } from "../src/assistantRetry.js";

test("refresh cannot complete or unlock a pending submit", () => {
  const gate = createAssistantRequestGate(), oldRead = gate.read(), submit = gate.begin();
  assert.equal(gate.current(oldRead), false);
  assert.equal(gate.read(), null);
  assert.equal(gate.begin(), null);
  assert.equal(gate.writing, true);
  gate.end(submit);
  assert.equal(gate.current(oldRead), false);
  const fresh = gate.read(); assert.equal(gate.current(fresh), true);
});
test("a late completion cannot release a newer cancel or edit request", () => {
  const gate = createAssistantRequestGate(), submit = gate.begin();
  gate.end(submit);
  const cancel = gate.begin(); gate.end(submit);
  assert.equal(gate.writing, true); assert.equal(gate.read(), null);
  gate.end(cancel); assert.equal(gate.writing, false);
});
test("only the newest read may update the visible conversation", () => {
  const gate = createAssistantRequestGate(), first = gate.read(), second = gate.read();
  assert.equal(gate.current(first), false); assert.equal(gate.current(second), true);
});
test("retry is bound to the selected read-only answer, never a later purchase instruction", () => {
  const history = [{ role: "user", text: "找鼠标" }, { id: "answer1", role: "assistant", canRetry: true }];
  assert.equal(retryQuestion(history, "answer1"), "找鼠标");
  history.push({ role: "user", text: "再加两件" }, { id: "answer2", role: "assistant", canRetry: false });
  assert.equal(retryQuestion(history, "answer1"), null);
  assert.equal(retryQuestion(history, "answer2"), null);
  assert.equal(retryQuestion(history, null), null);
});
