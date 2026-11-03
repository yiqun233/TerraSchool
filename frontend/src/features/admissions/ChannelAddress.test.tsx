import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ChannelAddress } from "./ChannelAddress";
const { csrf } = vi.hoisted(() => ({ csrf: vi.fn() }));
vi.mock("../../shared/http", () => ({ csrf }));
beforeEach(() => {
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "token" });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});
it("保存地址携带读取版本，冲突后禁止覆盖", async () => {
  const fetchMock = vi.fn(async (_url: string, options?: RequestInit) =>
    options?.method === "POST"
      ? { ok: false, status: 409 }
      : {
          ok: true,
          status: 200,
          json: async () => ({ id: "c1", address: "原地址", version: 2 }),
        },
  );
  vi.stubGlobal("fetch", fetchMock);
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ChannelAddress
        id="c1"
        schoolId="s1"
        canEdit
        onClose={vi.fn()}
        onUnauthorized={vi.fn()}
      />
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  const input = await screen.findByRole("textbox", { name: "渠道地址" });
  await user.clear(input);
  await user.type(input, "新地址");
  await user.click(screen.getByRole("button", { name: "保存地址" }));
  await screen.findByText(/渠道已变更/);
  const request = fetchMock.mock.calls.find(
    (call) => call[1]?.method === "POST",
  );
  expect(JSON.parse(String(request?.[1]?.body))).toEqual({
    address: "新地址",
    expectedVersion: 2,
  });
  expect(
    (screen.getByRole("button", { name: "保存地址" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});
it("保存时权限被撤销后隐藏已读取地址", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, options?: RequestInit) =>
      options?.method === "POST"
        ? { ok: false, status: 403 }
        : {
            ok: true,
            status: 200,
            json: async () => ({ id: "c1", address: "原地址", version: 1 }),
          },
    ),
  );
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ChannelAddress
        id="c1"
        schoolId="s1"
        canEdit
        onClose={vi.fn()}
        onUnauthorized={vi.fn()}
      />
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await screen.findByRole("textbox", { name: "渠道地址" });
  await user.click(screen.getByRole("button", { name: "保存地址" }));
  await screen.findByText(/保存未完成/);
  expect(screen.queryByRole("textbox", { name: "渠道地址" })).toBeNull();
});
