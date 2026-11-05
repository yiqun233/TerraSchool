package org.teraschool.server;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/**
 * Optional background sweep returning stale private customers to their school's public pool. Rules
 * intentionally remain disabled by default until a school confirms its legacy settings.
 */
@Component
class CustomerAutoRecycleJob {
  private static final Logger log = LoggerFactory.getLogger(CustomerAutoRecycleJob.class);
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate identity;
  private final DSLContext sql;
  private final TenantTransactions tx;
  private final boolean enabled;
  private final int inactiveDays;
  private final boolean assignmentAgeEnabled;
  private final int assignmentAgeDays;
  private final boolean firstDayNoContactEnabled;
  private final LocalTime firstDayCutoff;

  CustomerAutoRecycleJob(
      @Qualifier("identityDataSource") DataSource identityDataSource,
      DSLContext sql,
      TenantTransactions tx,
      Environment environment) {
    identity = new JdbcTemplate(identityDataSource);
    this.sql = sql;
    this.tx = tx;
    enabled =
        environment.getProperty("teraschool.admissions.auto-recycle.enabled", Boolean.class, false);
    inactiveDays = boundedDays(environment, "inactive-days", 7);
    assignmentAgeEnabled =
        environment.getProperty(
            "teraschool.admissions.auto-recycle.assignment-age-enabled", Boolean.class, false);
    assignmentAgeDays = boundedDays(environment, "assignment-age-days", 15);
    firstDayNoContactEnabled =
        environment.getProperty(
            "teraschool.admissions.auto-recycle.first-day-no-contact-enabled",
            Boolean.class,
            false);
    firstDayCutoff =
        LocalTime.parse(
            environment.getProperty(
                "teraschool.admissions.auto-recycle.first-day-cutoff", "23:59"));
  }

  private int boundedDays(Environment environment, String name, int fallback) {
    int days =
        environment.getProperty(
            "teraschool.admissions.auto-recycle." + name, Integer.class, fallback);
    if (days < 1 || days > 365) throw new IllegalArgumentException(name + " 必须在 1 到 365 天之间");
    return days;
  }

  @Scheduled(fixedDelayString = "${teraschool.admissions.auto-recycle.interval-ms:60000}")
  void scheduledSweep() {
    if (!enabled) return;
    List<UUID> tenants =
        identity.query(
            "select id from identity.tenant where active order by id",
            (rs, row) -> rs.getObject(1, UUID.class));
    for (UUID tenantId : tenants) {
      try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
        int count = sweepTenant(tenantId);
        if (count > 0)
          log.info("Customer auto-recycle completed tenant={} count={}", tenantId, count);
      } catch (RuntimeException failure) {
        log.error("Customer auto-recycle failed tenant={}", tenantId, failure);
      }
    }
  }

  int sweepTenant(UUID tenantId) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return tx.execute(tenantId, () -> recycleTenantSchools(tenantId));
    }
  }

  private int recycleTenantSchools(UUID tenantId) {
    var schools =
        sql.fetch(
                "select id from organization.unit where tenant_id=? and kind='SCHOOL' and active order by id",
                tenantId)
            .map(row -> row.get("id", UUID.class));
    int total = 0;
    for (UUID schoolId : schools) total += recycleSchool(tenantId, schoolId);
    return total;
  }

  private int recycleSchool(UUID tenantId, UUID schoolId) {
    var now = java.time.OffsetDateTime.now(BUSINESS_ZONE);
    var today = LocalDate.now(BUSINESS_ZONE);
    var inactivityBoundary = today.minusDays(inactiveDays);
    var assignmentBoundary = today.minusDays(assignmentAgeDays);
    var rows =
        sql.fetch(
            "select c.id,c.version,case "
                + "when ? and c.owner_assigned_at<=?::timestamptz and not exists (select 1 from admissions.customer_followup f where f.customer_id=c.id and f.occurred_on>?::date) then 'INACTIVITY' "
                + "when ? and c.owner_assigned_at<=?::timestamptz then 'ASSIGNMENT_AGE' "
                + "else 'FIRST_DAY_NO_CONTACT' end as reason "
                + "from admissions.customer c where c.tenant_id=? and c.school_id=? and c.owner_id is not null "
                + "and not c.in_pool and c.merged_into_id is null "
                + "and not exists (select 1 from admissions.customer child where child.merged_into_id=c.id) "
                + "and not exists (select 1 from admissions.customer_student_link l where l.customer_id=c.id) "
                + "and ((c.owner_assigned_at<=?::timestamptz and not exists (select 1 from admissions.customer_followup f where f.customer_id=c.id and f.occurred_on>?::date)) "
                + "or (? and c.owner_assigned_at<=?::timestamptz) "
                + "or (? and c.owner_id<>c.creator_id and (c.owner_assigned_at at time zone 'Asia/Shanghai')::date<=?::date and "
                + "(((c.owner_assigned_at at time zone 'Asia/Shanghai')::date<?::date) or "
                + "((c.owner_assigned_at at time zone 'Asia/Shanghai')::date=?::date and ?)) "
                + "and not exists (select 1 from admissions.customer_followup f where f.customer_id=c.id and f.created_at>=c.owner_assigned_at))) "
                + "order by c.owner_assigned_at,c.id for update of c skip locked limit 200",
            true,
            now.minusDays(inactiveDays),
            inactivityBoundary,
            assignmentAgeEnabled,
            now.minusDays(assignmentAgeDays),
            tenantId,
            schoolId,
            now.minusDays(inactiveDays),
            inactivityBoundary,
            assignmentAgeEnabled,
            now.minusDays(assignmentAgeDays),
            firstDayNoContactEnabled,
            today,
            today,
            today,
            !now.toLocalTime().isBefore(firstDayCutoff));
    for (var row : rows) {
      var customerId = row.get("id", UUID.class);
      var version = row.get("version", Long.class);
      var reason = row.get("reason", String.class);
      if (sql.execute(
              "update admissions.customer set owner_id=null,in_pool=true,version=version+1 "
                  + "where tenant_id=? and id=? and version=? and owner_id is not null and in_pool=false and merged_into_id is null",
              tenantId,
              customerId,
              version)
          != 1) continue;
      sql.execute(
          "insert into audit.customer_event(tenant_id,actor_id,actor_type,school_id,customer_id,action,detail,trace_id) "
              + "values (?,null,'SYSTEM',?,?,'AUTO_RECYCLED',?,?)",
          tenantId,
          schoolId,
          customerId,
          reason,
          RequestTrace.id());
    }
    return rows.size();
  }
}
