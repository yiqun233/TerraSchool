import type { ThemeConfig } from "antd";
import { baseTheme } from "./baseTheme";
import type { TenantBranding } from "./brand";

/**
 * 把服务端品牌令牌合并到骨架主题。
 *
 * 只允许改品牌相关 token；密度、字号、圆角、语义色仍来自 baseTheme，
 * 租户不能通过品牌配置改变骨架。
 */
export function buildTenantTheme(branding: TenantBranding): ThemeConfig {
  return {
    ...baseTheme,
    token: {
      ...baseTheme.token,
      colorPrimary: branding.brand,
      colorPrimaryHover: branding.brandHover,
      colorPrimaryActive: branding.brandActive,
      colorPrimaryBg: branding.brandSoft,
      colorPrimaryBorder: branding.brandLine,
      colorLink: branding.brandLink,
      colorLinkHover: branding.brandHover,
      colorLinkActive: branding.brandActive,
      colorTextLightSolid: branding.onBrand,
    },
    components: {
      ...baseTheme.components,
      Table: {
        ...baseTheme.components?.Table,
        rowSelectedBg: branding.brandSoft,
        rowSelectedHoverBg: branding.brandSoft,
      },
      Input: {
        activeShadow: `0 0 0 3px ${branding.brandSoft}`,
      },
      Select: {
        activeOutlineColor: branding.brandSoft,
      },
    },
  };
}
