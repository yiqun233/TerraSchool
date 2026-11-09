package org.teraschool.organization.internal;

import static org.teraschool.organization.internal.generated.tables.Unit.UNIT;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.*;
import org.teraschool.platform.api.TenantTransactions;

/** 先核对当前权限和学校范围再计数/分页；校区与部门均直属学校。 */
@Service
public class OrganizationService implements OrganizationQueries {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final EmployeeAccountLinkService employeeLinks;

  public OrganizationService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      EmployeeAccountLinkService employeeLinks) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.employeeLinks = employeeLinks;
  }

  public OrganizationPage list(int page, int size) {
    var actor = actors.requireOrganizationReader();
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("分页超出范围");
    if (actor.schools().isEmpty()) return new OrganizationPage(java.util.List.of(), page, size, 0);
    return tx.execute(
        actor.tenantId(),
        () -> {
          var table = UNIT;
          var school = UNIT.SCHOOL_ID;
          var condition = school.in(actor.schools());
          long total = sql.fetchCount(table, condition);
          var rows =
              sql.select()
                  .from(table)
                  .where(condition)
                  .orderBy(UNIT.ID)
                  .limit(size)
                  .offset(page * size)
                  .fetch(
                      r ->
                          new OrganizationUnit(
                              r.get("id", java.util.UUID.class),
                              r.get("school_id", java.util.UUID.class),
                              r.get("kind", String.class),
                              r.get("name", String.class),
                              r.get("version", Long.class),
                              r.get("active", Boolean.class),
                              r.get("short_name", String.class),
                              r.get("address", String.class),
                              r.get("postal_code", String.class),
                              r.get("bus_routes", String.class)));
          return new OrganizationPage(rows, page, size, total);
        });
  }

  @Override
  public String employeeDisplayName(java.util.UUID accountId) {
    if (accountId == null) return null;
    var permissions = actors.permissions();
    return tx.execute(
        permissions.tenantId(),
        () -> {
          var row =
              sql.fetchOne(
                  "select name from organization.employee"
                      + " where tenant_id=? and account_id=? and active"
                      + " order by name,id limit 1",
                  permissions.tenantId(),
                  accountId);
          return row == null ? null : row.get("name", String.class);
        });
  }

  @Override
  public java.util.List<OrganizationUnit> schoolOptions(String search) {
    var actor = actors.requireOrganizationMaintainer();
    if (search == null || search.length() > 100) throw new IllegalArgumentException("学校检索条件过长");
    return tx.execute(
        actor.tenantId(),
        () ->
            sql.selectFrom(UNIT)
                .where(
                    UNIT.ID
                        .in(actor.schools())
                        .and(UNIT.KIND.eq("SCHOOL"))
                        .and(UNIT.ACTIVE.eq(true))
                        .and(UNIT.NAME.containsIgnoreCase(search.strip())))
                .orderBy(UNIT.NAME, UNIT.ID)
                .limit(20)
                .fetch(
                    r ->
                        new OrganizationUnit(
                            r.getId(), r.getSchoolId(), r.getKind(), r.getName(), r.getVersion())));
  }

  @Override
  public java.util.List<OrganizationQueries.EmployeeAssignmentOption> customerAssignmentOptions(
      java.util.UUID schoolId) {
    return employeeLinks.customerAssignmentOptions(schoolId);
  }

  @Override
  public boolean isActiveCustomerAssignee(java.util.UUID schoolId, java.util.UUID accountId) {
    return employeeLinks.isActiveCustomerAssignee(schoolId, accountId);
  }

  @Override
  public java.util.List<OrganizationQueries.EmployeeAssignmentOption> studentAssignmentOptions(
      java.util.UUID schoolId) {
    return employeeLinks.studentAssignmentOptions(schoolId);
  }

  @Override
  public boolean isActiveStudentAssignee(java.util.UUID schoolId, java.util.UUID accountId) {
    return employeeLinks.isActiveStudentAssignee(schoolId, accountId);
  }
}
