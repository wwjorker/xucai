<script setup>
import { ref, watch } from "vue";
import { api } from "../api";
import { productImageUrl } from "../domain";
import Icon from "./Icon.vue";
const props = defineProps({ product: Object, preview: Boolean });
const image = ref(null), failed = ref(false);
watch(() => [props.product?.id, props.preview], async ([id, preview], _, onCleanup) => {
  let active = true;
  onCleanup(() => { active = false; });
  image.value = null;
  failed.value = false;
  if (preview || !/^[1-9]\d*$/.test(String(id))) return;
  try {
    const product = await api("/catalog/products/" + id, { retry: false });
    if (active) image.value = productImageUrl(product.imageUrl);
  } catch { /* Hidden/unavailable products keep their category icon. */ }
}, { immediate: true });
</script>
<template>
  <img v-if="image && !failed" :src="image" :alt="product.name" loading="lazy" referrerpolicy="no-referrer" @error="failed = true" style="width:100%;height:100%;object-fit:contain" />
  <Icon v-else :name="{1:'monitor',2:'printer',3:'mouse',4:'keyboard',5:'camera',6:'headphone'}[product.category] || 'box'" :size="32" />
</template>
