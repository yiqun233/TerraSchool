package org.teraschool.identity.api;

import java.util.UUID;

/** 已认证主体在持久账号中的归属；仅供审计，不能作为业务授权结果。 */
public record AuditSubject(UUID accountId, UUID tenantId) {}
