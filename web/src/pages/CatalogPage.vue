<script setup>
import { ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, query } from "../api";
import {
  categories,
  categoryOf,
  money,
  specLabels,
  specValue,
} from "../domain";
import Icon from "../components/Icon.vue";
import ProductVisual from "../components/ProductVisual.vue";
const route = useRoute(),
  router = useRouter();
const keyword = ref(String(route.query.keyword || "")),
  items = ref([]),
  total = ref(0),
  busy = ref(false),
  error = ref("");
let loadId = 0;
async function load() {
  const id = ++loadId;
  busy.value = true;
  error.value = "";
  try {
    const data = await api(
      "/catalog/products?" +
        query({
          page: route.query.page || 1,
          size: 9,
          categoryId: route.query.category,
          keyword: route.query.keyword,
        }),
    );
    if (id === loadId) {
      items.value = data.items;
      total.value = Number(data.total);
    }
  } catch (e) {
    if (id === loadId) {
      error.value = e.message;
      items.value = [];
    }
  } finally {
    if (id === loadId) busy.value = false;
  }
}
function filter(category) {
  router.push({
    path: "/",
    query: { ...route.query, category: category || undefined, page: undefined },
  });
}
function search() {
  router.push({
    path: "/",
    query: {
      ...route.query,
      keyword: keyword.value.trim() || undefined,
      page: undefined,
    },
  });
}
watch(
  () => route.query,
  () => {
    keyword.value = String(route.query.keyword || "");
    load();
  },
  { immediate: true },
);
</script>
<template>
  <div class="page-container catalog-page">
    <section class="catalog-intro">
      <div>
        <div class="eyebrow">办公设备选购</div>
        <h1>为每一个工位，<br />选好装备<span class="green-dot">。</span></h1>
        <p>
          从日常输入到远程协作，找到适合工作的办公设备。<br
            class="desktop-only"
          />查看规格与来源，让每一次选择都有依据。
        </p>
        <div class="procurement-entry">
          <RouterLink to="/requirements/new" class="procurement-start"
            >复杂方案，请商家协助 →</RouterLink
          >
          <RouterLink to="/guide">了解采购流程</RouterLink>
        </div>
      </div>
      <div class="intro-note">
        <Icon name="shield" :size="30" /><strong>参数清楚，来源可查</strong
        ><span>了解设备，再做选择</span>
        <div class="intro-line"></div>
        <span class="intro-number">01 — 06</span
        ><small>六类办公设备 · 持续整理</small>
      </div>
    </section>
    <div class="catalog-layout">
      <aside class="category-panel">
        <span class="section-overline">设备分类</span
        ><button
          :class="{ selected: !route.query.category }"
          @click="filter(null)"
        >
          <Icon name="grid" />全部设备<Icon name="arrow" :size="15" /></button
        ><button
          v-for="category in categories"
          :key="category.id"
          :class="{ selected: String(category.id) === route.query.category }"
          @click="filter(category.id)"
        >
          <Icon :name="category.icon" />{{ category.name }}
        </button>
      </aside>
      <section class="catalog-results">
        <div class="catalog-toolbar">
          <div>
            <h2>
              {{
                route.query.category
                  ? categoryOf(route.query.category).name
                  : "全部设备"
              }}
            </h2>
            <span class="muted">{{
              error ? "暂时无法加载" : total + " 件商品"
            }}</span>
          </div>
          <form class="search-form" @submit.prevent="search">
            <el-input
              v-model="keyword"
              placeholder="搜索型号、名称或品牌"
              aria-label="搜索商品"
              clearable
              @clear="search"
              ><template #prefix
                ><Icon name="search" :size="18" /></template></el-input
            ><el-button native-type="submit">搜索</el-button>
          </form>
        </div>
        <div v-if="error" class="empty-state">
          <Icon name="box" :size="44" />
          <h3>设备目录暂时无法加载</h3>
          <p>{{ error }}</p>
          <el-button @click="load">重新加载</el-button>
        </div>
        <div v-else-if="busy" class="product-grid">
          <div v-for="n in 6" :key="n" class="skeleton-card">
            <el-skeleton animated :rows="5" />
          </div>
        </div>
        <div v-else-if="!items.length" class="empty-state">
          <Icon
            :name="
              route.query.category
                ? categoryOf(route.query.category).icon
                : 'search'
            "
            :size="48"
          />
          <h3>暂未找到相关设备</h3>
          <p class="muted">试试其他关键词，或查看全部设备。</p>
          <el-button
            @click="
              keyword = '';
              router.push('/');
            "
            >查看全部设备</el-button
          >
        </div>
        <div v-else class="product-grid">
          <RouterLink
            v-for="item in items"
            :key="item.id"
            :to="'/products/' + item.id"
            class="product-card"
            ><div class="card-image">
              <span class="category-label">{{
                categoryOf(item.categoryId).name
              }}</span
              ><ProductVisual :product="item" />
            </div>
            <div class="card-content">
              <div class="product-brand">
                {{ item.brand }} <span> / </span> {{ item.series }}
              </div>
              <h3>{{ item.name }}</h3>
              <div class="spec-chips">
                <span
                  v-for="(value, key) in Object.fromEntries(
                    Object.entries(item.specJson || {}).slice(0, 2),
                  )"
                  :key="key"
                  :title="`${specLabels[key] || key}：${specValue(value)}`"
                  >{{ specLabels[key] || key }}：{{ specValue(value) }}</span
                >
              </div>
              <div class="card-bottom">
                <div>
                  <span class="price" v-if="item.priceAvailable"
                    ><small>¥</small>{{ money(item.sale?.price) }}</span
                  ><span class="price-unavailable" v-else>{{
                    item.purchaseMessage
                  }}</span
                  ><small class="demo-price">演示售价</small>
                </div>
                <span :class="['availability', { available: item.purchasable }]"
                  ><i></i>{{ item.purchaseMessage }}</span
                >
              </div>
              <span class="card-link"
                >查看设备 <Icon name="arrow" :size="17"
              /></span></div
          ></RouterLink>
        </div>
        <el-pagination
          v-if="total > 9"
          class="pagination"
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="9"
          :current-page="Number(route.query.page || 1)"
          @current-change="
            (page) => router.push({ query: { ...route.query, page } })
          "
        />
      </section>
    </div>
  </div>
</template>
<style scoped>
.procurement-entry {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 20px;
  margin-top: 24px;
  font-size: 14px;
}
.procurement-entry .procurement-start {
  padding: 12px 18px;
  border-radius: 8px;
  background: #214e40;
  color: white;
  font-weight: 600;
}
.procurement-entry a:focus-visible {
  outline: 2px solid #214e40;
  outline-offset: 4px;
}
</style>
