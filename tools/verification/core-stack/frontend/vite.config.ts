import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 构建验证专用；尚未启动开发服务或声明端到端页面已验收。
export default defineConfig({ plugins: [react()], server: { host: "127.0.0.1", port: 5173, strictPort: true } });
