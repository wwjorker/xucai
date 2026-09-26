<script setup>
import { ref, watch } from "vue";
import { api } from "../api";
import { productImageUrl } from "../domain";
const props = defineProps({ imageUrl: String });
const src = ref(null), error = ref("");
watch(() => props.imageUrl, async (value, _, onCleanup) => {
  let active = true, local = null;
  onCleanup(() => { active = false; if (local) URL.revokeObjectURL(local); });
  src.value = null; error.value = "";
  const safe = productImageUrl(value);
  if (!safe) return;
  if (!safe.startsWith("/api/catalog/products/")) { src.value = safe; return; }
  try {
    const blob = await api(safe.replace("/api/catalog/products/", "/catalog/admin/products/"), { scope: "merchant", binary: true });
    if (active) { local = URL.createObjectURL(blob); src.value = local; }
  } catch { if (active) error.value = "图片暂未加载，请刷新核对"; }
}, { immediate: true });
</script>
<template>
  <img v-if="src" :src="src" alt="已保存的商品图片" referrerpolicy="no-referrer" style="max-width:180px;max-height:150px;object-fit:contain" @error="src = null; error = '图片暂未加载'" />
  <p v-if="error" role="status">{{ error }}</p>
</template>
