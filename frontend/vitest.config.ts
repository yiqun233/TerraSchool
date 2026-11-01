import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    // 组件测试用真实定时器操作 Ant Design 组件；并行跑 18 个文件时默认 5s 会被机器负载吃掉。
    // 这里只放宽单用例上限，断言内容不变。
    testTimeout: 20000,
    hookTimeout: 20000,
  },
});
