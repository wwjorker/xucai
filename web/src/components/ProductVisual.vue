<script setup>
import { ref, watch } from "vue";
import { categoryOf, productImageUrl } from "../domain";
import Icon from "./Icon.vue";
const props = defineProps({ product: Object, large: Boolean });
const failed = ref(false);
watch(
  () => props.product?.imageUrl,
  () => {
    failed.value = false;
  },
);
</script>
<template>
  <div class="product-visual" :class="{ large }">
    <img
      v-if="productImageUrl(product.imageUrl) && !failed"
      :src="productImageUrl(product.imageUrl)"
      :alt="product.name"
      loading="lazy"
      referrerpolicy="no-referrer"
      @error="failed = true"
    />
    <template v-else
      ><div class="visual-orbit"></div>
      <Icon
        :name="categoryOf(product.categoryId).icon"
        :size="large ? 146 : 90"
      /><span class="image-note">暂无商品图片 · 设备示意</span></template
    >
  </div>
</template>
