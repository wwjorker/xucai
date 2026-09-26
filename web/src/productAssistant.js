import { shallowRef } from "vue";

// Ephemeral, account-scoped UI intent. No conversation or credentials are stored here.
export const productAssistantIntent = shallowRef(null);
export function requestProductAssistant(product, owner) {
  if (!/^[1-9]\d*$/.test(String(product?.id || "")) || !owner || !product?.name)
    throw new Error("暂时无法识别这件商品，请刷新详情后重试");
  productAssistantIntent.value = {
    owner: String(owner),
    product: { id: String(product.id), name: String(product.name).slice(0, 200) },
  };
}
export function productQuestion(product, text) {
  const question = String(text || "").trim();
  if (!question) throw new Error("请先输入想了解的问题");
  const result = product
    ? `我正在查看商品「${product.name}」。关于这款商品：${question}`
    : question;
  if (result.length > 4000) throw new Error("问题太长，请缩短后再发送");
  return result;
}
