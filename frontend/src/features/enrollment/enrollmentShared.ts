import type { components } from "../../shared/generated/api";

// 工作台与表单共用同一份类型与状态文案，避免两处各写一份后慢慢分叉。
export type EnrollmentView = components["schemas"]["EnrollmentView"];
export type StudentSummary = components["schemas"]["StudentSummary"];
export type Receipt = components["schemas"]["Receipt"];
export type Allocation = components["schemas"]["Allocation"];
export type FundsCapabilities = components["schemas"]["FundsCapabilities"];

export const STATUS_LABEL: Record<string, string> = {
  PENDING_AUDIT: "待审核",
  ACTIVE: "已生效",
};
