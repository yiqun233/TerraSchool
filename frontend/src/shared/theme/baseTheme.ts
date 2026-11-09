import type { ThemeConfig } from "antd";

/**
 * L0 骨架的 antd 映射：只放中性色、密度与字体。
 *
 * 品牌色由调用方合并进来：平台端用固定色，租户端用服务端下发的派生色。
 * 这里不写 colorPrimary，避免任何一端的品牌值渗进共享层。
 */
export const baseTheme: ThemeConfig = {
  cssVar: { prefix: "ts" },
  token: {
    colorBgLayout: "#f7f8fa",
    colorBgContainer: "#ffffff",
    colorBgElevated: "#ffffff",
    colorBorder: "#e5e7eb",
    colorBorderSecondary: "#f0f1f3",
    colorText: "#1f2937",
    colorTextSecondary: "#475467",
    colorTextTertiary: "#6b7280",
    colorTextQuaternary: "#9ca3af",
    colorSuccess: "#067647",
    colorWarning: "#b54708",
    colorError: "#b42318",
    colorInfo: "#175cd3",
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
      borderColor: "#f0f1f3",
      cellPaddingBlock: 10,
      cellPaddingInline: 16,
    },
    Button: {
      primaryShadow: "none",
      defaultShadow: "none",
      dangerShadow: "none",
    },
    Card: { paddingLG: 16 },
    Modal: { paddingContentHorizontalLG: 24 },
    Drawer: { paddingLG: 24 },
  },
};
