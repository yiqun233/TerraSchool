package org.teraschool.identity.api;

import java.util.UUID;

/**
 * 当前会话的最小呈现上下文：身份、租户名与强制改密状态。
 *
 * <p>不含权限或业务数据；权限仍由 {@link CurrentPermissions} 单独读取， 页面不能用这里的字段判断某个业务动作是否允许。
 */
public record SessionContext(
    UUID accountId,
    UUID tenantId,
    String tenantName,
    String login,
    /** 顶栏显示名；组织模块能定位到在职员工时用员工姓名，否则回登录名。 */
    String displayName,
    boolean tenantAdmin,
    boolean passwordChangeRequired) {}
