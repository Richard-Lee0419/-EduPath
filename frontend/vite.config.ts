import { fileURLToPath, URL } from "node:url";

import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

const base = process.env.VITE_APP_BASE || '/';

export default defineConfig({
  base,
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      [base + 'api']: {
        target: process.env.VITE_PROXY_TARGET ?? "http://127.0.0.1:8080",
        changeOrigin: true,
        rewrite: (path) => path.slice(base.length - 1),
      },
    },
  },
});
