import { afterEach, expect, it, vi } from "vitest";
import { api, logout } from "./http";

afterEach(() => vi.restoreAllMocks());

it("远程撤销导致安全令牌返回401时退出完成", async () => {
  vi.spyOn(api, "GET").mockResolvedValue({
    response: new Response(null, { status: 401 }),
  } as never);
  const send = vi.spyOn(globalThis, "fetch");
  await expect(logout()).resolves.toBeUndefined();
  expect(send).not.toHaveBeenCalled();
});
