<script setup>
import { ref, onMounted, onBeforeUnmount } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api";
import { money } from "../domain";
const id = useRoute().params.id,
  items = ref([]),
  chosen = ref([]),
  compared = ref([]),
  error = ref(""),
  busy = ref(false);
let alive = true;
async function load() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  compared.value = [];
  try {
    const d = await api(`/trade/requirements/${id}/plans`, { scope: "buyer" });
    if (alive) {
      items.value = d.items;
      chosen.value = [];
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function compare() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  compared.value = [];
  try {
    const rows = await Promise.all(
      chosen.value.map((id) => api(`/trade/plans/${id}`, { scope: "buyer" })),
    );
    if (alive) compared.value = rows;
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
onBeforeUnmount(() => (alive = false));
const status = (s) =>
  ({
    READY: "本次检查通过",
    ORDERED: "已建立订单",
    BLOCKED: "需要重新核实",
    STALE: "需求已变化",
    UNAVAILABLE: "暂时无法检查",
  })[s] || "待检查";
</script>
<template>
  <div class="page-container buyer-plans">
    <RouterLink :to="'/requirements/' + id" class="back-link"
      >返回采购需求</RouterLink
    >
    <section class="page-heading">
      <div>
        <p class="eyebrow">YOUR PROCUREMENT OPTIONS</p>
        <h1>查看与比较采购方案</h1>
        <p>仅显示商家已发布的最近50个版本；打开详情查看逐项依据和当前检查。</p>
      </div>
      <el-button :disabled="busy" @click="load">刷新方案</el-button>
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="!items.length && !busy">还没有商家发布的方案。</p>
    <el-checkbox-group
      v-model="chosen"
      :max="3"
      :disabled="busy"
      @change="compared = []"
      ><article v-for="p in items" :key="p.id" class="option-card">
        <el-checkbox :value="p.id">加入对比 · 方案 {{ p.planNo }}</el-checkbox>
        <h2>{{ p.summary }}</h2>
        <p>
          ¥ {{ money(p.totalAmount) }} · 需求第 {{ p.requirementRevision }} 版
        </p>
        <p>
          {{
            p.effectiveStatus === "ORDERED"
              ? "已建立订单，请打开详情查看"
              : p.effectiveStatus === "STALE"
                ? "需求已变化，历史版本仅供查阅"
                : "商家已核实，商品现状请打开后检查"
          }}
        </p>
        <RouterLink :to="'/plans/' + p.id">查看方案与依据 →</RouterLink>
      </article></el-checkbox-group
    ><el-button
      v-if="items.length"
      type="primary"
      :disabled="busy || chosen.length < 2"
      @click="compare"
      >比较所选方案（最多3份）</el-button
    >
    <div v-if="compared.length" class="compare-grid">
      <article v-for="p in compared" :key="p.id" class="option-card">
        <h2>方案 {{ p.planNo }}</h2>
        <p>¥ {{ money(p.totalAmount) }}</p>
        <strong>{{ status(p.currentCheck?.status) }}</strong>
        <p>
          检查时间：{{
            p.currentCheck?.checkedAt || "暂不可用"
          }}；本次检查不预占库存。
        </p>
        <ul>
          <li v-for="r in p.currentCheck?.reasons" :key="r">{{ r }}</li>
        </ul>
        <p v-for="i in p.snapshot.items" :key="i.productId">
          {{ i.name }} × {{ i.quantity }}
        </p>
        <p>{{ p.review.note }}</p>
        <RouterLink :to="'/plans/' + p.id">展开逐项核实 →</RouterLink>
      </article>
    </div>
  </div>
</template>
<style scoped>
.buyer-plans {
  padding-bottom: 64px;
}
.option-card {
  font-size: 14px;
  line-height: 1.65;
  padding: 24px;
  background: white;
  border: 1px solid #e4e9e2;
  border-radius: 12px;
  margin: 18px 0;
  overflow-wrap: anywhere;
}
.compare-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 20px;
}
li {
  margin: 8px 0;
}
@media (max-width: 600px) {
  .compare-grid {
    grid-template-columns: 1fr;
  }
  .option-card {
    padding: 18px;
  }
}
</style>
