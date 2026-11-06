import { afterEach } from "vitest";
import { cleanup, configure } from "@testing-library/react";
afterEach(cleanup);
// 整页工作台的首次渲染在负载高时要数秒（Ant Design 表格在 jsdom 中每张约 1 秒），
// 默认 1 秒窗口会把“慢”误判为“不存在”。这里只放宽异步查询等待，断言内容不变。
configure({ asyncUtilTimeout: 12000 });
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
