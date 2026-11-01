package org.teraschool.identity.api;

public interface CurrentActor {
  CurrentPermissions permissions();

  /** 使用当前业务事务连接复核会话，不隐含任何业务范围。 */
  Actor requireIdentityInTransaction();

  Actor requireOrganizationReader();

  Actor requireOrganizationMaintainer();

  Actor requireOrganizationMaintainerInTransaction();

  /** 指定资源各自的查看/维护动作；维护范围与该资源查看范围相交。 */
  Actor requireResource(String kind, boolean maintenance);

  /** 在已设置租户的业务事务中读取同一连接，调用方须先取得机构授权锁。 */
  Actor requireResourceInTransaction(String kind, boolean maintenance);

  java.util.Optional<AuditSubject> auditSubject();
}
