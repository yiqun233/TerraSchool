import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  type ReactNode,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { components } from "../shared/generated/api";
import { login, logout, SessionExpired } from "../shared/http";
import { clearBrandingCache } from "../shared/theme/TenantBrandingProvider";

export type CurrentPermissions = components["schemas"]["CurrentPermissions"];

/**
 * 会话上下文：只放身份、租户与权限快照，不放业务数据。
 * 权限仍以服务端每次请求为准，这里的值只用于导航和按钮显隐。
 */
export interface SessionContext {
  accountId: string;
  tenantId: string;
  tenantName: string;
  login: string;
  displayName: string;
  tenantAdmin: boolean;
  passwordChangeRequired: boolean;
}

export class SessionUnavailable extends Error {}

async function getJson<T>(path: string): Promise<T | null> {
  const response = await fetch(path, { credentials: "same-origin" });
  if (response.status === 401) return null;
  if (!response.ok) throw new SessionUnavailable("无法读取当前登录信息");
  return (await response.json()) as T;
}

export async function fetchSessionContext(): Promise<SessionContext | null> {
  return getJson<SessionContext>("/api/session/context");
}

export async function fetchPermissions(): Promise<CurrentPermissions | null> {
  return getJson<CurrentPermissions>("/api/session/permissions");
}

interface SessionValue {
  session: SessionContext;
  permissions: CurrentPermissions | null;
  permissionsLoading: boolean;
  /** 会话失效时清理缓存并回到登录页；页面不要各自实现一套 */
  onUnauthorized: () => void;
  reload: () => void;
}

const Context = createContext<SessionValue | null>(null);

export function SessionProvider({
  session,
  children,
}: {
  session: SessionContext;
  children: ReactNode;
}) {
  const client = useQueryClient();
  const permissions = useQuery({
    queryKey: ["session-permissions", session.accountId, session.tenantId],
    queryFn: fetchPermissions,
    retry: false,
    staleTime: 30_000,
  });

  const onUnauthorized = useCallback(() => {
    clearBrandingCache(session.tenantId);
    void client.cancelQueries();
    client.clear();
    client.setQueryData(["session-context"], null);
  }, [client, session.tenantId]);

  const reload = useCallback(() => {
    void client.invalidateQueries({ queryKey: ["session-context"] });
    void client.invalidateQueries({ queryKey: ["session-permissions"] });
    void client.invalidateQueries({ queryKey: ["tenant-branding"] });
  }, [client]);

  const value = useMemo<SessionValue>(
    () => ({
      session,
      permissions: permissions.data ?? null,
      permissionsLoading: permissions.isPending,
      onUnauthorized,
      reload,
    }),
    [session, permissions.data, permissions.isPending, onUnauthorized, reload],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useSession() {
  const value = useContext(Context);
  if (!value) throw new Error("useSession 必须在 SessionProvider 内使用");
  return value;
}

/** 登录：成功后清空旧缓存与旧品牌，避免上一个身份的请求或颜色回灌。 */
export async function signIn(values: { username: string; password: string }) {
  await login(values);
  clearBrandingCache();
}

/** 退出：即使服务端退出失败也清理本地，避免卡在已失效的壳层里。 */
export async function signOut(tenantId: string) {
  try {
    await logout();
  } catch (error) {
    if (!(error instanceof SessionExpired)) throw error;
  } finally {
    clearBrandingCache(tenantId);
  }
}
