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

  /** 一次快照取得查看、维护与敏感字段范围；不得在同一请求内拼接多次读取。 */
  ResourceScope requireResourceScope(String kind);

  /** 事务内复核，用于写入前重新确认权限未被撤销。 */
  ResourceScope requireResourceScopeInTransaction(String kind);

  /** 单独的功能动作（例如发布定价），返回该动作的学校范围；不与普通维护范围合并。 */
  Actor requireAction(String action);

  /** 事务内复核同一动作，用于写入前重新确认。 */
  Actor requireActionInTransaction(String action);

  java.util.Optional<AuditSubject> auditSubject();
}
