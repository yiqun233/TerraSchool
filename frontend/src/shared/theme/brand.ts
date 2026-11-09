/**
 * 品牌令牌的前端视图。
 *
 * 派生算法在服务端执行（多租户品牌主题方案第 5.3 节），前端只消费结果；
 * 这里保留一份默认深蓝，用于登录前、品牌接口不可用或旧租户尚未初始化的场景。
 */

export interface BrandTokens {
  /** 预设 seed，同时作为服务端派生与审计依据 */
  seed: string;
  brand: string;
  brandHover: string;
  brandActive: string;
  brandSoft: string;
  brandLine: string;
  brandLink: string;
  onBrand: string;
}

export interface BrandPreset {
  name: string;
  seed: string;
}

export interface TenantBranding extends BrandTokens {
  displayName: string | null;
  logoWideUrl: string | null;
  logoMarkUrl: string | null;
  adjusted: boolean;
  algoVersion: number;
  version: number;
  canWrite: boolean;
  presets: BrandPreset[];
}

/** 与多租户品牌主题方案第 6 节保持同一组预设。 */
export const BRAND_PRESETS: BrandPreset[] = [
  { name: "深蓝", seed: "#254c78" },
  { name: "靛蓝", seed: "#3b4e9b" },
  { name: "青", seed: "#0f6e6e" },
  { name: "墨绿", seed: "#1f5b4a" },
  { name: "石墨", seed: "#3d4453" },
  { name: "暖棕", seed: "#6b4a2f" },
];

export const DEFAULT_BRANDING: TenantBranding = {
  seed: "#254c78",
  brand: "#254c78",
  brandHover: "#1b3859",
  brandActive: "#142941",
  brandSoft: "#eff4fa",
  brandLine: "#d0dff1",
  brandLink: "#254c78",
  onBrand: "#ffffff",
  displayName: null,
  logoWideUrl: null,
  logoMarkUrl: null,
  adjusted: false,
  algoVersion: 1,
  version: 0,
  canWrite: false,
  presets: BRAND_PRESETS,
};

/** 把品牌令牌映射为根元素变量；CSS Modules 与 antd 浮层都从根上读取。 */
export function brandCssVariables(branding: TenantBranding) {
  return {
    "--ts-brand": branding.brand,
    "--ts-brand-hover": branding.brandHover,
    "--ts-brand-active": branding.brandActive,
    "--ts-brand-soft": branding.brandSoft,
    "--ts-brand-line": branding.brandLine,
    "--ts-brand-link": branding.brandLink,
    "--ts-on-brand": branding.onBrand,
  } as Record<string, string>;
}

/** 服务端返回值与默认值合并，兼容尚未下发新字段的旧接口。 */
export function normalizeBranding(
  value: Partial<TenantBranding> | null | undefined,
): TenantBranding {
  if (!value) return DEFAULT_BRANDING;
  return {
    ...DEFAULT_BRANDING,
    ...value,
    presets: value.presets?.length ? value.presets : BRAND_PRESETS,
  };
}
