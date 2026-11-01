import { afterEach } from "vitest";
import { cleanup, configure } from "@testing-library/react";
afterEach(cleanup);
// 并行跑多个测试文件时首次渲染可能超过默认 1s 的等待窗口；放宽异步查询超时，
// 断言内容不变，只是避免把机器负载当成失败。
configure({ asyncUtilTimeout: 5000 });
// jsdom 不渲染伪元素；Ant 的滚动条测量在测试中复用普通元素样式。
const computedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element) => computedStyle(element);
Object.defineProperty(window, "matchMedia", {
  value: () => ({
    matches: false,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
  }),
});
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
Object.defineProperty(globalThis, "ResizeObserver", {
  value: ResizeObserverMock,
});
