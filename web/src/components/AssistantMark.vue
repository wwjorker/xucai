<script setup>
defineProps({ size: { type: Number, default: 40 } });
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 80 80"
    fill="none"
    aria-hidden="true"
  >
    <rect x="3" y="3" width="74" height="74" rx="26" fill="#205C49" />
    <path
      d="M19 28h42v23a8 8 0 0 1-8 8H33l-11 7v-9a8 8 0 0 1-3-6V28Z"
      fill="#E7F4DB"
    />
    <path d="m25 29 13-14 5 10 14-7-5 11" fill="#C8D99D" />
    <rect x="29" y="36" width="5" height="9" rx="2.5" fill="#205C49" />
    <rect x="47" y="36" width="5" height="9" rx="2.5" fill="#205C49" />
    <path
      d="M35 50c3 3 7 3 10 0"
      stroke="#205C49"
      stroke-width="3"
      stroke-linecap="round"
    />
    <path d="m65 9 2 5 5 2-5 2-2 5-2-5-5-2 5-2 2-5Z" fill="#DCA963" />
  </svg>
</template>
