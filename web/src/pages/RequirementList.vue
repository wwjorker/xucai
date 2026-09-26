<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, query } from "../api";
import { money, categoryOf } from "../domain";
import Icon from "../components/Icon.vue";
const route = useRoute(),
  router = useRouter(),
  merchant = computed(() => route.meta.merchant);
const scope = computed(() => (merchant.value ? "merchant" : "buyer")),
  prefix = computed(() =>
    merchant.value ? "/merchant/requirements" : "/requirements",
  );
const items = ref([]),
  total = ref(0),
  page = ref(1),
  busy = ref(false),
  error = ref("");
let generation = 0;
async function load() {
  const g = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const data = await api(
      "/trade/" +
        (merchant.value ? "admin/" : "") +
        "requirements?" +
        query({ page: page.value, size: 10 }),
      { scope: scope.value },
    );
    if (g === generation) {
      items.value = data.items;
      total.value = Number(data.total);
    }
  } catch (e) {
    if (g === generation) error.value = e.message;
  } finally {
    if (g === generation) busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-container requirements-page">
    <div class="merchant-breadcrumb">
      {{ merchant ? "商家工作台" : "采购客户" }} <span>/</span>
      {{ merchant ? "客户需求" : "我的采购需求" }}
    </div>
    <section class="page-heading">
      <div>
        <div class="eyebrow">
          {{ merchant ? "CUSTOMER REQUIREMENTS" : "PROCUREMENT BRIEF" }}
        </div>
        <h1>
          {{ merchant ? "先理解需求，再提供方案。" : "把采购需求，说清楚。" }}
        </h1>
        <p class="muted">
          {{
            merchant
              ? "查看客户需求，编制设备组合、核实依据并发布待确认方案。"
              : "记录预算、数量和使用场景，逐步补齐设备选购条件。"
          }}
        </p>
      </div>
      <el-button
        v-if="!merchant"
        type="primary"
        size="large"
        @click="router.push('/requirements/new')"
        ><Icon name="plus" :size="18" />新建采购需求</el-button
      >
    </section>
    <section class="requirement-intro">
      <Icon name="document" :size="26" />
      <div>
        <strong>{{
          merchant
            ? "需求更新后，请以最新版本为准"
            : "先保存需求，条件可以逐步补充"
        }}</strong>
        <p>
          预算和数量不确定时可留空。保存后可在需求详情查看AI整理是否可用；设备方案仍由商家编制与核实。
        </p>
      </div>
    </section>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    /><el-skeleton v-if="busy" animated :rows="8" />
    <div v-else-if="!items.length" class="empty-state">
      <Icon name="document" :size="44" />
      <h2>{{ error ? "暂时无法加载需求" : "还没有采购需求" }}</h2>
      <p>
        {{
          merchant
            ? "客户保存需求后，将显示在这里。"
            : "例如，为远程办公团队采购鼠标、键盘和耳机。"
        }}
      </p>
      <el-button v-if="error" @click="load">重新加载</el-button
      ><el-button
        v-else-if="!merchant"
        type="primary"
        @click="router.push('/requirements/new')"
        >写下第一份需求</el-button
      >
    </div>
    <div v-else class="requirement-list">
      <RouterLink
        v-for="item in items"
        :key="item.id"
        :to="prefix + '/' + item.id"
        class="requirement-card"
        ><div class="requirement-card-top">
          <span class="eyebrow"
            >需求 {{ item.id }} · 第 {{ item.revision }} 版</span
          ><span
            :class="[
              'status-pill',
              item.status === 'OPEN' ? 'published' : 'hidden',
            ]"
            >{{
              item.status === "OPEN"
                ? "进行中"
                : item.status === "CLOSED"
                  ? "已关闭"
                  : "已下单"
            }}</span
          >
        </div>
        <h2>{{ item.title }}</h2>
        <p>{{ item.rawText }}</p>
        <div class="requirement-tags">
          <span
            >预算
            {{
              item.structured.budgetCents
                ? "¥ " + money(item.structured.budgetCents)
                : "待确认"
            }}</span
          ><span>{{
            item.structured.quantity
              ? item.structured.quantity + " 件设备"
              : "数量待确认"
          }}</span
          ><span v-for="id in item.structured.categoryIds" :key="id">{{
            categoryOf(id).name
          }}</span>
        </div>
        <div class="requirement-card-foot">
          <span>更新于 {{ item.updatedAt.replace("T", " ").slice(0, 16) }}</span
          ><span
            >{{ merchant ? "查看需求" : "查看与补充" }}
            <Icon name="arrow" :size="16"
          /></span></div
      ></RouterLink>
    </div>
    <el-pagination
      v-if="total > 10"
      class="pagination"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="10"
      v-model:current-page="page"
      @current-change="load"
    />
  </div>
</template>
