import type { ThemeConfig } from "antd";
import { baseTheme } from "../shared/theme/baseTheme";

/**
 * 平台端固定主题：不读任何租户品牌配置（DEC-025 第 4 条）。
 *
 * 只覆盖品牌色，密度、字号、圆角与语义色沿用共享骨架；
 * 页面组件不得写死色值。
 */
export const platformTheme: ThemeConfig = {
  ...baseTheme,
  token: {
    ...baseTheme.token,
    colorPrimary: "#254c78",
    colorPrimaryHover: "#1b3859",
    colorPrimaryActive: "#142941",
    colorPrimaryBg: "#eff4fa",
    colorPrimaryBorder: "#d0dff1",
    colorLink: "#254c78",
    colorLinkHover: "#1b3859",
    colorLinkActive: "#142941",
    colorTextLightSolid: "#ffffff",
  },
  components: {
    ...baseTheme.components,
    Input: {
      activeShadow: "0 0 0 3px #eff4fa",
    },
  },
};
