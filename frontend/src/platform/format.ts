/** 平台端共用的时间与动作展示口径：三个页面用同一份，避免同一动作在不同页叫不同名字。 */

/** 按浏览器本地时区显示到分钟；空值显示“—”，无法解析时原样返回。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (part: number) => String(part).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate(),
  )} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 审计动作码的中文名；新增动作未登记时原样显示动作码，不隐藏也不编造含义。 */
export const AUDIT_ACTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "TENANT_PROVISIONED", label: "开通租户" },
  { value: "TENANT_SUSPENDED", label: "停用租户" },
  { value: "TENANT_RESUMED", label: "恢复租户" },
  { value: "TENANT_ADMIN_PASSWORD_RESET", label: "重置管理员密码" },
  { value: "TENANT_BRANDING_UPDATE", label: "代改品牌" },
  { value: "TENANT_BRANDING_ROLLBACK", label: "回滚品牌" },
  { value: "PLATFORM_LOGIN", label: "平台登录" },
];

export function auditActionLabel(action: string): string {
  return AUDIT_ACTIONS.find((item) => item.value === action)?.label ?? action;
}

/** 开通失败的稳定错误码到可读原因；未登记的码原样显示，便于排查。 */
const PROVISIONING_ERRORS: Record<string, string> = {
  ADMIN_LOGIN_TAKEN: "管理员登录名已被占用",
  TENANT_NAME_TAKEN: "机构名称已被占用",
  IDEMPOTENCY_MISMATCH: "同一请求换了参数",
  TENANT_NAME_REQUIRED: "机构名称为空",
  ADMIN_LOGIN_REQUIRED: "管理员登录名不合规",
  INVALID_REQUEST: "提交信息不合规",
};

export function provisioningErrorLabel(code: string | null): string {
  if (!code) return "—";
  return PROVISIONING_ERRORS[code] ?? code;
}
