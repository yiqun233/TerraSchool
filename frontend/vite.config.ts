import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * 两个独立入口：租户管理端（index.html）与平台运营端（platform.html）。
 * 平台端不加载租户主题，避免租户品牌样式漏进平台界面（DEC-025）。
 */
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL("./index.html", import.meta.url)),
        platform: fileURLToPath(new URL("./platform.html", import.meta.url)),
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.TERASCHOOL_BACKEND_URL ?? "http://127.0.0.1:8081",
      },
    },
  },
});
