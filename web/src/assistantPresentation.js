// Hide only the server's exact card inventory when the same cards are rendered.
// Keep unrecognized text and partial streamed inventories intact.
export function replyWithoutCardInventory(text = "", products = []) {
  if (!products.length || !text.startsWith("商品选项：\n")) return text;
  const lines = text.split("\n");
  for (let i = 0; i < products.length; i++) {
    const p = products[i];
    const name = String(p.name).replace(/[\r\n]/g, " ");
    const prefix = `选项 ${i + 1}：${name}`;
    const line = lines[i + 1];
    if (p.price == null ? line !== prefix :
      !line?.startsWith(prefix + " · ¥") ||
      !/^\d+(?:\.\d+)?$/.test(line.slice((prefix + " · ¥").length)) ||
      Number(line.slice((prefix + " · ¥").length)) !== Number(p.price)) return text;
  }
  if (lines[products.length + 1] !== "") return text;
  return lines.slice(products.length + 2).join("\n");
}

export function conversationTitle(text = "") {
  return [...text.replace(/\s+/g, " ").trim()].slice(0, 48).join("") || "新对话";
}

export function historyTitle(session) {
  if (session.title !== "新对话") return session.title;
  const date = new Date(session.createdAt || session.updatedAt);
  if (Number.isNaN(date.getTime())) return "未命名对话";
  return `未命名对话 · ${date.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}`;
}
