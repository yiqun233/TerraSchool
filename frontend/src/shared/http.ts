import createClient from "openapi-fetch";
import type { paths } from "./generated/api";
export const api = createClient<paths>({ credentials: "same-origin" });
export async function csrf() {
  const { data, error, response } = await api.GET("/api/session/csrf");
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (error || !data) throw new Error("无法获取安全令牌，请重试");
  return data;
}
class SessionExpired extends Error {}
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
  let token;
  try {
    token = await csrf();
  } catch (error) {
    if (error instanceof SessionExpired) return;
    throw error;
  }
  const response = await fetch("/api/session/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { [token.headerName!]: token.token! },
  });
  if (!response.ok && response.status !== 401)
    throw new Error("退出失败，请重试");
}

/** 下载 CSV：走浏览器下载，服务端已按权限处理并写审计。 */
export async function downloadCsv(
  path: string,
  filename: string,
): Promise<boolean> {
  let response: Response;
  try {
    response = await fetch(path, { credentials: "include" });
  } catch {
    window.alert("导出失败，请检查网络后重试。");
    return false;
  }
  if (!response.ok) {
    const detail = await response.json().catch(() => null);
    window.alert(
      detail?.code === "EXPORT_LIMIT_EXCEEDED"
        ? "导出超过 5000 条，请缩小范围后重试。"
        : "导出失败，请检查权限或稍后重试。",
    );
    return false;
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
  return true;
}
