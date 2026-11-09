/**
 * 平台运营端接口：与租户的 /api/session/** 完全分开。
 * 平台会话失效语义（401 回登录、403 无权限）与租户端不同，这里独立实现，不复用 shared/http。
 */
import { normalizeBranding, type TenantBranding } from "../shared/theme/brand";

export interface PlatformOperator {
  login: string;
  displayName: string;
  actions: string[];
}

export interface TenantRow {
  id: string;
  name: string;
  active: boolean;
  /** 服务端返回 snake_case 的开通时间 */
  created_at: string;
}

/** 登录名或口令错误：与“会话过期”区分，避免把用户踢回登录页时给出错误提示。 */
export class LoginRejected extends Error {}

/** 平台会话缺失或过期。 */
export class SessionExpired extends Error {}

/** 已登录但缺少动作权限。 */
export class AccessDenied extends Error {}

export class RequestFailed extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function csrfToken(): Promise<{ headerName: string; token: string }> {
  const response = await fetch("/api/platform/session/csrf", {
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw new RequestFailed(response.status, "无法获取安全令牌，请刷新后重试");
  }
  const data = (await response.json()) as {
    headerName?: string;
    token?: string;
  };
  if (!data.headerName || !data.token) {
    throw new RequestFailed(500, "安全令牌无效，请刷新后重试");
  }
  return { headerName: data.headerName, token: data.token };
}

/** 读取当前平台会话；未登录返回 null，不当作错误。 */
export async function currentOperator(): Promise<PlatformOperator | null> {
  const response = await fetch("/api/platform/session/me", {
    credentials: "same-origin",
  });
  if (response.status === 401) return null;
  if (!response.ok) {
    throw new RequestFailed(response.status, "无法读取平台会话");
  }
  return (await response.json()) as PlatformOperator;
}

export async function signIn(login: string, password: string): Promise<void> {
  const token = await csrfToken();
  const response = await fetch("/api/platform/session/login", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      [token.headerName]: token.token,
    },
    body: new URLSearchParams({ login, password }),
  });
  // 服务端对“账号不存在”和“口令错误”给同一个 401，这里也合并提示，避免枚举。
  if (response.status === 401) throw new LoginRejected("登录名或密码不正确");
  if (!response.ok) {
    throw new RequestFailed(response.status, "登录失败，请稍后重试");
  }
}

export async function signOut(): Promise<void> {
  const token = await csrfToken();
  const response = await fetch("/api/platform/session/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { [token.headerName]: token.token },
  });
  if (!response.ok && response.status !== 401) {
    throw new RequestFailed(response.status, "退出失败，请重试");
  }
}

/** 停用或恢复租户：失败按状态码区分权限、不存在与一般错误。 */
export async function setTenantActive(
  id: string,
  active: boolean,
): Promise<void> {
  const token = await csrfToken();
  const action = active ? "resume" : "suspend";
  const response = await fetch(`/api/platform/tenants/${id}/${action}`, {
    method: "POST",
    credentials: "same-origin",
    headers: { [token.headerName]: token.token },
  });
  if (response.status === 401) {
    throw new SessionExpired("登录已失效，请重新登录");
  }
  if (response.status === 403) {
    throw new AccessDenied(
      active ? "缺少 TENANT_RESUME 权限" : "缺少 TENANT_SUSPEND 权限",
    );
  }
  if (response.status === 404) {
    throw new RequestFailed(404, "租户不存在，刷新后再试");
  }
  if (!response.ok) {
    throw new RequestFailed(
      response.status,
      active ? "恢复失败，请重试" : "停用失败，请重试",
    );
  }
}

export interface TenantSummary {
  total: number;
  active: number;
  suspended: number;
  thisMonth: number;
}

/** 租户列表：只有名称、状态与开通时间，不含业务数据（DEC-024）。 */
export async function fetchTenants(
  page: number,
  size = 20,
  status = "",
  keyword = "",
): Promise<{ items: TenantRow[]; total: number; summary: TenantSummary }> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (status) params.set("status", status);
  if (keyword) params.set("keyword", keyword);
  const response = await fetch(`/api/platform/tenants?${params}`, {
    credentials: "same-origin",
  });
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403) {
    throw new AccessDenied("缺少 TENANT_READ 权限");
  }
  if (!response.ok) {
    throw new RequestFailed(response.status, "加载租户列表失败");
  }
  const data = (await response.json()) as {
    items?: TenantRow[];
    total?: number;
    summary?: Partial<TenantSummary>;
  };
  return {
    items: data.items ?? [],
    total: data.total ?? 0,
    summary: {
      total: data.summary?.total ?? data.total ?? 0,
      active: data.summary?.active ?? 0,
      suspended: data.summary?.suspended ?? 0,
      thisMonth: data.summary?.thisMonth ?? 0,
    },
  };
}

/** 开通租户：只建租户与管理员，不建学校（DEC-024）。 */
export async function provisionTenant(input: {
  requestId: string;
  name: string;
  adminLogin: string;
  adminPassword: string;
}): Promise<{ tenantId: string; accountId: string }> {
  const token = await csrfToken();
  const response = await fetch("/api/platform/tenants", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [token.headerName]: token.token,
    },
    body: JSON.stringify(input),
  });
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403)
    throw new AccessDenied("缺少 TENANT_CREATE 权限");
  if (response.status === 409) {
    const problem = (await response.json().catch(() => null)) as {
      code?: string;
    } | null;
    if (problem?.code === "ADMIN_LOGIN_TAKEN")
      throw new RequestFailed(409, "管理员登录名已被占用，请换一个");
    if (problem?.code === "IDEMPOTENCY_MISMATCH")
      throw new RequestFailed(409, "同一请求不能改参数，请刷新后重新开通");
    throw new RequestFailed(409, "开通冲突，请核对信息后重试");
  }
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as {
      detail?: string;
    } | null;
    throw new RequestFailed(400, problem?.detail ?? "开通信息不完整或不合规");
  }
  if (!response.ok)
    throw new RequestFailed(response.status, "开通失败，请重试");
  return (await response.json()) as { tenantId: string; accountId: string };
}

/** 重置租户管理员密码：不传密码时由服务端生成，明文只在这一次响应里返回。 */
export async function resetAdminPassword(
  tenantId: string,
  login: string,
): Promise<{ login: string; password: string }> {
  const token = await csrfToken();
  const response = await fetch(
    `/api/platform/tenants/${tenantId}/administrators/${encodeURIComponent(login)}/reset-password`,
    {
      method: "POST",
      credentials: "same-origin",
      headers: { [token.headerName]: token.token },
    },
  );
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403) throw new AccessDenied("缺少重置密码的权限");
  if (response.status === 404)
    throw new RequestFailed(404, "该租户没有可重置的管理员");
  if (!response.ok)
    throw new RequestFailed(response.status, "重置失败，请重试");
  return (await response.json()) as { login: string; password: string };
}

/** 某租户的租户管理员（登录名与状态）。 */
export async function fetchAdministrators(
  tenantId: string,
): Promise<Array<{ login: string; active: boolean }>> {
  const response = await fetch(
    `/api/platform/tenants/${tenantId}/administrators`,
    { credentials: "same-origin" },
  );
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403) throw new AccessDenied("缺少 TENANT_READ 权限");
  if (!response.ok) throw new RequestFailed(response.status, "加载管理员失败");
  const data = (await response.json()) as {
    items?: Array<{ login: string; active: boolean }>;
  };
  return data.items ?? [];
}

export interface TenantDetail {
  id: string;
  name: string;
  active: boolean;
  created_at: string;
  schools: number;
  accounts: number;
  administrators: number;
}

/** 平台查看租户品牌：与租户端同一份记录，不含业务数据。 */
export async function fetchTenantBranding(id: string): Promise<TenantBranding> {
  const response = await fetch(`/api/platform/tenants/${id}/branding`, {
    credentials: "same-origin",
  });
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403) throw new AccessDenied("缺少 TENANT_READ 权限");
  if (!response.ok) throw new RequestFailed(response.status, "加载品牌失败");
  return normalizeBranding(await response.json());
}

export interface BrandingHistoryRow {
  version: number;
  displayName: string | null;
  seed: string;
  brand: string;
  changedAt: string;
  changedByKind: "TENANT" | "PLATFORM";
}

/** 平台查看品牌历史版本，用于选择回滚目标。 */
export async function fetchBrandingHistory(
  id: string,
): Promise<BrandingHistoryRow[]> {
  const response = await fetch(`/api/platform/tenants/${id}/branding/history`, {
    credentials: "same-origin",
  });
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403) throw new AccessDenied("缺少 TENANT_READ 权限");
  if (!response.ok)
    throw new RequestFailed(response.status, "加载品牌历史失败");
  return (await response.json()) as BrandingHistoryRow[];
}

/** 平台按历史版本回滚；服务端复制成新版本，不删除旧版本。 */
export async function rollbackTenantBranding(
  id: string,
  version: number,
): Promise<TenantBranding> {
  const token = await csrfToken();
  const response = await fetch(
    `/api/platform/tenants/${id}/branding/rollback`,
    {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        [token.headerName]: token.token,
      },
      body: JSON.stringify({ version }),
    },
  );
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403) throw new AccessDenied("缺少回滚品牌的权限");
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as {
      detail?: string;
    } | null;
    throw new RequestFailed(400, problem?.detail ?? "回滚版本无效");
  }
  if (!response.ok) throw new RequestFailed(response.status, "回滚品牌失败");
  return normalizeBranding(await response.json());
}

/** 平台代改品牌：写平台审计，租户侧立即生效。 */
export async function updateTenantBranding(
  id: string,
  input: { displayName: string | null; seed: string; version: number },
): Promise<TenantBranding> {
  const token = await csrfToken();
  const response = await fetch(`/api/platform/tenants/${id}/branding`, {
    method: "PUT",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [token.headerName]: token.token,
      "If-Match": String(input.version),
    },
    body: JSON.stringify({
      displayName: input.displayName,
      seed: input.seed,
      expectedVersion: input.version,
    }),
  });
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403) throw new AccessDenied("缺少代改品牌的权限");
  if (response.status === 409)
    throw new RequestFailed(409, "品牌已被其他人修改，请刷新后重试");
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as {
      detail?: string;
    } | null;
    throw new RequestFailed(400, problem?.detail ?? "品牌设置不合规");
  }
  if (!response.ok) throw new RequestFailed(response.status, "品牌保存失败");
  return normalizeBranding(await response.json());
}

/** 租户详情：只有名称、状态、开通时间与三个计数，没有任何业务明细。 */
export async function fetchTenantDetail(id: string): Promise<TenantDetail> {
  const response = await fetch(`/api/platform/tenants/${id}`, {
    credentials: "same-origin",
  });
  if (response.status === 401)
    throw new SessionExpired("登录已失效，请重新登录");
  if (response.status === 403) throw new AccessDenied("缺少 TENANT_READ 权限");
  if (response.status === 404)
    throw new RequestFailed(404, "租户不存在，刷新后再试");
  if (!response.ok)
    throw new RequestFailed(response.status, "加载租户详情失败");
  return (await response.json()) as TenantDetail;
}
