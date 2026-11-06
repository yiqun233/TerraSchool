import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    // Ant Design 交互测试依赖真实定时器；按 CPU 数启动大量 jsdom 会抢占等待窗口，
    // 超时后尚未结束的交互还可能污染同文件后续用例。限制文件 worker，不增加超时或自动重试。
    maxWorkers: 2,
    testTimeout: 20000,
    hookTimeout: 20000,
  },
});
