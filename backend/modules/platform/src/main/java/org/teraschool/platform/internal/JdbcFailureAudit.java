package org.teraschool.platform.internal;

import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.teraschool.platform.api.*;

/** 原始独立连接自动提交，不加入调用者的Spring事务，也不占用业务连接池。 */
@Component
public class JdbcFailureAudit implements FailureAudit {
  private final DataSource source;

  public JdbcFailureAudit(@Qualifier("auditDataSource") DataSource source) {
    this.source = source;
  }

  @Override
  public void append(Entry e) {
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(true);
      try (var statement =
          connection.prepareStatement(
              "insert into audit.operation_failure(attempt_id,trace_id,tenant_id,actor_id,action,source,result,code,resource_id) values (?,?,?,?,?,?,?,?,?)")) {
        statement.setQueryTimeout(2);
        statement.setObject(1, e.attemptId());
        statement.setObject(2, e.traceId());
        statement.setObject(3, e.tenantId());
        statement.setObject(4, e.actorId());
        statement.setString(5, e.action().name());
        statement.setString(6, e.source().name());
        statement.setString(7, e.result().name());
        statement.setString(8, e.code());
        statement.setObject(9, e.resourceId());
        statement.executeUpdate();
      }
    } catch (java.sql.SQLException error) {
      // 不打印SQL参数或异常原文；失败不能伪称已持久化，不无限重试。
      LoggerFactory.getLogger(getClass())
          .error(
              "AUDIT_WRITE_FAILED trace={} code={} sqlstate={}",
              e.traceId(),
              e.code(),
              error.getSQLState());
      throw new AuditUnavailable();
    }
  }
}
