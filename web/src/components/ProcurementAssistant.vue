<script setup>
import { onMounted, onBeforeUnmount, ref, watch, nextTick } from "vue";
import { useRouter } from "vue-router";
import { api, sessions as auth } from "../api";
import AssistantPanel from "./AssistantPanel.vue";
import { productAssistantIntent, productQuestion } from "../productAssistant";
import { retryQuestion } from "../assistantRetry";
import { createAssistantRequestGate } from "../assistantRequestGate";
import { readAssistantEvents } from "../assistantStream";
import { conversationTitle } from "../assistantPresentation";
let streamController = null;
function closeStream() { streamController?.abort(); streamController = null; }
const requestGate = createAssistantRequestGate(), writing = ref(false);
function beginWrite() {
  const ticket = requestGate.begin();
  if (ticket !== null) { writing.value = true; clearTimeout(timer); closeStream(); }
  return ticket;
}
function endWrite(ticket) { requestGate.end(ticket); writing.value = requestGate.writing; }
const panel = ref(), contextProduct = ref(null);
let productConversation = false;
function clearProduct() { contextProduct.value = null; productConversation = false; }
function selectSession(id) {
  if (writing.value || sending || uncertain.value) return;
  detachConversation();
  clearProduct(); load(id);
}
watch(productAssistantIntent, async (intent) => {
  if (!intent || intent.owner !== owner || !valid()) return;
  productAssistantIntent.value = null;
  await nextTick();
  if (!valid()) return;
  panel.value?.open(false);
  if (pending || sending || writing.value || uncertain.value) {
    notice.value = "请先完成当前回复或刷新记录，再从商品页发起咨询。";
    return;
  }
  contextProduct.value = intent.product;
  productConversation = true;
}, { flush: "post" });
const router = useRouter();
const sessions = ref([]),
  activeId = ref(""),
  busy = ref(false),
  notice = ref(""),
  uncertain = ref(false);
const owner = String(auth.buyer.user?.id);
let alive = true,
  sending = false,
  stopRequested = false,
  timer,
  pending;
const valid = () => alive && String(auth.buyer.user?.id) === owner;
const request = (path, options = {}) =>
  api("/ai/assistant/sessions" + path, {
    scope: "buyer",
    ...options,
    retry: !options.method || options.method === "GET",
  });
function messages(turns) {
  return turns.flatMap((t) => [
    { id: t.id + "-u", role: "user", text: t.text },
    ...(t.status === "RUNNING"
      ? []
      : [
          {
            id: t.id,
            role: "assistant",
            text:
              t.reply ||
              (t.status === "CANCELED"
                ? "已停止回复。已完成的清单操作可在采购清单中查看。"
                : "本次回复未完成，请重试。"),
            products: t.result?.tools?.includes("search_products")
              ? t.result.products || []
              : [],
            canRetry:
              t.status === "DONE" &&
              ["SHOPPING", "KNOWLEDGE"].includes(t.result?.agent),
            evidence: t.result?.evidence || [],
            retrievalFallback: t.result?.retrievals?.some((r) => r.semantic === false) || false,
            checkout: t.result?.checkout || t.result?.cartChanged,
            ordersShown: t.result?.ordersShown,
            requirementLink: t.status === "DONE" && t.result?.requirementDraft
              ? "/requirements/from-assistant/" + encodeURIComponent(t.sessionId) + "/" + encodeURIComponent(t.id)
              : null,
            stopped: t.status === "CANCELED",
          },
        ]),
  ]);
}
async function load(id = activeId.value) {
  const version = requestGate.read();
  if (version === null) { notice.value = "请求正在提交，请稍候；不会重复发送。"; return; }
  clearTimeout(timer);
  closeStream();
  busy.value = true;
  try {
    const list = await request("");
    if (!valid() || !requestGate.current(version)) return;
    const previous = sessions.value;
    sessions.value = list.map((s) => ({
      ...s,
      updatedAt: Date.parse(s.updatedAt),
      messages: previous.find((old) => old.id === s.id)?.messages || [],
    }));
    const selected =
      sessions.value.find((s) => s.id === id);
    activeId.value = selected?.id || "";
    if (!selected) {
      busy.value = false;
      uncertain.value = false;
      notice.value = "";
      pending = null;
      return;
    }
    const turns = await request("/" + selected.id + "/turns");
    if (!valid() || !requestGate.current(version) || activeId.value !== selected.id)
      return;
    selected.messages = messages(turns);
    pending = turns.find((t) => t.status === "RUNNING");
    busy.value = !!pending;
    uncertain.value = false;
    notice.value = pending && stopRequested ? "上次停止尚未确认，可再次点击停止。" : "";
    if (!pending) stopRequested = false;
    if (busy.value) followStream({ ...pending });
  } catch (e) {
    if (valid() && requestGate.current(version)) {
      notice.value = e.message + "；可点击刷新记录重新核对。";
      uncertain.value = true;
      busy.value = false;
    }
  }
}
async function followStream(target) {
  closeStream();
  const controller = new AbortController(); streamController = controller;
  const timeout = setTimeout(() => controller.abort(), 240000);
  let terminal = false;
  const current = () => valid() && streamController === controller && activeId.value === target.sessionId && !writing.value;
  let checking = false;
  const statusCheck = setInterval(async () => {
    if (!current() || checking) return;
    checking = true;
    try {
      const saved = await request("/" + target.sessionId + "/turns/" + target.id);
      if (current() && saved.status !== "RUNNING") { terminal = true; controller.abort(); }
    } catch { /* The stream timeout still provides a bounded recovery path. */ }
    finally { checking = false; }
  }, 5000);
  try {
    const response = await fetch(`/api/ai/assistant/sessions/${encodeURIComponent(target.sessionId)}/turns/${encodeURIComponent(target.id)}/events`, {
      headers: { Accept: "text/event-stream", authorization: auth.buyer.token },
      credentials: "same-origin", signal: controller.signal,
    });
    if (!response.ok || !response.headers.get("content-type")?.includes("text/event-stream")) throw new Error("流式连接未建立");
    await readAssistantEvents(response.body, (frame) => {
      if (!current()) return;
      if (frame.status !== "RUNNING") { terminal = true; return; }
      const selected = sessions.value.find((s) => s.id === target.sessionId);
      if (!selected) return;
      const remaining = selected.messages.filter((m) => m.id !== target.id);
      selected.messages = frame.text
        ? [...remaining, { id: target.id, role: "assistant", text: frame.text, streaming: true, canRetry: false }]
        : remaining;
    });
  } catch {
    if (current()) notice.value = "增量连接中断，正在读取已保存状态；不会重复发送消息。";
  } finally {
    clearTimeout(timeout);
    clearInterval(statusCheck);
    if (current()) {
      streamController = null;
      // Read the durable result, including cancellation/failure, instead of accepting a partial answer as final.
      timer = setTimeout(() => load(target.sessionId), terminal ? 0 : 1200);
    }
  }
}
function detachConversation() {
  requestGate.read();
  clearTimeout(timer);
  closeStream();
  pending = null;
  busy.value = false;
}
function freshConversation() {
  if (sending || writing.value || uncertain.value) return;
  detachConversation();
  clearProduct();
  activeId.value = "";
  notice.value = "";
  stopRequested = false;
}
async function create(title = "新对话", preserveProduct = false) {
  if (busy.value || uncertain.value || writing.value) return;
  const ticket = beginWrite(); if (ticket === null) return;
  if (!preserveProduct) clearProduct();
  busy.value = true;
  const id = crypto.randomUUID();
  try {
    await request("", { method: "POST", body: { id, title } });
    endWrite(ticket);
    if (valid()) await load(id);
  } catch (e) {
    if (valid()) {
      notice.value = e.message;
      uncertain.value = true;
      busy.value = false;
    }
  } finally { endWrite(ticket); }
}
async function send(text) {
  if (busy.value || uncertain.value || writing.value) return;
  try { text = productQuestion(contextProduct.value, text); }
  catch (e) { notice.value = e.message; return; }
  if (productConversation) {
    await create(("关于 " + contextProduct.value.name).slice(0, 80), true);
    if (!valid() || uncertain.value) return;
    productConversation = false;
  }
  if (!activeId.value) await create(conversationTitle(text));
  if (!valid() || !activeId.value || uncertain.value) return;
  const ticket = beginWrite(); if (ticket === null) return;
  const session = activeId.value,
    id = crypto.randomUUID();
  busy.value = true;
  notice.value = "";
  pending = { id, sessionId: session };
  sending = true;
  stopRequested = false;
  try {
    await request("/" + session + "/turns", {
      method: "POST",
      body: { id, text },
    });
    if (valid()) clearProduct();
    sending = false;
    if (valid() && stopRequested)
      await request("/" + session + "/turns/" + id + "/cancel", {
        method: "POST",
      });
    endWrite(ticket);
    if (valid()) await load(session);
  } catch (e) {
    sending = false;
    if (valid()) {
      notice.value = e.message + "；先刷新记录确认发送结果，不会自动重发。";
      uncertain.value = true;
      busy.value = false;
    }
  } finally { endWrite(ticket); }
}
async function stop() {
  if (!pending) return;
  if (sending) {
    stopRequested = true;
    notice.value = "正在停止回复";
    return;
  }
  if (writing.value) return;
  const target = { ...pending }, ticket = beginWrite();
  if (ticket === null) return;
  stopRequested = true;
  try {
    await request(
      "/" + target.sessionId + "/turns/" + target.id + "/cancel",
      { method: "POST" },
    );
    stopRequested = false;
    endWrite(ticket);
    if (valid()) await load();
  } catch (e) {
    if (valid()) { notice.value = e.message + "；停止结果未确认，请刷新记录核对。"; uncertain.value = true; busy.value = false; }
  } finally { endWrite(ticket); }
}
async function edit(id, title, archived) {
  const s = sessions.value.find((s) => s.id === id);
  if (!s || busy.value || writing.value || uncertain.value) return;
  const ticket = beginWrite(); if (ticket === null) return;
  try {
    await request("/" + id, {
      method: "PATCH",
      body: {
        expectedRevision: s.revision,
        ...(title === undefined ? {} : { title }),
        ...(archived === undefined ? {} : { archived }),
      },
    });
    endWrite(ticket);
    if (valid()) await load();
  } catch (e) {
    if (valid()) {
      notice.value = e.message;
      endWrite(ticket);
      await load();
    }
  } finally { endWrite(ticket); }
}
function retry(id) {
  if (busy.value || writing.value || uncertain.value || contextProduct.value) return;
  const text = retryQuestion(sessions.value.find((s) => s.id === activeId.value)?.messages, id);
  if (text) send(text);
}
onMounted(() => {
  load();
  // A login return can publish intent before this authenticated component mounts.
  const intent = productAssistantIntent.value;
  if (intent?.owner === owner) {
    contextProduct.value = intent.product; productConversation = true;
    productAssistantIntent.value = null;
    nextTick(() => { if (valid()) panel.value?.open(false); });
  }
});
onBeforeUnmount(() => {
  alive = false;
  closeStream();
  clearTimeout(timer);
  if (productAssistantIntent.value?.owner === owner) productAssistantIntent.value = null;
});
</script>
<template>
  <AssistantPanel
    ref="panel"
    :context-product="contextProduct"
    :blocked="uncertain || writing"
    :sessions="sessions"
    :active-id="activeId"
    :busy="busy || writing"
    :notice="notice"
    :initial-open="false"
    @send="send"
    @create="freshConversation"
    @opened="freshConversation"
    @select="selectSession"
    @clear-context="clearProduct"
    @refresh="load(activeId)"
    @stop="stop"
    @retry="retry"
    @rename="(id, title) => edit(id, title)"
    @remove="(id) => edit(id, undefined, true)"
    @product="(p) => router.push('/products/' + p.id)"
  />
</template>
