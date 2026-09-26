// 资料片段页面的纯函数：高亮、原文定位窗口、索引状态文案。
// 这里不做任何语义判断，只把后端返回的偏移和状态翻译成页面结构。

export const INDEX_STATES = {
  READY: { label: "片段可用", tone: "success" },
  NOT_BUILT: { label: "尚未建立片段", tone: "info" },
  STALE: { label: "需要重建", tone: "warning" },
  FAILED: { label: "切分失败", tone: "danger" },
};

export const indexLabel = (state) =>
  INDEX_STATES[state] || { label: state || "未知", tone: "info" };

/** 把命中区间切成 {text, hit} 片段，交给模板按文本渲染，不用 v-html。 */
export function highlight(text, matches = []) {
  const source = String(text ?? "");
  const ranges = [];
  for (const m of matches) {
    const start = Number(m?.start),
      length = Number(m?.length);
    if (!Number.isInteger(start) || !Number.isInteger(length)) continue;
    if (start < 0 || length <= 0 || start + length > source.length) continue;
    ranges.push([start, start + length]);
  }
  ranges.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const merged = [];
  for (const [start, end] of ranges) {
    const last = merged[merged.length - 1];
    if (last && start <= last[1]) last[1] = Math.max(last[1], end);
    else merged.push([start, end]);
  }
  const parts = [];
  let cursor = 0;
  for (const [start, end] of merged) {
    if (start > cursor) parts.push({ text: source.slice(cursor, start), hit: false });
    parts.push({ text: source.slice(start, end), hit: true });
    cursor = end;
  }
  if (cursor < source.length) parts.push({ text: source.slice(cursor), hit: false });
  return parts.length ? parts : [{ text: source, hit: false }];
}

/**
 * 原文定位窗口。偏移对不上就明说对不上，不去猜一个近似位置，
 * 否则页面会把"引用存在"演成"引用正确"。
 */
export function contextWindow(content, start, end, radius = 160) {
  const source = String(content ?? "");
  const from = Number(start),
    to = Number(end);
  if (
    !Number.isInteger(from) ||
    !Number.isInteger(to) ||
    from < 0 ||
    to > source.length ||
    to <= from
  )
    return { ok: false, before: "", target: "", after: "", clippedStart: false, clippedEnd: false };
  const left = Math.max(0, from - radius),
    right = Math.min(source.length, to + radius);
  return {
    ok: true,
    before: source.slice(left, from),
    target: source.slice(from, to),
    after: source.slice(to, right),
    clippedStart: left > 0,
    clippedEnd: right < source.length,
  };
}

/** 空值不进查询串，避免把空筛选发成 productId= 这种无效条件。 */
export function searchParams(form, page = 1, size = 20) {
  const out = { page, size };
  const keyword = String(form.keyword ?? "").trim();
  if (keyword) out.keyword = keyword.slice(0, 120);
  // 商品编号是手输的，非正整数就不带进查询，免得后端只能回400。
  const productId = Number(String(form.productId ?? "").trim());
  if (Number.isInteger(productId) && productId > 0) out.productId = productId;
  if (form.categoryId) out.categoryId = Number(form.categoryId);
  const docKey = String(form.docKey ?? "").trim();
  if (docKey) out.docKey = docKey;
  if (form.includeUnpublished) out.includeUnpublished = true;
  return out;
}

export const DROP_REASONS = {
  CONTENT_MISSING: "读不到资料正文",
  CONTENT_CHANGED: "正文与建索引时不一致",
  OWNERSHIP_MISMATCH: "片段不属于所选商品",
  ID_MISMATCH: "片段编号与资料版本不符",
  RANGE_OUT_OF_TEXT: "偏移超出正文范围",
  TEXT_MISMATCH: "片段正文与原文对不上",
};
export const dropReason = (code) => DROP_REASONS[code] || code;
