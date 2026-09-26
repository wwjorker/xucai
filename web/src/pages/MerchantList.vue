<script setup>
import { ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { api, query } from "../api";
import { money, statusName, categoryOf } from "../domain";
import Icon from "../components/Icon.vue";
import ProductVisual from "../components/ProductVisual.vue";
const router = useRouter(),
  items = ref([]),
  total = ref(0),
  page = ref(1),
  keyword = ref(""),
  busy = ref(false),
  error = ref("");
let generation = 0;
async function load() {
  const g = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const data = await api(
      "/catalog/admin/products?" +
        query({ page: page.value, size: 10, keyword: keyword.value.trim() }),
      { scope: "merchant" },
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
function search() {
  page.value = 1;
  load();
}
onMounted(load);
</script>
<template>
  <div class="page-container merchant-page">
    <div class="merchant-breadcrumb">商家工作台 <span>/</span> 商品管理</div>
    <section class="page-heading">
      <div>
        <div class="eyebrow">PRODUCT MANAGEMENT</div>
        <h1>把商品信息，维护清楚。</h1>
        <p class="muted">从商品档案到售卖设置，在这里管理设备目录。</p>
      </div>
      <el-button
        type="primary"
        size="large"
        @click="router.push('/merchant/products/new')"
        ><Icon name="plus" :size="18" /> 新建商品</el-button
      >
    </section>
    <div class="merchant-guide">
      <span><b>01</b> 建立商品档案</span><Icon name="arrow" :size="17" /><span
        ><b>02</b> 添加来源资料并发布</span
      ><Icon name="arrow" :size="17" /><span><b>03</b> 设置售价库存并开售</span>
    </div>
    <section class="panel">
      <div class="panel-toolbar">
        <h2>
          商品目录 <small>{{ total }} 件</small>
        </h2>
        <form class="search-form" @submit.prevent="search">
          <el-input
            v-model="keyword"
            clearable
            placeholder="按名称、品牌或型号搜索"
            aria-label="搜索商家商品"
            @clear="search"
            ><template #prefix
              ><Icon name="search" :size="18" /></template></el-input
          ><el-button native-type="submit">搜索</el-button
          ><el-button aria-label="刷新商品列表" @click="load"
            ><Icon name="refresh" :size="17"
          /></el-button>
        </form>
      </div>
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        show-icon
        class="panel-alert"
      />
      <el-table
        v-loading="busy"
        :data="items"
        empty-text="暂无商品，点击“新建商品”开始"
        class="merchant-table"
        @row-dblclick="
          (item) => router.push('/merchant/products/' + item.id + '/edit')
        "
      >
        <el-table-column label="商品" min-width="320"
          ><template #default="{ row }"
            ><div class="table-product">
              <ProductVisual :product="row" />
              <div>
                <RouterLink :to="'/merchant/products/' + row.id + '/edit'">{{
                  row.name
                }}</RouterLink
                ><small
                  >{{ categoryOf(row.categoryId).name }} <span>·</span>
                  {{ row.series }}</small
                >
              </div>
            </div></template
          ></el-table-column
        >
        <el-table-column label="发布状态" width="120"
          ><template #default="{ row }"
            ><span :class="['status-pill', row.status.toLowerCase()]">{{
              statusName(row.status)
            }}</span></template
          ></el-table-column
        >
        <el-table-column label="演示售价" width="140"
          ><template #default="{ row }"
            ><b v-if="row.priceAvailable">¥ {{ money(row.sale?.price) }}</b
            ><span v-else class="muted">{{
              row.purchaseMessage === "价格暂不可用" ? "暂不可用" : "未设置"
            }}</span></template
          ></el-table-column
        >
        <el-table-column label="库存 / 售卖" width="170"
          ><template #default="{ row }"
            ><span v-if="row.sale"
              >{{ row.sale.stock }} 件
              <span class="muted"
                >/ {{ row.sale.onSale ? "在售" : "停售" }}</span
              ></span
            ><span v-else class="muted">暂无售卖信息</span></template
          ></el-table-column
        >
        <el-table-column label="操作" width="95" fixed="right"
          ><template #default="{ row }"
            ><RouterLink
              class="text-link"
              :to="'/merchant/products/' + row.id + '/edit'"
              >管理 <Icon name="arrow" :size="15" /></RouterLink></template
        ></el-table-column> </el-table
      ><el-pagination
        v-if="total > 10"
        class="pagination"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="10"
        v-model:current-page="page"
        @current-change="load"
      />
    </section>
    <p class="muted fine-print">
      隐藏商品前，先在售卖设置中停售。已保存的资料历史会持续保留。
    </p>
  </div>
</template>
