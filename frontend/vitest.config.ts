import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    // Ant Design 的 Table 在 jsdom 里单次挂载约 1 秒，整页工作台一次渲染会挂多张表；
    // 同一份代码在机器负载高时会在 20 秒窗口外完成，表现为随机失败（2026-11-06 实测：
    // 同一天两次全量运行分别是 5 项失败与 96 项全通过）。这里按“可完成但慢”放宽窗口，
    // 不增加自动重试，也不隐藏真实失败：断言内容与用例数量不变。
    maxWorkers: 2,
    testTimeout: 45000,
    hookTimeout: 45000,
  },
});
