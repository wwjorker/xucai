export function fitWindow(rect, viewport) {
  const width = Math.min(Math.max(360, rect.width), viewport.width);
  const height = Math.min(Math.max(420, rect.height), viewport.height);
  return {
    width,
    height,
    x: Math.max(0, Math.min(rect.x, viewport.width - width)),
    y: Math.max(0, Math.min(rect.y, viewport.height - height)),
  };
}
