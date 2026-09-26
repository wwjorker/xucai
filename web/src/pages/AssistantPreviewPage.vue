<script setup>
import { onBeforeUnmount, ref, watch } from "vue";
import AssistantPanel from "../components/AssistantPanel.vue";
import AssistantMark from "../components/AssistantMark.vue";
import Icon from "../components/Icon.vue";

const key = "xucai-assistant-ui-preview-v1";
const sessions = ref([]),
  activeId = ref(""),
  busy = ref(false),
  notice = ref("");
const panel = ref();
let timer, currentReply;
const uuid = () => crypto.randomUUID();
function create() {
  if (busy.value) return;
  const s = {
    id: uuid(),
    title: "新对话",
    updatedAt: Date.now(),
    messages: [],
  };
  sessions.value.unshift(s);
  activeId.value = s.id;
}
try {
  const data = JSON.parse(localStorage.getItem(key) || "null");
  if (Array.isArray(data?.sessions))
    sessions.value = data.sessions
      .filter(
        (s) =>
          typeof s.id === "string" &&
          typeof s.title === "string" &&
          Number.isFinite(s.updatedAt) &&
          Array.isArray(s.messages),
      )
      .slice(0, 30)
      .map((s) => ({
        ...s,
        messages: s.messages
          .filter(
            (m) =>
              typeof m.id === "string" &&
              ["user", "assistant"].includes(m.role) &&
              typeof m.text === "string",
          )
          .slice(-100)
          .map((m) => ({
            ...m,
            products: Array.isArray(m.products)
              ? m.products.filter(
                  (p) =>
                    typeof p.id === "string" &&
                    typeof p.name === "string" &&
                    Number.isFinite(p.price),
                )
              : undefined,
          })),
      }));
  activeId.value = sessions.value.some((s) => s.id === data?.activeId)
    ? data.activeId
    : sessions.value[0]?.id;
} catch {
  notice.value = "无法读取本地预览记录，本次会话仍可使用";
}
if (!sessions.value.length) create();
watch(
  [sessions, activeId],
  () => {
    try {
      localStorage.setItem(
        key,
        JSON.stringify({
          sessions: sessions.value.slice(0, 30),
          activeId: activeId.value,
        }),
      );
    } catch {
      notice.value = "浏览器未能保存记录，刷新后可能丢失";
    }
  },
  { deep: true },
);
const products = [
  { id: "sample-a", name: "基础有线鼠标 · 样例 A", price: 29 },
  { id: "sample-b", name: "静音有线鼠标 · 样例 B", price: 45 },
];
function answer(text, history) {
  if (text.includes("第二款"))
    return {
      text: history.some((m) => m.products?.length > 1)
        ? "你选的是静音有线鼠标 · 样例 B，4件合计180元。\n\n正式接通后，我会更新采购清单，再带你到订单确认页核对。此处只预览操作表达，没有写入真实清单。"
        : "先看看候选商品，再选第二款。可以告诉我“预算200元，买四个办公鼠标”。",
    };
  if (text.includes("怎么选") || text.includes("区别"))
    return {
      text: "可以先看连接方式和使用场景：\n\n有线：通过线缆连接，适合固定工位。\n蓝牙：桌面更整洁，但要核对电脑和设备的连接能力。\n\n正式回答会检索具体型号资料，并附上来源。本条是固定交互示例，不是已检索的商品结论。",
    };
  if (text.includes("有线"))
    return {
      text: "按“4件、总预算200元、有线办公”展示两款示例。你可以问它们的区别，也可以说“第二款要四个”。",
      products,
    };
  if (text.includes("鼠标"))
    return {
      text: "给办公室买4个鼠标，总预算200元。\n\n你倾向有线还是无线？如果用于固定工位，也可以先看看有线款。",
    };
  if (text.includes("订单"))
    return {
      text: "这里是交互预览，还没有连接你的订单。\n\n正式功能会在登录后查询你自己的采购记录，并显示订单状态卡片，不会凭空生成订单信息。",
    };
  return {
    text: "这页用于体验窗口、历史记录和消息卡片，还没有接入真实模型。\n\n可以试试“预算200元，买四个办公鼠标”，再回答“有线就行”，走一遍示例对话。",
  };
}
function play(session, response) {
  busy.value = true;
  const reply = { id: uuid(), role: "assistant", text: "", stopped: false };
  session.messages.push(reply);
  currentReply = session.messages.at(-1);
  let position = 0;
  timer = window.setInterval(() => {
    position += 4;
    currentReply.text = response.text.slice(0, position);
    if (position >= response.text.length) {
      clearInterval(timer);
      if (response.products) currentReply.products = response.products;
      busy.value = false;
      currentReply = null;
    }
  }, 24);
}
function send(text) {
  if (busy.value) return;
  const s = sessions.value.find((s) => s.id === activeId.value);
  if (!s) return;
  const response = answer(text, s.messages);
  s.messages.push({ id: uuid(), role: "user", text });
  if (s.title === "新对话") s.title = text.slice(0, 22);
  s.updatedAt = Date.now();
  sessions.value.sort((a, b) => b.updatedAt - a.updatedAt);
  play(s, response);
}
function stop() {
  clearInterval(timer);
  if (currentReply) {
    currentReply.stopped = true;
    if (!currentReply.text) currentReply.text = "回答已停止";
  }
  currentReply = null;
  busy.value = false;
}
function retry() {
  if (busy.value) return;
  const s = sessions.value.find((s) => s.id === activeId.value);
  const q = s.messages.findLast((m) => m.role === "user");
  if (q) play(s, answer(q.text, s.messages));
}
function remove(id) {
  if (busy.value) return;
  sessions.value = sessions.value.filter((s) => s.id !== id);
  if (!sessions.value.length) create();
  else if (activeId.value === id) activeId.value = sessions.value[0].id;
}
function rename(id, title) {
  const s = sessions.value.find((s) => s.id === id);
  if (s) s.title = title;
}
onBeforeUnmount(stop);
</script>
<template>
  <div class="assistant-preview-page">
    <div class="ap-top">
      <RouterLink to="/" class="ap-brand"
        >序采<span>办公设备选购</span></RouterLink
      ><span class="ap-label">新版助手 · 交互预览</span>
    </div>
    <div class="ap-main">
      <div class="ap-eyebrow">为每一个工位，找到合适的设备</div>
      <h1>好设备，<br />也要选得明白。</h1>
      <p class="ap-intro">
        用途、预算、参数，不必一次想清楚。<br />和序采聊一聊，从一件设备开始。
      </p>
      <button class="ap-open" @click="panel?.open()">
        <AssistantMark :size="30" />打开选购助手<Icon name="arrow" :size="17" />
      </button>
      <div class="ap-categories">
        <div><Icon name="mouse" :size="28" /><span>键鼠外设</span></div>
        <div><Icon name="monitor" :size="28" /><span>显示设备</span></div>
        <div><Icon name="printer" :size="28" /><span>打印办公</span></div>
      </div>
      <div class="ap-note">
        <strong>这一版可以实际操作</strong>
        <p>
          拖动标题栏 · 右下角调整大小 · 吸附 / 全屏<br />历史搜索 · 重命名 /
          删除 · 刷新恢复 · 停止 / 复制
        </p>
        <small
          >示例回复与设备卡用于验证交互，不调用模型、不产生订单。语音、附件和真实资料引用尚未接入，不放置无效按钮。</small
        >
      </div>
    </div>
    <AssistantPanel
      ref="panel"
      :sessions="sessions"
      :active-id="activeId"
      :busy="busy"
      :notice="notice"
      preview
      @send="send"
      @stop="stop"
      @create="create"
      @select="activeId = $event"
      @rename="rename"
      @remove="remove"
      @retry="retry"
      @product="notice = '这是示例商品卡，正式商品详情尚未接入'"
    />
  </div>
</template>
<style scoped>
.assistant-preview-page {
  min-height: 100dvh;
  background:
    radial-gradient(ellipse at 80% 50%, #e5eedc 0, transparent 55%), #f7f8f3;
  color: #29493b;
  font-family: Inter, "Microsoft YaHei", sans-serif;
}
.ap-top {
  height: 83px;
  padding: 0 5vw;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e8dc;
  background: #ffffff80;
}
.ap-brand {
  font-size: 25px;
  font-weight: 700;
  color: #29493b;
  display: flex;
  align-items: center;
  gap: 17px;
  text-decoration: none;
}
.ap-brand span {
  font-size: 12px;
  font-weight: 400;
  color: #8a9582;
  border-left: 1px solid #ced8c4;
  padding-left: 17px;
}
.ap-label {
  font-size: 11px;
  color: #8a977f;
}
.ap-main {
  padding: 68px 6vw 35px;
  max-width: 780px;
}
.ap-eyebrow {
  font-size: 12px;
  color: #8b977b;
  letter-spacing: 2px;
}
.ap-main h1 {
  font-family: inherit;
  font-size: clamp(34px, 4vw, 57px);
  line-height: 1.3;
  font-weight: 650;
  letter-spacing: -2px;
  margin: 22px 0;
}
.ap-intro {
  font-size: 14px;
  line-height: 1.9;
  color: #8a957d;
  margin: 0 0 25px;
}
.ap-open {
  border: 0;
  border-radius: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px 10px 8px;
  font: inherit;
  font-size: 13px;
  background: #e2ebd7;
  color: #31543b;
  cursor: pointer;
}
.ap-categories {
  display: flex;
  gap: 13px;
  margin-top: 40px;
}
.ap-categories > div {
  border: 1px solid #e0e6d8;
  background: #ffffff85;
  border-radius: 15px;
  width: 120px;
  height: 100px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #79906c;
}
.ap-categories span {
  font-size: 12px;
  color: #7f8e73;
}
.ap-note {
  margin-top: 35px;
  border-top: 1px solid #e2e7d8;
  padding-top: 22px;
  max-width: 385px;
  font-size: 12px;
  color: #899780;
  line-height: 1.9;
}
.ap-note strong {
  font-weight: 500;
  color: #5d7454;
}
.ap-note p {
  margin: 6px 0 10px;
}
.ap-note small {
  font-size: 10px;
  line-height: 1.7;
  display: block;
  color: #9aa58f;
}
@media (max-width: 680px) {
  .ap-top {
    padding: 0 20px;
  }
  .ap-label {
    display: none;
  }
  .ap-main {
    padding: 40px 24px;
  }
  .ap-categories > div {
    width: 96px;
  }
  .ap-brand {
    font-size: 22px;
  }
}
</style>
