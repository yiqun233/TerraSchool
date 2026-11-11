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
export class SessionExpired extends Error {}
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

/**
 * 触发一次浏览器下载。
 *
 * 锚点必须先进入文档再点击，且不能在点击后立刻 revoke：Safari 与部分 Firefox
 * 版本此时还没读完 blob，会得到 0 字节或被中断的文件。延迟释放对结果没有副作用。
 */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.rel = "noopener";
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
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
  saveBlob(await response.blob(), filename);
  return true;
}

/**
 * 本人改密：首次登录强制改密与主动改密共用。
 *
 * 成功后会递增凭据版本，当前会话随即失效——调用方应把用户送回登录页。
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const token = await csrf();
  const response = await fetch("/api/session/password", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [token.headerName!]: token.token!,
    },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as {
      detail?: string;
    } | null;
    throw new Error(problem?.detail ?? "新密码不符合要求，请按提示修改");
  }
  if (!response.ok) throw new Error("修改密码失败，请重试");
}
