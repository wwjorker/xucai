import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { localApiOrigin } from "./src/localProxy.js";
const proxy = {
  "/api": {
    target: localApiOrigin(process.env.XUCAI_API_ORIGIN),
    changeOrigin: true,
    rewrite: (path) => path.replace(/^\/api/, ""),
  },
};
export default defineConfig({
  plugins: [vue()],
  server: { proxy },
  preview: { proxy },
  build: {
    rolldownOptions: {
      output: {
        manualChunks: (id) =>
          id.includes("node_modules") ? "vendor" : undefined,
      },
    },
  },
});
