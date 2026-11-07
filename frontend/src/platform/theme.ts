import type { ThemeConfig } from "antd";

/**
 * 平台端固定主题：不读任何租户品牌配置（DEC-025 第 4 条）。
 * 颜色、字号只在这里定义，页面组件不得写死色值。
 */
export const platformTheme: ThemeConfig = {
  token: {
    colorPrimary: "#254c78",
    colorLink: "#254c78",
    colorInfo: "#175cd3",
    colorSuccess: "#067647",
    colorWarning: "#b54708",
    colorError: "#b42318",
    colorText: "#1f2937",
    colorTextSecondary: "#475467",
    colorTextTertiary: "#6b7280",
    colorBorder: "#e5e7eb",
    colorBorderSecondary: "#f0f1f3",
    colorBgLayout: "#f7f8fa",
    borderRadius: 6,
    borderRadiusLG: 8,
    fontSize: 14,
    controlHeight: 32,
    fontFamily:
      "system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif",
  },
  components: {
    Table: {
      headerBg: "#fafbfc",
      headerColor: "#6b7280",
      headerSplitColor: "transparent",
      rowHoverBg: "#f9fafb",
      cellPaddingBlock: 10,
      cellPaddingInline: 16,
    },
    Button: { primaryShadow: "none", defaultShadow: "none" },
    Input: { activeShadow: "0 0 0 3px rgba(37,76,120,0.12)" },
    Card: { paddingLG: 16 },
  },
};
