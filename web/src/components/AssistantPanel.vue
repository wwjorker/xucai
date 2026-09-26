<script setup>
import AssistantProductArt from "./AssistantProductArt.vue";
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import Icon from "./Icon.vue";
import AssistantMark from "./AssistantMark.vue";
import AssistantText from "./AssistantText.vue";
import { fitWindow } from "./assistantWindow";
import { replyWithoutCardInventory, historyTitle } from "../assistantPresentation";

// The host owns messages and persistence. This component never calls a model or a business API.
const props = defineProps({
  sessions: { type: Array, default: () => [] },
  activeId: String,
  busy: Boolean,
  preview: Boolean,
  notice: String,
  blocked: Boolean,
  contextProduct: Object,
  initialOpen: { type: Boolean, default: true },
});
const emit = defineEmits([
  "send",
  "opened",
  "stop",
  "create",
  "select",
  "rename",
  "remove",
  "retry",
  "product",
  "refresh",
  "clear-context",
]);
const opened = ref(props.initialOpen),
  mode = ref("float"),
  historyOpen = ref(false);
const input = ref(""),
  search = ref(""),
  renameId = ref(""),
  renameText = ref(""),
  deleteId = ref("");
const panel = ref(),
  messageList = ref(),
  composer = ref(),
  launcher = ref();
const status = ref(""),
  dragging = ref(false),
  resizing = ref(false);
const viewport = ref({ width: 1440, height: 900 });
const rect = ref({ x: 700, y: 100, width: 530, height: 680 });
const narrow = computed(() => viewport.value.width < 680);
const active = computed(() =>
  props.sessions.find((s) => s.id === props.activeId),
);
const messages = computed(() => active.value?.messages || []);
const filtered = computed(() =>
  props.sessions.filter((s) =>
    s.title.toLowerCase().includes(search.value.toLowerCase()),
  ),
);
const style = computed(() => {
  if (narrow.value)
    return { inset: "0", width: "100%", height: "100dvh", borderRadius: "0" };
  if (mode.value === "full")
    return { inset: "18px", width: "auto", height: "auto" };
  if (mode.value === "dock")
    return {
      top: "12px",
      right: "12px",
      bottom: "12px",
      width: "min(570px, calc(100vw - 24px))",
      height: "auto",
    };
  return {
    left: rect.value.x + "px",
    top: rect.value.y + "px",
    width: rect.value.width + "px",
    height: rect.value.height + "px",
  };
});
function setViewport() {
  viewport.value = { width: window.innerWidth, height: window.innerHeight };
  rect.value = fitWindow(rect.value, viewport.value);
}
function startMove(e, kind) {
  if (
    narrow.value ||
    mode.value !== "float" ||
    e.button !== 0 ||
    e.target.closest("button,input")
  )
    return;
  e.preventDefault();
  e.currentTarget.setPointerCapture(e.pointerId);
  const initial = { ...rect.value },
    x = e.clientX,
    y = e.clientY;
  dragging.value = kind === "move";
  resizing.value = kind === "size";
  const target = e.currentTarget;
  const move = (event) => {
    const dx = event.clientX - x,
      dy = event.clientY - y;
    rect.value = fitWindow(
      kind === "move"
        ? { ...initial, x: initial.x + dx, y: initial.y + dy }
        : {
            ...initial,
            width: Math.min(
              initial.width + dx,
              viewport.value.width - initial.x,
            ),
            height: Math.min(
              initial.height + dy,
              viewport.value.height - initial.y,
            ),
          },
      viewport.value,
    );
  };
  const end = () => {
    dragging.value = false;
    resizing.value = false;
    target.removeEventListener("pointermove", move);
    target.removeEventListener("pointerup", end);
    target.removeEventListener("pointercancel", end);
  };
  target.addEventListener("pointermove", move);
  target.addEventListener("pointerup", end);
  target.addEventListener("pointercancel", end);
}
function moveWithKeyboard(e) {
  if (narrow.value || mode.value !== "float") return;
  const delta = {
    ArrowLeft: [-16, 0],
    ArrowRight: [16, 0],
    ArrowUp: [0, -16],
    ArrowDown: [0, 16],
  }[e.key];
  if (!delta) return;
  e.preventDefault();
  rect.value = fitWindow(
    { ...rect.value, x: rect.value.x + delta[0], y: rect.value.y + delta[1] },
    viewport.value,
  );
}
async function open(reset = true) {
  if (!opened.value) { input.value = ""; historyOpen.value = false; if (reset !== false) emit("opened"); }
  opened.value = true;
  await nextTick();
  composer.value?.focus();
}
async function minimize() {
  opened.value = false;
  await nextTick();
  launcher.value?.focus();
}
function changeMode(next) {
  mode.value = mode.value === next ? "float" : next;
}
function send(value = input.value) {
  const text = value.trim();
  if (!text || props.busy || props.blocked) return;
  emit("send", text);
  input.value = "";
}
function keySend(e) {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
    e.preventDefault();
    send();
  }
}
function select(id) {
  emit("select", id);
  renameId.value = "";
  deleteId.value = "";
  historyOpen.value = false;
}
function startRename(s) {
  renameId.value = s.id;
  renameText.value = s.title;
  deleteId.value = "";
}
function rename() {
  if (renameText.value.trim())
    emit("rename", renameId.value, renameText.value.trim().slice(0, 60));
  renameId.value = "";
}
async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
    status.value = "已复制回答";
  } catch {
    status.value = "无法访问剪贴板，请选中文字复制";
  }
}
function groupLabel(s, index) {
  const date = new Date(s.updatedAt).toLocaleDateString();
  if (
    index &&
    date === new Date(filtered.value[index - 1].updatedAt).toLocaleDateString()
  )
    return "";
  return date === new Date().toLocaleDateString() ? "今天" : date;
}
function escape(e) {
  if (e.key !== "Escape") return;
  if (deleteId.value) deleteId.value = "";
  else if (renameId.value) renameId.value = "";
  else if (historyOpen.value) historyOpen.value = false;
  else minimize();
}
watch(
  () => [props.activeId, messages.value.length, messages.value.at(-1)?.text],
  async (values, previous) => {
    const el = messageList.value;
    const nearEnd =
      !el ||
      values[0] !== previous?.[0] ||
      el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    await nextTick();
    if (messageList.value && nearEnd)
      messageList.value.scrollTop = messageList.value.scrollHeight;
  },
  { immediate: true },
);
onMounted(() => {
  setViewport();
  rect.value = fitWindow(
    {
      x: window.innerWidth - 565,
      y: 85,
      width: 530,
      height: window.innerHeight - 115,
    },
    viewport.value,
  );
  window.addEventListener("resize", setViewport);
});
onBeforeUnmount(() => window.removeEventListener("resize", setViewport));
defineExpose({ open });
</script>

<template>
  <Teleport to="body">
    <button v-if="!opened" ref="launcher" class="xa-launch" @click="open">
      <AssistantMark :size="48" /><span
        >选设备，问序采<small>AI 选购助手</small></span
      ><Icon name="chevron" :size="17" />
    </button>
    <section
      v-else
      ref="panel"
      class="xa-panel"
      :class="{ 'xa-moving': dragging || resizing, 'xa-full': mode === 'full' }"
      :style="style"
      role="dialog"
      aria-label="序采AI选购助手"
      :aria-modal="false"
      @keydown="escape"
    >
      <header class="xa-header" @pointerdown="startMove($event, 'move')">
        <AssistantMark :size="37" />
        <div
          class="xa-title"
          tabindex="0"
          title="拖动标题栏移动窗口，也可聚焦后用方向键移动"
          aria-label="拖动窗口，或使用方向键移动"
          @keydown="moveWithKeyboard"
        >
          <strong>序采助手</strong><small>找设备 · 比参数 · 管采购</small>
        </div>
        <div class="xa-window-actions">
          <button
            title="历史会话"
            aria-label="历史会话"
            :aria-expanded="historyOpen"
            @click="historyOpen = !historyOpen"
          >
            <Icon name="history" :size="18" />
          </button>
          <button
            v-if="!narrow"
            title="右侧吸附 / 恢复浮窗"
            aria-label="右侧吸附"
            :aria-pressed="mode === 'dock'"
            @click="changeMode('dock')"
          >
            <Icon name="dock" :size="18" />
          </button>
          <button
            v-if="!narrow"
            title="全屏 / 恢复浮窗"
            aria-label="切换全屏"
            :aria-pressed="mode === 'full'"
            @click="changeMode('full')"
          >
            <Icon name="expand" :size="18" />
          </button>
          <button
            title="收起；已保存的对话可从历史记录继续"
            aria-label="收起助手"
            @click="minimize"
          >
            <Icon name="minus" :size="19" />
          </button>
        </div>
      </header>
      <div v-if="preview" class="xa-preview">
        <span></span>交互预览 · 示例回复，不调用模型
      </div>
      <div class="xa-workspace">
        <aside v-if="historyOpen" class="xa-history" aria-label="历史会话列表">
          <div class="xa-history-top">
            <strong>历史会话</strong
            ><button aria-label="关闭历史列表" @click="historyOpen = false">
              <Icon name="close" :size="17" />
            </button>
          </div>
          <button
            class="xa-new"
            :disabled="blocked"
            @click="
              emit('create');
              historyOpen = false;
            "
          >
            <Icon name="plus" :size="17" /> 开始新对话
          </button>
          <label class="xa-search"
            ><Icon name="search" :size="15" /><input
              v-model="search"
              placeholder="搜索会话"
              aria-label="搜索历史会话"
          /></label>
          <div class="xa-session-list">
            <template v-for="(session, index) in filtered" :key="session.id">
              <small v-if="groupLabel(session, index)" class="xa-date">{{
                groupLabel(session, index)
              }}</small>
              <div
                class="xa-session"
                :class="{ active: session.id === activeId }"
              >
                <form
                  v-if="renameId === session.id"
                  class="xa-rename"
                  @submit.prevent="rename"
                >
                  <input
                    v-model="renameText"
                    aria-label="会话新名称"
                    maxlength="60"
                  /><button aria-label="保存名称">
                    <Icon name="check" :size="15" />
                  </button>
                </form>
                <template v-else
                  ><button
                    class="xa-session-name"
                    :disabled="blocked"
                    @click="select(session.id)"
                  >
                    <Icon name="chat" :size="15" /><span>{{
                      historyTitle(session)
                    }}</span>
                  </button>
                  <div class="xa-session-actions">
                    <button
                      title="重命名"
                      :aria-label="'重命名：' + session.title"
                      :disabled="busy"
                      @click="startRename(session)"
                    >
                      <Icon name="edit" :size="14" /></button
                    ><button
                      title="删除会话"
                      :aria-label="'删除：' + session.title"
                      :disabled="busy"
                      @click="deleteId = session.id"
                    >
                      <Icon name="trash" :size="14" />
                    </button></div
                ></template>
                <div v-if="deleteId === session.id" class="xa-delete">
                  <p>删除这段会话？采购清单不受影响。</p>
                  <button @click="deleteId = ''">保留</button
                  ><button
                    class="xa-danger"
                    @click="
                      emit('remove', session.id);
                      deleteId = '';
                    "
                  >
                    删除
                  </button>
                </div>
              </div>
            </template>
            <p v-if="!filtered.length" class="xa-empty-small">没有找到会话</p>
          </div>
          <small class="xa-storage">{{
            preview ? "预览记录仅保存在此浏览器" : "当前账户的会话记录"
          }}</small>
        </aside>
        <div class="xa-conversation">
          <div class="xa-thread-heading">
            <span>{{ active?.title || "新对话" }}</span
            ><button :disabled="blocked" @click="emit('create')">
              <Icon name="plus" :size="14" />新对话
            </button>
          </div>
          <div ref="messageList" class="xa-messages" :aria-busy="busy">
            <div v-if="!messages.length" class="xa-welcome">
              <div class="xa-welcome-art">
                <span class="xa-orbit xa-orbit-one"></span
                ><span class="xa-orbit xa-orbit-two"></span
                ><AssistantMark :size="78" /><span class="xa-spark">✦</span>
              </div>
              <h2>今天，想选点什么？</h2>
              <p>告诉我用途和预算，<br />一起找到合适的办公设备。</p>
              <div class="xa-starters">
                <button @click="send('预算200元，买四个办公鼠标')">
                  <span class="xa-starter-icon"><Icon name="mouse" /></span
                  ><span
                    ><strong>帮我挑设备</strong
                    ><small>预算200元，买四个办公鼠标</small></span
                  ><Icon name="chevron" :size="16" /></button
                ><button @click="send('有线和蓝牙鼠标怎么选？')">
                  <span class="xa-starter-icon warm"
                    ><Icon name="document" /></span
                  ><span
                    ><strong>看懂参数再选</strong
                    ><small>有线和蓝牙鼠标怎么选？</small></span
                  ><Icon name="chevron" :size="16" /></button
                ><button @click="send('我想查询采购订单')">
                  <span class="xa-starter-icon blue"><Icon name="box" /></span
                  ><span
                    ><strong>查看我的采购</strong
                    ><small>查询订单和采购进度</small></span
                  ><Icon name="chevron" :size="16" />
                </button>
              </div>
            </div>
            <article
              v-for="message in messages"
              :key="message.id"
              class="xa-message"
              :class="message.role"
            >
              <AssistantMark v-if="message.role === 'assistant'" :size="28" />
              <div class="xa-message-content">
                <div class="xa-message-text">
                  <AssistantText :text="replyWithoutCardInventory(message.text, message.products)" />
                </div>
                <div v-if="message.products?.length" class="xa-products">
                  <button
                    v-for="(p, index) in message.products"
                    :key="p.id"
                    class="xa-product"
                    @click="emit('product', p)"
                  >
                    <span class="xa-product-art"><AssistantProductArt :product="p" :preview="preview" /></span
                    ><span
                      ><small
                        >选项 {{ index + 1
                        }}{{ preview ? " · 示例设备" : "" }}</small
                      ><strong>{{ p.name }}</strong
                      ><span class="xa-product-price"
                        >{{ p.price == null ? "价格待确认" : "¥" + p.price }}
                        <small v-if="p.price != null">/ 件</small></span
                      ></span
                    ><Icon name="chevron" :size="16" />
                  </button>
                </div>
                <p v-if="message.retrievalFallback">本次部分资料按关键词检索，可能遗漏相关内容；未找到不代表商品不支持。</p>
                <div v-if="message.evidence?.length" class="xa-message-sources">
                  <details>
                    <summary>查看参考资料</summary>
                    <p v-for="source in message.evidence" :key="source.chunkId">
                      <strong>{{ source.docTitle }}</strong
                      ><br />{{ source.text }}
                    </p>
                  </details>
                </div>
                <p v-if="message.checkout">
                  <RouterLink class="xa-business-action" to="/cart" @click="minimize">查看清单 · 核对并下单 <span aria-hidden="true">→</span></RouterLink>
                </p>
                <p v-if="message.ordersShown">
                  <RouterLink class="xa-business-action" to="/purchase-orders" @click="minimize">查看我的订单 →</RouterLink>
                </p>
                <p v-if="message.requirementLink">
                  <RouterLink :to="message.requirementLink" @click="opened = false">核对采购需求草稿</RouterLink>
                </p>
                <div
                  v-if="message.role === 'assistant' && !busy"
                  class="xa-message-actions"
                >
                  <button
                    title="复制回答"
                    aria-label="复制回答"
                    @click="copy(message.text)"
                  >
                    <Icon name="copy" :size="14" /></button
                  ><button
                    v-if="
                      message === messages.at(-1) &&
                      (preview || message.canRetry)
                    "
                    title="重新生成"
                    aria-label="重新生成"
                    @click="emit('retry', message.id)"
                  >
                    <Icon name="refresh" :size="14" /></button
                  ><span v-if="message.stopped">已停止</span>
                </div>
              </div>
            </article>
            <div v-if="busy" class="xa-thinking" role="status">
              <span></span><span></span><span></span
              ><small>{{ preview ? "正在播放示例回复" : "正在处理" }}</small>
            </div>
          </div>
          <div class="xa-compose">
            <div v-if="contextProduct" class="xa-product-context">
              <span>正在咨询：<strong>{{ contextProduct.name }}</strong><small>发送后新开商品对话</small></span>
              <button type="button" aria-label="取消商品关联" @click="emit('clear-context')">×</button>
            </div>
            <div v-if="messages.length && !busy" class="xa-followups">
              <template v-if="preview"
                ><button @click="send('有线就行')">有线就行</button
                ><button @click="send('第二款要四个')">
                  第二款要四个
                </button></template
              >
              <template v-else
                ><button @click="send('查看我的采购清单')">查看采购清单</button>
                <button @click="send('查看我的最近订单')">
                  查看我的订单
                </button></template
              >
            </div>
            <form class="xa-input-box" @submit.prevent="send()">
              <textarea
                ref="composer"
                v-model="input"
                aria-label="对助手说"
                placeholder="说说你的需求，也可以接着问…"
                rows="2"
                maxlength="2000"
                @keydown="keySend"
              ></textarea>
              <div class="xa-input-bottom">
                <span>Enter 发送 · Shift+Enter 换行</span
                ><button
                  v-if="busy"
                  type="button"
                  class="xa-send"
                  title="停止生成"
                  aria-label="停止生成"
                  @click="emit('stop')"
                >
                  <Icon name="stop" :size="17" /></button
                ><button
                  v-else
                  class="xa-send"
                  :disabled="!input.trim() || blocked"
                  title="发送"
                  aria-label="发送"
                >
                  <Icon name="send" :size="18" />
                </button>
              </div>
            </form>
            <p class="xa-compose-note" role="status">
              <button v-if="!preview" type="button" @click="emit('refresh')">
                刷新记录
              </button>
              {{
                status ||
                notice ||
                (preview
                  ? "预览不产生订单，商品图为示意图"
                  : "商品价格与库存以确认页为准")
              }}
            </p>
          </div>
        </div>
      </div>
      <div
        v-if="!narrow && mode === 'float'"
        class="xa-resize"
        title="拖动调整窗口大小"
        aria-hidden="true"
        @pointerdown="startMove($event, 'size')"
      ></div>
    </section>
  </Teleport>
</template>

<style scoped>
.xa-product-context { display:flex; align-items:flex-start; gap:12px; padding:10px 12px; margin-bottom:10px; border-radius:10px; background:#edf5f1; color:#254c40; font-size:12px; }
.xa-product-context span { flex:1; min-width:0; overflow-wrap:anywhere; }
.xa-product-context small { display:block; margin-top:4px; color:#687e75; }
.xa-product-context button { flex:none; border:0; background:transparent; color:inherit; cursor:pointer; padding:2px 5px; font-size:18px; }
</style>
<style src="./assistantPanel.css"></style>
