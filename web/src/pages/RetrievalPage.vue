<script setup>
import { onMounted, ref } from "vue";
import { api } from "../api";
import SemanticRetrievalPanel from "../components/SemanticRetrievalPanel.vue";
const products = ref([]),
  productId = ref(""),
  error = ref(""),
  busy = ref(false);
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const data = await api("/catalog/products?page=1&size=50", {
      scope: "merchant",
    });
    products.value = data.list || data.items || [];
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-container merchant-page">
    <div class="merchant-breadcrumb">商家工作台 <span>/</span> 语义检索</div>
    <section class="page-heading">
      <div>
        <div class="eyebrow">PRODUCT EVIDENCE</div>
        <h1>带着问题，查找商品依据。</h1>
        <p class="muted">
          先选商品，再查相关原文。适合核对接口、连接方式和使用限制。
        </p>
      </div>
    </section>
    <section class="panel">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div class="retrieval-selector">
        <label for="retrieval-product">选择已发布商品</label>
        <el-select
          id="retrieval-product"
          v-model="productId"
          filterable
          placeholder="请选择商品"
          :loading="busy"
          aria-label="检索商品"
        >
          <el-option
            v-for="p in products"
            :key="p.id"
            :value="String(p.id)"
            :label="p.name"
          />
        </el-select>
        <el-button v-if="error" @click="load">重新读取商品</el-button>
      </div>
      <SemanticRetrievalPanel
        v-if="productId"
        :key="productId"
        :product-id="productId"
      />
      <p v-else class="muted">选择后才显示检索操作，不会自动调用模型。</p>
    </section>
  </div>
</template>
<style scoped>
.retrieval-selector {
  display: grid;
  gap: 12px;
  margin-bottom: 28px;
  max-width: 560px;
}
.retrieval-selector label {
  font-weight: 600;
}
</style>
