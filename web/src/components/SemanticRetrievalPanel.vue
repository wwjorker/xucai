<script setup>
import { computed, onBeforeUnmount, reactive, ref } from "vue";
import { api, query, sessions, authPrompt } from "../api";
import {
  RetrievalSession,
  retrievalLabel,
  locatedHitError,
} from "../retrieval.js";
const props = defineProps({
  productId: { type: [String, Number], required: true },
  selectable: Boolean,
  disabled: Boolean,
});
const emit = defineEmits(["select"]);
const owner = String(sessions.merchant.user?.id || "");
const sameUser = () =>
  !!owner && owner === String(sessions.merchant.user?.id || "");
const state = reactive(
  new RetrievalSession({
    storage: {
      getItem: (k) => sessionStorage.getItem(k),
      setItem: (k, v) => sessionStorage.setItem(k, v),
    },
    owner,
    productId: props.productId,
    request: async (path, options) => {
      if (!sameUser()) throw new Error("账户已变化，请重新进入检索页面。");
      try {
        return await api(path, {
          ...options,
          scope: "merchant",
          retry: options.method === "POST" ? false : undefined,
        });
      } catch (e) {
        if (e.code === 401) {
          authPrompt.expectedUserId = owner;
          authPrompt.scope = "merchant";
        }
        throw e;
      }
    },
  }),
);
const question = ref(
    state.ticket?.kind === "search" ? state.ticket.body.query : "",
  ),
  located = ref(null),
  locating = ref(false),
  locateError = ref("");
const response = computed(() => state.ticket?.response),
  result = computed(() => response.value?.result);
const hits = computed(() =>
  state.ticket?.kind === "search" &&
  response.value?.status === "DONE" &&
  result.value?.semantic === true
    ? result.value.items || []
    : [],
);
const disabled = computed(
  () => props.disabled || !sameUser() || state.locked || locating.value,
);
let alive = true,
  generation = 0;
onBeforeUnmount(() => {
  alive = false;
  ++generation;
});
async function run(kind) {
  if (disabled.value) return;
  ++generation;
  located.value = null;
  locateError.value = "";
  await state.start(kind, question.value);
}
async function inspect(chunk, select = false) {
  if (props.disabled || locating.value || state.busy || !sameUser()) return;
  const g = ++generation;
  locating.value = true;
  locateError.value = "";
  located.value = null;
  try {
    const data = await api(
      `/catalog/products/${props.productId}/doc-chunks/locate?` +
        query({ chunkId: chunk.chunkId }),
      { scope: "merchant" },
    );
    if (!alive || g !== generation || !sameUser() || props.disabled) return;
    const error = locatedHitError(chunk, data, props.productId);
    if (error) throw new Error(error);
    located.value = data;
    if (select) emit("select", chunk);
  } catch (e) {
    if (alive && g === generation) locateError.value = e.message;
  } finally {
    if (alive && g === generation) locating.value = false;
  }
}
</script>

<template>
  <section class="semantic-panel" aria-label="商品资料语义检索">
    <h3>用一个问题，查找相关原文</h3>
    <p class="muted">
      只检索当前商品的已发布资料。相似度用于排序，是否支持采购结论仍需人工核实。
    </p>
    <div class="semantic-build">
      <el-button :disabled="disabled" @click="run('build')"
        >建立或更新本商品索引</el-button
      >
      <small
        >资料未变化且索引完整时直接复用；需要新向量时会调用模型并计入项目限额。</small
      >
    </div>
    <form @submit.prevent="run('search')" class="semantic-form">
      <el-input
        v-model="question"
        type="textarea"
        :rows="2"
        maxlength="1000"
        :disabled="state.pending || state.busy || props.disabled"
        placeholder="例如：这个USB-C接口可以传输视频吗？请仅输入公开资料或演示问题。"
        aria-label="语义检索问题"
      />
      <el-button
        type="primary"
        native-type="submit"
        :disabled="disabled || !question.trim()"
        >检索相关原文</el-button
      >
    </form>
    <p class="muted">
      每次新检索会调用向量模型；不会自动创建方案、标记满足条件或发布。
    </p>
    <el-alert
      v-if="state.error"
      :title="state.error"
      type="error"
      :closable="false"
    />
    <div v-if="state.ticket" class="semantic-operation">
      <strong>{{
        retrievalLabel(
          response?.status === "DONE" ? result?.status : response?.status,
        )
      }}</strong>
      <p v-if="state.pending">
        原操作尚未确认。请先查回；若服务未收到，可重发同一编号。新收费操作暂时锁定。
      </p>
      <p v-else>
        这是上次操作的历史结果，不代表当前资料仍未变化；查看或采用时会重新核对原文。
      </p>
      <details>
        <summary>操作记录</summary>
        <code>{{ state.ticket.body.requestId }}</code>
        <p>{{ response?.finished_at || "尚未收到终态" }}</p>
      </details>
      <div class="semantic-actions">
        <el-button
          :disabled="state.busy || !sameUser()"
          @click="state.recover()"
          >查回原操作</el-button
        >
        <el-button
          v-if="state.pending"
          :disabled="state.busy || state.storageBlocked || !sameUser()"
          @click="state.send()"
          >重发原请求</el-button
        >
      </div>
      <p v-if="response?.error_code === 'EMBEDDING_LIMIT'">
        本批额度已用完，请联系维护者核对预算；原商品与方案仍可正常使用。
      </p>
      <p v-else-if="response?.error_code">
        未自动重试，错误代码：{{ response.error_code }}
      </p>
      <p v-if="result?.notice">{{ result.notice }}</p>
      <p v-if="result?.status === 'READY'">
        本次记录包含 {{ result.chunkCount }} 个片段{{
          result.reused ? "，已复用现有索引" : ""
        }}。
      </p>
      <p v-if="state.ticket.kind === 'search'">
        本次问题：{{ state.ticket.body.query }}
      </p>
    </div>
    <ol class="semantic-hits">
      <li v-for="(hit, index) in hits" :key="hit.chunk.chunkId">
        <strong>相关排序 {{ index + 1 }} · 待人工核实</strong>
        <p class="semantic-text">{{ hit.chunk.text }}</p>
        <small
          >{{ hit.chunk.docTitle }} · 资料第 {{ hit.chunk.version }} 版</small
        >
        <div class="semantic-actions">
          <el-button
            :disabled="locating || state.busy || props.disabled"
            @click="inspect(hit.chunk)"
            >核对当前原文</el-button
          >
          <el-button
            v-if="selectable"
            type="primary"
            :disabled="
              locating ||
              state.busy ||
              props.disabled ||
              hit.chunk.text.length > 1000
            "
            @click="inspect(hit.chunk, true)"
            >核对并采用此片段</el-button
          >
        </div>
      </li>
    </ol>
    <el-alert
      v-if="locateError"
      :title="locateError"
      type="warning"
      :closable="false"
    />
    <details v-if="located" open class="semantic-original">
      <summary>已核对当前原文 · 资料第 {{ located.version }} 版</summary>
      <pre>{{ located.content }}</pre>
    </details>
  </section>
</template>

<style scoped>
.semantic-panel {
  min-width: 0;
}
.semantic-panel h3 {
  margin: 0 0 12px;
}
.semantic-build,
.semantic-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 12px 0;
}
.semantic-build small {
  flex: 1;
  min-width: 180px;
  color: var(--muted);
}
.semantic-form {
  display: grid;
  gap: 12px;
}
.semantic-form > .el-button {
  justify-self: start;
}
.semantic-operation {
  margin: 16px 0;
  padding: 16px;
  background: #f3f6f6;
  border-radius: 12px;
  overflow-wrap: anywhere;
}
.semantic-operation p {
  line-height: 1.7;
}
.semantic-operation code {
  display: block;
  margin-top: 10px;
}
.semantic-hits {
  list-style: none;
  padding: 0;
  display: grid;
  gap: 14px;
}
.semantic-hits li {
  border: 1px solid #dce5e4;
  border-radius: 12px;
  padding: 16px;
  min-width: 0;
}
.semantic-text,
.semantic-original pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  line-height: 1.8;
}
.semantic-hits small {
  color: var(--muted);
}
.semantic-original {
  border-top: 1px solid #dce5e4;
  padding-top: 16px;
}
@media (max-width: 600px) {
  .semantic-actions {
    align-items: stretch;
    flex-direction: column;
  }
  .semantic-actions .el-button {
    margin-left: 0;
  }
  .semantic-build .el-button {
    width: 100%;
  }
  .semantic-build small {
    min-width: 0;
  }
}
</style>
