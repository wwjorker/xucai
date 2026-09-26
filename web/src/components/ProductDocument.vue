<script setup>
import { computed } from "vue";
import AssistantText from "./AssistantText.vue";
const props = defineProps({ content: { type: String, default: "" } });
// Display the stored source in its original order; do not summarize or remove caveats.
const lines = computed(() => props.content.split(/\r?\n/).map((text) => {
  const heading = text.match(/^#{1,6}\s+(.+)$/);
  const bullet = text.match(/^[-*]\s+(.+)$/);
  return { text: heading?.[1] ?? bullet?.[1] ?? text, heading: !!heading, bullet: !!bullet };
}));
</script>
<template>
  <div class="product-document">
    <template v-for="(line, i) in lines" :key="i">
      <h4 v-if="line.heading"><AssistantText :text="line.text" /></h4>
      <p v-else-if="line.text" :class="{ 'document-bullet': line.bullet }"><AssistantText :text="line.text" /></p>
      <div v-else class="document-gap" aria-hidden="true"></div>
    </template>
  </div>
</template>
<style scoped>
.product-document { line-height: 1.9; color: #3c4d43; overflow-wrap: anywhere; }
h4 { font-size: 15px; margin: 18px 0 8px; color: #203d31; }
p { margin: 5px 0; white-space: pre-wrap; }
.document-bullet { padding-left: 17px; position: relative; }
.document-bullet::before { content: "•"; position: absolute; left: 0; color: #537862; }
.document-gap { height: 8px; }
</style>
