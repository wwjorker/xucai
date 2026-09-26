<script setup>
defineProps({ name: String, size: { default: 20 } });
const paths = {
  chat: "M4 4h16v12H9l-5 4zM8 8h8M8 12h5",
  history: "M3 4v5h5M3 9a9 9 0 1 1 0 7M12 7v5l3 2",
  close: "m6 6 12 12M18 6 6 18",
  expand: "M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5",
  dock: "M3 4h18v16H3zM15 4v16",
  minus: "M5 12h14",
  send: "m3 3 18 9-18 9 4-9zM7 12h14",
  edit: "m4 16 12-12 4 4L8 20H4zM13 7l4 4",
  trash: "M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7",
  copy: "M8 8h13v13H8zM16 8V3H3v13h5",
  stop: "M5 5h14v14H5z",
  chevron: "m9 5 7 7-7 7",
  search: "m21 21-4.4-4.4M19 10.5a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0",
  arrow: "M4 12h16m-6-6 6 6-6 6",
  back: "M20 12H4m6-6-6 6 6 6",
  plus: "M12 5v14M5 12h14",
  check: "m5 12 4 4L19 6",
  monitor: "M3 4h18v13H3zM12 17v4M7 21h10",
  printer: "M6 8V3h12v5M6 17H3V8h18v9h-3M6 14h12v7H6zM17 11h1",
  mouse: "M6 9a6 6 0 0 1 12 0v6a6 6 0 0 1-12 0zM12 3v6M6 10h12",
  keyboard:
    "M2 5h20v14H2zM5 9h1m3 0h1m3 0h1m3 0h1M5 12h1m3 0h1m3 0h1m3 0h1M6 16h12",
  camera: "M3 5h18v11H3zM12 16v4M7 21h10M16 10.5a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  headphone: "M3 14V11a9 9 0 0 1 18 0v3M3 12h4v9H3zM17 12h4v9h-4z",
  box: "m3 7 9-5 9 5v10l-9 5-9-5zM3 7l9 5 9-5M12 12v10",
  document: "M6 2h8l5 5v15H6zM14 2v6h5M9 12h7M9 16h7",
  grid: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  user: "M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 22v-3a8 8 0 0 1 16 0v3",
  logout: "M9 3H3v18h6M8 12h13m-5-5 5 5-5 5",
  refresh: "M20 7a9 9 0 1 0 1 8M20 2v6h-6",
  shield: "m12 2 9 4v6c0 6-9 10-9 10S3 18 3 12V6zM8 12l3 3 5-6",
  link: "M14 3h7v7M21 3 10 14M10 5H3v16h16v-7",
};
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.65"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.box" />
  </svg>
</template>
