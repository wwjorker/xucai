<script setup>
import { computed } from "vue";
import { assistantTextParts } from "../assistantText";
const props = defineProps({ text: { type: String, default: "" } });
// Render a small, safe formatting subset with Vue text nodes. Never inject model HTML.
const parts = computed(() => assistantTextParts(props.text));
</script>
<template>
  <span
    ><template v-for="(part, index) in parts" :key="index"
      ><a v-if="part.href" :href="part.href" target="_blank" rel="noopener noreferrer">{{ part.text }}</a
      ><strong v-else-if="part.bold">{{ part.text }}</strong
      ><code v-else-if="part.code">{{ part.text }}</code
      ><template v-else>{{ part.text }}</template></template
    ></span
  >
</template>
