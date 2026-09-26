<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from "vue";
import { api } from "../api";
import { money } from "../domain";
const props = defineProps({ requirement: Object, disabled: Boolean });
const emit = defineEmits(["created", "busy", "pending"]);
const rows = ref([]),
  error = ref(""),
  busy = ref(false),
  loading = ref(false),
  pending = ref(""),
  saved = ref(null);
const key = `xucai.candidateImport.${props.requirement.id}`;
let alive = true;
try {
  pending.value = sessionStorage.getItem(key) || "";
} catch {
  /* 写入前再检查 */
}
watch(pending, (value) => emit("pending", !!value), {
  immediate: true,
  flush: "sync",
});
async function load() {
  if (loading.value || busy.value) return;
  loading.value = true;
  error.value = "";
  try {
    const data = await api(
      `/ai/admin/requirements/${props.requirement.id}/candidates`,
      { scope: "merchant" },
    );
    if (alive) rows.value = data;
  } catch (e) {
    if (alive) error.value = e.message;
  } finally {
    if (alive) loading.value = false;
  }
}
async function adopt(runId) {
  if (
    busy.value ||
    props.disabled ||
    (pending.value && pending.value !== runId)
  )
    return;
  try {
    sessionStorage.setItem(key, runId);
    pending.value = runId;
  } catch {
    error.value = "浏览器无法保存重试标识，请先启用会话存储；本次未提交。";
    return;
  }
  busy.value = true;
  emit("busy", true);
  error.value = "";
  try {
    const plan = await api(
      `/trade/admin/requirements/${props.requirement.id}/candidate-plans/${runId}`,
      { scope: "merchant", method: "POST" },
    );
    sessionStorage.removeItem(key);
    pending.value = "";
    if (alive) {
      saved.value = plan;
      emit("created", plan);
    }
  } catch (e) {
    if ([400, 403, 404, 409].includes(e.code)) {
      try {
        sessionStorage.removeItem(key);
        pending.value = "";
      } catch {
        /* 保留原操作供重放 */
      }
    }
    if (alive) error.value = e.message;
  } finally {
    if (alive) busy.value = false;
    emit("busy", false);
  }
}
onMounted(load);
onBeforeUnmount(() => {
  alive = false;
});
</script>
<template>
  <section class="surface candidate-import">
    <h2>采用AI候选，生成待核实草稿</h2>
    <p>
      这里是已完成的候选参考。采用时重新核对需求、商品和资料，不调用模型、不发布方案、不预占库存。
    </p>
    <p>用途、必须条件和偏好仍需你核实；同一候选重复提交只返回原草稿。</p>
    <el-button :disabled="busy || loading" @click="load"
      >刷新候选列表</el-button
    >
    <p v-if="error" class="error-text">{{ error }}</p>
    <p v-if="pending">
      保存结果待核对，请用原候选重试。刷新页面后仍会保留此操作。
    </p>
    <el-button
      v-if="pending"
      :loading="busy"
      :disabled="disabled"
      @click="adopt(pending)"
      >重试原候选保存</el-button
    >
    <p v-if="saved">已保存方案 {{ saved.planNo }}，可在下方逐项核实。</p>
    <p v-if="!loading && !rows.length">
      暂无已完成的商品候选，可继续人工编制。
    </p>
    <article v-for="r in rows" :key="r.runId">
      <p>
        需求第 {{ r.requirementRevision }} 版 · 合计 ¥
        {{ money(r.candidate.totalAmount) }} ·
        {{ r.candidate.partialPool ? "候选范围有截取" : "有限商品范围" }}
      </p>
      <ul>
        <li v-for="i in r.candidate.items" :key="i.productId">
          {{ i.name }} × {{ i.quantity }}
        </li>
      </ul>
      <p>所有引用支持关系待核实。</p>
      <el-button
        :disabled="
          disabled ||
          busy ||
          !!pending ||
          requirement.status !== 'OPEN' ||
          String(r.requirementRevision) !== String(requirement.revision)
        "
        @click="adopt(r.runId)"
        >采用为待核实草稿</el-button
      >
    </article>
  </section>
</template>
<style scoped>
.candidate-import {
  margin: 20px 0;
  padding: 24px;
  overflow-wrap: anywhere;
}
.candidate-import article {
  border-top: 1px solid #dce4dc;
  margin-top: 16px;
  padding-top: 12px;
}
.candidate-import p {
  line-height: 1.7;
}
</style>
