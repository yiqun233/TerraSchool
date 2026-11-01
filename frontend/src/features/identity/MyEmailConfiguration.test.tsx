import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MyEmailConfiguration } from "./MyEmailConfiguration";
const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));
it("密码不回显，提交失败后清除输入并保留读取版本", async () => {
  get.mockResolvedValue({
    response: { ok: true },
    data: {
      employeeId: "e",
      smtpHost: "smtp.example.test",
      smtpUser: "u",
      senderAddress: "a@example.test",
      hasCredential: true,
      version: 4,
    },
  });
  post.mockResolvedValue({
    response: { ok: false, status: 409 },
    error: { code: "MAIL_KEY_MISMATCH" },
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF", token: "token" });
  const user = userEvent.setup();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MyEmailConfiguration onChanged={vi.fn()} />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole("button", { name: "我的邮箱配置" }));
  await screen.findByText("当前凭据：已设置");
  expect(screen.queryByLabelText("新邮箱密码或授权码")).toBeNull();
  await user.click(screen.getByLabelText("邮箱凭据"));
  await user.click(await screen.findByText("设置或更换凭据"));
  await user.type(
    screen.getByLabelText("新邮箱密码或授权码"),
    "private-test-secret",
  );
  await user.click(screen.getByRole("button", { name: /保\s*存/ }));
  await screen.findByText("邮件凭据密钥不可用，请联系管理员恢复原密钥。");
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][1].body.expectedVersion).toBe(4);
  expect(
    (screen.getByLabelText("新邮箱密码或授权码") as HTMLInputElement).value,
  ).toBe("");
  expect(
    (screen.getByRole("button", { name: /保\s*存/ }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
}, 10000);
