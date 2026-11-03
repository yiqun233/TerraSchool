import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CustomerFollowups } from "./CustomerFollowups";
const { csrf } = vi.hoisted(() => ({ csrf: vi.fn() }));
vi.mock("../../shared/http", () => ({ csrf }));
function setup() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <CustomerFollowups
        customerId="customer-1"
        accountKey="tenant:user"
        canEdit
        onClose={vi.fn()}
        onUnauthorized={vi.fn()}
      />
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  localStorage.clear();
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "test" });
  vi.stubGlobal("crypto", {
    randomUUID: () => "request-1",
    subtle: {
      digest: async (_algorithm: string, bytes: Uint8Array) => {
        const result = new Uint8Array(32);
        bytes.forEach((v, i) => {
          result[i % 32] = (result[i % 32] + v + i) % 256;
        });
        return result.buffer;
      },
    },
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});
it("结果不确定后不保存正文，并以同一请求重试", async () => {
  let posts = 0;
  const fetchMock = vi.fn(async (_url: string, options?: RequestInit) => {
    if (options?.method === "POST") {
      posts++;
      if (posts === 1) throw new Error("network");
      return { ok: true, status: 200 };
    }
    return {
      ok: true,
      status: 200,
      json: async () => ({ items: [], hasMore: false }),
    };
  });
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  const first = setup();
  await user.type(await screen.findByLabelText("跟进内容"), "敏感跟进内容");
  await user.type(screen.getByLabelText("跟进发生日期"), "2026-11-01");
  await user.click(screen.getByRole("button", { name: "保存跟进" }));
  await screen.findByText(/结果尚未确认/);
  expect(
    localStorage.getItem("teraschool.customer-followup.tenant:user.customer-1"),
  ).not.toContain("敏感跟进内容");
  first.unmount();
  setup();
  await user.type(await screen.findByLabelText("跟进内容"), "敏感跟进内容");
  await user.type(screen.getByLabelText("跟进发生日期"), "2026-11-01");
  await user.click(screen.getByRole("button", { name: "重试保存" }));
  await screen.findByText("跟进记录已保存。");
  const requests = fetchMock.mock.calls.filter(
    (call) => call[1]?.method === "POST",
  );
  expect(
    requests.map((call) => JSON.parse(String(call[1]?.body)).requestId),
  ).toEqual(["request-1", "request-1"]);
  expect(localStorage.length).toBe(0);
});
