import test from "node:test";
import assert from "node:assert/strict";
import { assistantTextParts } from "../src/assistantText.js";
test("source Markdown becomes a labelled link alongside emphasis", () => {
  const parts = assistantTextParts("**来源**：[官方资料](https://example.com/spec) 与 `USB`");
  assert.deepEqual(parts.find(p => p.href), { text: "官方资料", href: "https://example.com/spec" });
  assert.ok(parts.some(p => p.bold && p.text === "来源"));
  assert.ok(parts.some(p => p.code && p.text === "USB"));
});
test("executable URLs, credentials and raw HTML are never links", () => {
  for (const text of ["[打开](javascript:alert(1))", "[打开](data:text/html,test)", "[打开](https://user:pass@example.com)", '<img src=x onerror="alert(1)">']) {
    const parts = assistantTextParts(text);
    assert.equal(parts.some(p => p.href), false);
    assert.equal(parts.map(p => p.text).join(""), text);
  }
});
