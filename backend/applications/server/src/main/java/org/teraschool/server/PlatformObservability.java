package org.teraschool.server;

import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 平台运营可观测：总览、开通记录、审计日志（批次 A）。
 *
 * <p>三段查询都是只读的：读 {@code platform.*} 与 {@code identity.tenant} 的最小字段。平台角色对
 * teaching/enrollment/funds/archive 仍然一张表都没有授权，所以这里既不需要、也不可能拼进业务表。 不接受任意
 * SQL、表名或排序字段；过滤与分页条件都来自固定片段。
 */
@Component
class PlatformObservability {
  /** 与租户列表一致：单页上限 100，避免一次拉全表。 */
  static final int MAX_PAGE_SIZE = 100;

  private static final Set<String> PROVISIONING_STATUSES = Set.of("ACTIVE", "FAILED");

  private final JdbcTemplate db;
  private final TenantProvisioning tenants;

  PlatformObservability(
      @Qualifier("platformDataSource") DataSource source, TenantProvisioning tenants) {
    this.db = new JdbcTemplate(source);
    this.tenants = tenants;
  }

  /** 总览：租户状态计数 + 跨租户规模 + 最近记录。 */
  Map<String, Object> overview(boolean includeProvisioning, boolean includeAudit) {
    var counts = tenants.counts();
    var totals =
        db.queryForObject(
            "select schools, accounts from platform.tenant_totals()",
            (rs, index) -> new long[] {rs.getLong(1), rs.getLong(2)});
    List<Map<String, Object>> recentProvisioning =
        includeProvisioning ? provisioningRequests(0, 5, "", "") : List.of();
    List<Map<String, Object>> recentAudit =
        includeAudit ? auditEvents(0, 5, "", null, null, null, null) : List.of();

    var body = new LinkedHashMap<String, Object>();
    body.put(
        "tenants",
        Map.of(
            "total", counts.total(),
            "active", counts.active(),
            "suspended", counts.suspended(),
            "thisMonth", counts.thisMonth()));
    body.put("schools", totals[0]);
    body.put("accounts", totals[1]);
    body.put("recentProvisioning", recentProvisioning);
    body.put("recentAudit", recentAudit);
    return body;
  }

  static void validatePaging(int page, int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
      throw new IllegalArgumentException("分页参数需为 page>=0、1<=size<=100");
  }

  /** 过滤条件片段与参数分开，避免把用户输入拼进 SQL。 */
  private record Filter(String where, List<Object> args) {}

  // ---------- 开通记录 ----------

  private static String provisioningStatus(String status) {
    var clean = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
    if (!clean.isEmpty() && !PROVISIONING_STATUSES.contains(clean))
      throw new IllegalArgumentException("开通状态只支持 ACTIVE、FAILED");
    return clean;
  }

  private static String provisioningKeyword(String keyword) {
    var clean = keyword == null ? "" : keyword.strip();
    if (clean.length() > 100) throw new IllegalArgumentException("关键字不能超过 100 字符");
    return clean;
  }

  private Filter provisioningFilter(String status, String keyword) {
    var cleanStatus = provisioningStatus(status);
    var cleanKeyword = provisioningKeyword(keyword);
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    if (!cleanStatus.isEmpty()) {
      where.append(" and r.status = ?");
      args.add(cleanStatus);
    }
    if (!cleanKeyword.isEmpty()) {
      // 机构名与管理员登录名都可搜：同一行信息，不区分“按哪个字段找”。
      where.append(" and (r.tenant_name ilike ? or r.admin_login ilike ?)");
      args.add("%" + cleanKeyword + "%");
      args.add("%" + cleanKeyword + "%");
    }
    return new Filter(where.toString(), args);
  }

  /** 开通记录：成功与失败都列出，失败带稳定错误码。 */
  List<Map<String, Object>> provisioningRequests(
      int page, int size, String status, String keyword) {
    validatePaging(page, size);
    var filter = provisioningFilter(status, keyword);
    var args = new ArrayList<>(filter.args());
    args.add(size);
    args.add(page * size);
    return db.query(
        "select r.created_at, r.finished_at, r.status, r.error_code, r.tenant_id,"
            + " r.tenant_name, r.admin_login,"
            + " o.login as operator_login, o.display_name as operator_name"
            + " from platform.provisioning_request r"
            + " join platform.operator o on o.id = r.operator_id"
            + filter.where()
            + " order by r.created_at desc, r.id desc limit ? offset ?",
        (rs, index) -> {
          var row = new LinkedHashMap<String, Object>();
          row.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
          row.put("finished_at", rs.getObject("finished_at", OffsetDateTime.class));
          row.put("status", rs.getString("status"));
          row.put("error_code", rs.getString("error_code"));
          row.put("tenant_id", rs.getObject("tenant_id", UUID.class));
          row.put("tenant_name", rs.getString("tenant_name"));
          row.put("admin_login", rs.getString("admin_login"));
          row.put("operator_login", rs.getString("operator_login"));
          row.put("operator_name", rs.getString("operator_name"));
          return row;
        },
        args.toArray());
  }

  long countProvisioningRequests(String status, String keyword) {
    var filter = provisioningFilter(status, keyword);
    return db.queryForObject(
        "select count(*) from platform.provisioning_request r" + filter.where(),
        Long.class,
        filter.args().toArray());
  }

  // ---------- 审计日志 ----------

  private Filter auditFilter(
      String action, UUID tenantId, String operatorLogin, OffsetDateTime from, OffsetDateTime to) {
    var cleanAction = action == null ? "" : action.strip();
    if (cleanAction.length() > 60) throw new IllegalArgumentException("动作码不能超过 60 字符");
    var cleanOperator = operatorLogin == null ? "" : operatorLogin.strip();
    if (cleanOperator.length() > 60) throw new IllegalArgumentException("操作者登录名不能超过 60 字符");
    if (from != null && to != null && from.isAfter(to))
      throw new IllegalArgumentException("开始时间不能晚于结束时间");

    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    if (!cleanAction.isEmpty()) {
      where.append(" and e.action = ?");
      args.add(cleanAction);
    }
    if (tenantId != null) {
      where.append(" and e.tenant_id = ?");
      args.add(tenantId);
    }
    if (!cleanOperator.isEmpty()) {
      where.append(" and o.login = ?");
      args.add(cleanOperator);
    }
    if (from != null) {
      where.append(" and e.happened_at >= ?");
      args.add(from);
    }
    if (to != null) {
      where.append(" and e.happened_at <= ?");
      args.add(to);
    }
    return new Filter(where.toString(), args);
  }

  /**
   * 审计事件：只读展示层字段，不含任何凭据或业务数据。
   *
   * <p>租户名来自 {@code identity.tenant} 的名称列；目标租户为空的事件（如平台登录）保留空值。
   */
  List<Map<String, Object>> auditEvents(
      int page,
      int size,
      String action,
      UUID tenantId,
      String operatorLogin,
      OffsetDateTime from,
      OffsetDateTime to) {
    validatePaging(page, size);
    var filter = auditFilter(action, tenantId, operatorLogin, from, to);
    var args = new ArrayList<>(filter.args());
    args.add(size);
    args.add(page * size);
    return db.query(
        "select e.happened_at, e.action, e.tenant_id, t.name as tenant_name,"
            + " e.changed_fields, e.trace_id,"
            + " o.login as operator_login, o.display_name as operator_name"
            + " from platform.audit_event e"
            + " join platform.operator o on o.id = e.operator_id"
            + " left join identity.tenant t on t.id = e.tenant_id"
            + filter.where()
            + " order by e.happened_at desc, e.id desc limit ? offset ?",
        (rs, index) -> {
          var row = new LinkedHashMap<String, Object>();
          row.put("happened_at", rs.getObject("happened_at", OffsetDateTime.class));
          row.put("action", rs.getString("action"));
          row.put("tenant_id", rs.getObject("tenant_id", UUID.class));
          row.put("tenant_name", rs.getString("tenant_name"));
          row.put("changed_fields", rs.getString("changed_fields"));
          row.put("trace_id", rs.getObject("trace_id", UUID.class));
          row.put("operator_login", rs.getString("operator_login"));
          row.put("operator_name", rs.getString("operator_name"));
          return row;
        },
        args.toArray());
  }

  long countAuditEvents(
      String action, UUID tenantId, String operatorLogin, OffsetDateTime from, OffsetDateTime to) {
    var filter = auditFilter(action, tenantId, operatorLogin, from, to);
    return db.queryForObject(
        "select count(*)"
            + " from platform.audit_event e"
            + " join platform.operator o on o.id = e.operator_id"
            + filter.where(),
        Long.class,
        filter.args().toArray());
  }
}
