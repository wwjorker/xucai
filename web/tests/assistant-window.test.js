import test from "node:test";
import assert from "node:assert/strict";
import { fitWindow } from "../src/components/assistantWindow.js";
test("dragging keeps the whole window reachable inside the viewport", () => {
  assert.deepEqual(
    fitWindow(
      { x: -200, y: 1600, width: 530, height: 680 },
      { width: 1440, height: 900 },
    ),
    { x: 0, y: 220, width: 530, height: 680 },
  );
});
test("a viewport shrink fits the window without leaving the header off screen", () => {
  assert.deepEqual(
    fitWindow(
      { x: 900, y: 80, width: 700, height: 680 },
      { width: 390, height: 350 },
    ),
    { x: 0, y: 0, width: 390, height: 350 },
  );
});
