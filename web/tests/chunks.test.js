import test from "node:test";
import assert from "node:assert/strict";
import {
  highlight,
  contextWindow,
  searchParams,
  indexLabel,
  dropReason,
} from "../src/chunks.js";

test("高亮只按后端偏移切分，越界或重叠都不会丢字", () => {
  const text = "不支持自动双面打印；需要手动翻面。";
  const parts = highlight(text, [
    { start: 3, length: 2 },
    { start: 4, length: 4 },
  ]);
  assert.equal(parts.map((p) => p.text).join(""), text);
  assert.deepEqual(
    parts.filter((p) => p.hit).map((p) => p.text),
    ["自动双面打"],
  );
  for (const bad of [
    { start: -1, length: 2 },
    { start: 2, length: 0 },
    { start: 10, length: 999 },
    { start: "x", length: 2 },
  ]) {
    const only = highlight(text, [bad]);
    assert.equal(only.map((p) => p.text).join(""), text);
    assert.equal(
      only.some((p) => p.hit),
      false,
    );
  }
  assert.equal(highlight("", []).length, 1);
});

test("没有关键词时整段按原文返回，不伪造命中", () => {
  const parts = highlight("110—120V机型", []);
  assert.deepEqual(parts, [{ text: "110—120V机型", hit: false }]);
});

test("定位窗口给出前后文，偏移对不上时明确失败", () => {
  const content = "# 供电\n本页标注为110—120V机型。直接接入220V电网可能损坏设备。";
  const start = content.indexOf("110—120V机型");
  const window = contextWindow(content, start, start + 9, 4);
  assert.equal(window.ok, true);
  assert.equal(window.target, content.slice(start, start + 9));
  assert.equal(window.before, content.slice(start - 4, start));
  assert.equal(window.clippedStart, true);
  for (const [from, to] of [
    [-1, 5],
    [5, 5],
    [8, 4],
    [0, content.length + 1],
  ]) {
    const bad = contextWindow(content, from, to);
    assert.equal(bad.ok, false);
    assert.equal(bad.target, "");
  }
});

test("定位窗口不越界，短正文不会被截断标记误导", () => {
  const content = "USB-C下行数据口不传视频";
  const window = contextWindow(content, 0, content.length, 100);
  assert.equal(window.target, content);
  assert.equal(window.clippedStart, false);
  assert.equal(window.clippedEnd, false);
});

test("空筛选不进查询串，关键词裁剪到120字", () => {
  assert.deepEqual(searchParams({}), { page: 1, size: 20 });
  assert.deepEqual(
    searchParams(
      { keyword: "  双面 220V ", productId: 24, categoryId: 2, docKey: " spec ", includeUnpublished: true },
      2,
      10,
    ),
    {
      page: 2,
      size: 10,
      keyword: "双面 220V",
      productId: 24,
      categoryId: 2,
      docKey: "spec",
      includeUnpublished: true,
    },
  );
  assert.equal(searchParams({ keyword: "字".repeat(200) }).keyword.length, 120);
  assert.equal(searchParams({ keyword: "   " }).keyword, undefined);
  assert.equal(searchParams({ includeUnpublished: false }).includeUnpublished, undefined);
  for (const productId of ["abc", "0", "-3", "2.5", "", "  "])
    assert.equal(searchParams({ productId }).productId, undefined, String(productId));
  assert.equal(searchParams({ productId: " 24 " }).productId, 24);
});

test("索引状态与丢弃原因都有中文说明，未知值原样显示", () => {
  assert.equal(indexLabel("READY").label, "片段可用");
  assert.equal(indexLabel("STALE").tone, "warning");
  assert.equal(indexLabel("WHAT").label, "WHAT");
  assert.equal(dropReason("TEXT_MISMATCH"), "片段正文与原文对不上");
  assert.equal(dropReason("OTHER"), "OTHER");
});
