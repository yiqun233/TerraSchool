import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PersonnelAttachments } from "./PersonnelAttachments";
const { get, csrf } = vi.hoisted(() => ({ get: vi.fn(), csrf: vi.fn() }));
vi.mock("../../shared/http", () => ({ api: { GET: get }, csrf }));
it("上传结果不确定时重试保留原文件和请求身份，后续拒绝后禁止继续提交", async () => {
  get.mockResolvedValue({ response: { ok: true }, data: [] });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "test" });
  const send = vi
    .spyOn(globalThis, "fetch")
    .mockResolvedValueOnce(new Response(null, { status: 502 }))
    .mockResolvedValueOnce(new Response(null, { status: 403 }));
  const user = userEvent.setup();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PersonnelAttachments
        row={{
          id: "e",
          schoolId: "s",
          kind: "EMPLOYEE",
          name: "员工",
          active: true,
          version: 0,
        }}
      />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole("button", { name: "档案附件" }));
  const picker = await screen.findByLabelText("选择附件");
  await user.upload(
    picker,
    new File(["content"], "测试.txt", { type: "text/plain" }),
  );
  await user.click(screen.getByRole("button", { name: /上\s*传/ }));
  await screen.findByText("上传结果尚未确认，请重试相同文件。");
  await user.click(screen.getByRole("button", { name: "重试相同上传" }));
  await screen.findByText("先前上传结果仍未确认，请关闭后核对附件列表。");
  await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  expect(send.mock.calls[0][0]).toEqual(send.mock.calls[1][0]);
  expect(
    (screen.getByRole("button", { name: /上\s*传/ }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  send.mockRestore();
}, 10000);
