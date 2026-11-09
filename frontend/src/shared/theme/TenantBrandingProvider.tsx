import {
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { ConfigProvider } from "antd";
import { useQuery } from "@tanstack/react-query";
import {
  brandCssVariables,
  DEFAULT_BRANDING,
  normalizeBranding,
  type TenantBranding,
} from "./brand";
import { buildTenantTheme } from "./tenantTheme";

interface BrandingContextValue {
  branding: TenantBranding;
  /** 品牌设置页保存成功后直接应用，不必等下一次启动 */
  apply: (value: TenantBranding) => void;
  refresh: () => void;
  /** 接口失败时页面要能重试，而不是把默认品牌误当成“无权限” */
  error: boolean;
}

const BrandingContext = createContext<BrandingContextValue | null>(null);

function cacheKey(tenantId: string) {
  return `ts.branding.${tenantId}`;
}

/** 本地缓存只存呈现数据，不存权限或业务数据；按租户分键，退出后清理。 */
function readCache(tenantId: string): TenantBranding | null {
  try {
    const raw = window.localStorage.getItem(cacheKey(tenantId));
    return raw ? normalizeBranding(JSON.parse(raw)) : null;
  } catch {
    return null;
  }
}

function writeCache(tenantId: string, branding: TenantBranding) {
  try {
    window.localStorage.setItem(
      cacheKey(tenantId),
      JSON.stringify({
        seed: branding.seed,
        brand: branding.brand,
        brandHover: branding.brandHover,
        brandActive: branding.brandActive,
        brandSoft: branding.brandSoft,
        brandLine: branding.brandLine,
        brandLink: branding.brandLink,
        onBrand: branding.onBrand,
        displayName: branding.displayName,
        logoWideUrl: branding.logoWideUrl,
        logoMarkUrl: branding.logoMarkUrl,
        adjusted: branding.adjusted,
        algoVersion: branding.algoVersion,
        version: branding.version,
        canWrite: branding.canWrite,
      }),
    );
  } catch {
    // 浏览器禁用本地存储时只是首屏可能闪一次默认色，不影响功能。
  }
}

async function fetchBranding(): Promise<TenantBranding | null> {
  const response = await fetch("/api/tenant/branding", {
    credentials: "same-origin",
  });
  if (
    response.status === 401 ||
    response.status === 404 ||
    response.status === 403
  )
    return null;
  if (!response.ok) throw new Error("无法读取当前机构品牌");
  return normalizeBranding(await response.json());
}

/**
 * 租户主题入口：品牌值来自服务端，首屏先用本租户缓存避免闪回默认色。
 *
 * 平台端不引入本组件（独立构建入口），所以平台不会读到租户品牌。
 */
export function TenantBrandingProvider({
  tenantId,
  children,
}: {
  tenantId: string;
  children: ReactNode;
}) {
  const [branding, setBranding] = useState<TenantBranding>(
    () => readCache(tenantId) ?? DEFAULT_BRANDING,
  );
  const query = useQuery({
    queryKey: ["tenant-branding", tenantId],
    queryFn: fetchBranding,
    staleTime: 60_000,
    retry: false,
  });

  useEffect(() => {
    if (query.data) setBranding(query.data);
  }, [query.data]);

  useEffect(() => {
    writeCache(tenantId, branding);
  }, [branding, tenantId]);

  // 弹窗和下拉会挂到 body，变量必须放在根元素而不是壳层容器上。
  useLayoutEffect(() => {
    const root = document.documentElement;
    const variables = brandCssVariables(branding);
    for (const [name, value] of Object.entries(variables))
      root.style.setProperty(name, value);
    return () => {
      for (const name of Object.keys(variables))
        root.style.removeProperty(name);
    };
  }, [branding]);

  const value = useMemo<BrandingContextValue>(
    () => ({
      branding,
      apply: setBranding,
      refresh: () => void query.refetch(),
      error: query.isError,
    }),
    [branding, query],
  );
  const theme = useMemo(() => buildTenantTheme(branding), [branding]);

  return (
    <BrandingContext.Provider value={value}>
      <ConfigProvider theme={theme} button={{ autoInsertSpace: false }}>
        {children}
      </ConfigProvider>
    </BrandingContext.Provider>
  );
}

export function useTenantBranding() {
  const value = useContext(BrandingContext);
  if (!value)
    throw new Error("useTenantBranding 必须在 TenantBrandingProvider 内使用");
  return value;
}

/** 登录前、切换身份时清掉缓存，避免上一个租户的颜色残留。 */
export function clearBrandingCache(tenantId?: string) {
  try {
    if (tenantId) window.localStorage.removeItem(cacheKey(tenantId));
    else
      Object.keys(window.localStorage)
        .filter((key) => key.startsWith("ts.branding."))
        .forEach((key) => window.localStorage.removeItem(key));
  } catch {
    // 本地存储不可用时不阻断退出。
  }
  const root = document.documentElement;
  for (const name of [
    "--ts-brand",
    "--ts-brand-hover",
    "--ts-brand-active",
    "--ts-brand-soft",
    "--ts-brand-line",
    "--ts-brand-link",
    "--ts-on-brand",
  ])
    root.style.removeProperty(name);
}
