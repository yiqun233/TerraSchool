import { fireEvent, render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Mock } from "vitest";
import { vi } from "vitest";
import { CustomerWorkspace } from "./CustomerWorkspace";

/**
 * 客户工作台测试公共件。
 *
 * 背景：一个 1500+ 行的工作台组件在每次按键都会整块重渲染（含多个 antd Table/Modal），
 * `userEvent.type` 逐字符触发 onChange 时单个用例可达 5–20 秒，并在同一文件内累积到
 * 20 秒用例上限，导致门禁随机器负载时绿时红。这里统一用一次 `change` 事件写入文本，
 * 保留点击/下拉等真实交互；需要覆盖“逐字输入”路径的用例仍使用 `userEvent.type`。
 */
export type ApiResult = {
  response: { ok: boolean; status: number };
  data: unknown;
};

export const response = (data: unknown, status = 200): ApiResult => ({
  response: { ok: status < 400, status },
  data,
});

/** jsdom 不实现 crypto.subtle；用确定性的伪摘要代替，只影响指纹比较不影响断言。 */
export async function fakeDigest(_algorithm: string, bytes: Uint8Array) {
  const result = new Uint8Array(32);
  bytes.forEach((value, index) => {
    result[index % 32] = (result[index % 32] + value + index) % 256;
  });
  return result.buffer;
}

/** 一次性写入文本，替代逐字符 typing，避免每次按键都整块重渲染。 */
export function fillText(element: HTMLElement, value: string) {
  fireEvent.change(element, { target: { value } });
}

export function renderWorkspace(
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <CustomerWorkspace accountKey="tenant:user" onUnauthorized={vi.fn()} />
      </QueryClientProvider>,
    ),
  };
}

type Mocks = {
  get: Mock;
  post: Mock;
  put: Mock;
  csrf: Mock;
};

/**
 * 每个用例的默认服务端返回：一所可维护、可读完整电话的学校、一个渠道、空提醒、
 * 以及一条本人客户。用例内部用 `get.mockImplementation` 覆盖需要的分支。
 */
export function resetCustomerMocks({ get, post, put, csrf }: Mocks) {
  vi.clearAllMocks();
  localStorage.clear();
  vi.stubGlobal("crypto", {
    randomUUID: () => "request-1",
    subtle: { digest: fakeDigest },
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "test" });
  get.mockImplementation(async (path: string) => {
    if (path.endsWith("/capabilities"))
      return response({
        schools: [
          { id: "s1", name: "学校一", maintainable: true, phoneReadable: true },
        ],
      });
    if (path.endsWith("/channels"))
      return response({
        items: [{ id: "c1", name: "原渠道", active: true }],
        hasMore: false,
      });
    if (path.endsWith("/reminders"))
      return response({ items: [], hasMore: false });
    return response({
      items: [{ id: "p1", name: "本人客户", maskedPhone: "****1001" }],
      hasMore: false,
    });
  });
  post.mockReset();
  put.mockReset();
}
