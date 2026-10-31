import createClient from "openapi-fetch";
import type { paths } from "./generated/api";
export const api = createClient<paths>({ credentials: "same-origin" });
export async function csrf() {
  const { data, error } = await api.GET("/api/session/csrf");
  if (error || !data) throw new Error("无法获取安全令牌，请重试");
  return data;
}
export async function login(values: { username: string; password: string }) {
  const token = await csrf();
  const response = await fetch("/api/session/login", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      ...values,
      [token.parameterName!]: token.token!,
    }),
  });
  if (!response.ok)
    throw new Error(
      response.status === 401 ? "账号或密码错误" : "登录失败，请刷新后重试",
    );
}
export async function logout() {
  const token = await csrf();
  const response = await fetch("/api/session/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { [token.headerName!]: token.token! },
  });
  if (!response.ok) throw new Error("退出失败，请重试");
}
