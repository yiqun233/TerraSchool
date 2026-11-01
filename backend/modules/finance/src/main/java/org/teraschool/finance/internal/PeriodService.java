package org.teraschool.finance.internal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.finance.api.*;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/**
 * 财务期间与锁月。
 *
 * <p>首版口径：自然月，按业务事实的业务日期归属；未登记的月份视为开放。锁定后该月的收款、分配、退费与 课次消费一律拒绝写入；解锁需要版本并记录原因，两次操作都写审计。
 */
@Service
public class PeriodService implements PeriodQueries, PeriodCommands, PeriodGate {
  private static final Pattern YEAR = Pattern.compile("\\d{4}");
  private static final Pattern PERIOD = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;

  public PeriodService(
      CurrentActor actors, TenantTransactions tx, DSLContext sql, SchoolAccess schools) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
  }

  // ---------------------------------------------------------------- 查询

  @Override
  public FinanceCapabilities capabilities() {
    var scope = actors.requireResourceScope("PERIOD");
    var lock = schoolsOf("PERIOD_LOCK");
    if (scope.readableSchoolIds().isEmpty()) return new FinanceCapabilities(true, false, List.of());
    return tx.execute(
        scope.tenantId(),
        () -> {
          var names = schools.names(scope.readableSchoolIds());
          var options =
              scope.readableSchoolIds().stream()
                  .filter(names::containsKey)
                  .sorted(
                      Comparator.comparing((UUID key) -> names.get(key)).thenComparing(key -> key))
                  .map(key -> new FinanceSchoolOption(key, names.get(key), lock.contains(key)))
                  .toList();
          return new FinanceCapabilities(
              true, options.stream().anyMatch(FinanceSchoolOption::canLock), options);
        });
  }

  @Override
  public List<PeriodView> periods(UUID schoolId, String year) {
    var scope = actors.requireResourceScope("PERIOD");
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的期间读取权限");
    var target =
        year == null || year.isBlank() ? String.valueOf(LocalDate.now().getYear()) : year.strip();
    if (!YEAR.matcher(target).matches()) throw new IllegalArgumentException("年份须为 YYYY");
    return tx.execute(
        scope.tenantId(),
        () -> {
          Map<String, Record> stored = new HashMap<>();
          for (var row :
              sql.fetch(
                  "select id,period,status,locked_at,locked_by,reason,version from finance.period"
                      + " where school_id=? and period like ?",
                  schoolId,
                  target + "-%")) stored.put(row.get("period", String.class), row);
          var result = new ArrayList<PeriodView>();
          for (var month = 1; month <= 12; month++) {
            var key = String.format(Locale.ROOT, "%s-%02d", target, month);
            var row = stored.get(key);
            if (row == null) result.add(new PeriodView(schoolId, key, "OPEN", null, null, null, 0));
            else result.add(view(row, schoolId));
          }
          return result;
        });
  }

  // ---------------------------------------------------------------- 锁月

  @Override
  public PeriodView lock(UUID schoolId, LockPeriod command) {
    return change(schoolId, command, true);
  }

  @Override
  public PeriodView unlock(UUID schoolId, LockPeriod command) {
    return change(schoolId, command, false);
  }

  private PeriodView change(UUID ignored, LockPeriod command, boolean lock) {
    if (command == null || command.schoolId() == null || command.period() == null)
      throw new IllegalArgumentException("需要学校和期间");
    var schoolId = command.schoolId();
    var period = period(command.period());
    var reason = text(command.reason(), 200, "原因");
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("PERIOD_LOCK");
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            var verified = actors.requireActionInTransaction("PERIOD_LOCK");
            if (!verified.schools().contains(schoolId))
              throw new AccessDeniedException("无该学校的锁月权限");
            if (!schools.require(schoolId).active())
              throw new AccessDeniedException("学校已停用，不能变更财务期间");
            var existing =
                sql.fetchOne(
                    "select id,period,status,version,locked_at,locked_by,reason from finance.period"
                        + " where school_id=? and period=? for update",
                    schoolId,
                    period);
            if (lock) {
              if (existing != null && "LOCKED".equals(existing.get("status", String.class)))
                return view(existing, schoolId);
              if (existing == null) {
                var id = UUID.randomUUID();
                sql.execute(
                    "insert into finance.period(id,tenant_id,school_id,period,status,locked_at,locked_by,reason)"
                        + " values (?,?,?,?, 'LOCKED',CURRENT_TIMESTAMP,?,?)",
                    id,
                    actor.tenantId(),
                    schoolId,
                    period,
                    actor.accountId(),
                    reason);
                audit(actor, "PERIOD_LOCKED", id, schoolId, period, reason);
                return view(requirePeriod(schoolId, period), schoolId);
              }
              sql.execute(
                  "update finance.period set status='LOCKED',locked_at=CURRENT_TIMESTAMP,locked_by=?,"
                      + "reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id=?",
                  actor.accountId(),
                  reason,
                  existing.get("id", UUID.class));
              audit(
                  actor, "PERIOD_LOCKED", existing.get("id", UUID.class), schoolId, period, reason);
              return view(requirePeriod(schoolId, period), schoolId);
            }
            if (existing == null || !"LOCKED".equals(existing.get("status", String.class)))
              throw new PeriodConflict("PERIOD_NOT_LOCKED");
            if (command.expectedVersion() == null
                || !existing.get("version", Long.class).equals(command.expectedVersion()))
              throw new PeriodConflict("VERSION_CONFLICT");
            sql.execute(
                "update finance.period set status='OPEN',locked_at=null,locked_by=null,reason=?,"
                    + "version=version+1,updated_at=CURRENT_TIMESTAMP where id=?",
                reason,
                existing.get("id", UUID.class));
            audit(
                actor, "PERIOD_UNLOCKED", existing.get("id", UUID.class), schoolId, period, reason);
            return view(requirePeriod(schoolId, period), schoolId);
          });
    }
  }

  // ---------------------------------------------------------------- 闸门

  @Override
  public void requireOpen(UUID schoolId, LocalDate businessDate) {
    if (schoolId == null || businessDate == null) return;
    var period = YearMonth.from(businessDate).toString();
    var row =
        sql.fetchOne(
            "select status from finance.period where school_id=? and period=?", schoolId, period);
    if (row != null && "LOCKED".equals(row.get("status", String.class)))
      throw new PeriodConflict("PERIOD_LOCKED");
  }

  // ---------------------------------------------------------------- 内部

  private Set<UUID> schoolsOf(String action) {
    try {
      return actors.requireAction(action).schools();
    } catch (AccessDeniedException denied) {
      return Set.of();
    }
  }

  private Record requirePeriod(UUID schoolId, String period) {
    var row =
        sql.fetchOne(
            "select id,period,status,locked_at,locked_by,reason,version from finance.period"
                + " where school_id=? and period=?",
            schoolId,
            period);
    if (row == null) throw new IllegalStateException("期间写入后不可见");
    return row;
  }

  private PeriodView view(Record row, UUID schoolId) {
    var lockedAt = row.get("locked_at", java.time.OffsetDateTime.class);
    var lockedBy = row.get("locked_by", UUID.class);
    return new PeriodView(
        schoolId,
        row.get("period", String.class),
        row.get("status", String.class),
        lockedAt == null ? null : lockedAt.toInstant().toString(),
        lockedBy == null ? null : lockedBy.toString(),
        row.get("reason", String.class),
        row.get("version", Long.class));
  }

  private String period(String value) {
    var text = value == null ? null : value.strip();
    if (text == null || !PERIOD.matcher(text).matches())
      throw new IllegalArgumentException("期间须为 YYYY-MM");
    try {
      YearMonth.parse(text);
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("期间须为 YYYY-MM");
    }
    return text;
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
  }

  private void audit(
      Actor actor, String action, UUID resource, UUID school, String period, String reason) {
    sql.execute(
        "insert into audit.period_event(tenant_id,actor_id,action,resource_id,school_id,period,reason,"
            + "trace_id) values (?,?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        action,
        resource,
        school,
        period,
        reason,
        RequestTrace.id());
  }

  /** 供测试与其他模块复用的期间计算；与数据库存储格式保持一致。 */
  public static String periodOf(LocalDate date) {
    return YearMonth.from(date).toString();
  }

  /** 期间列表与当前日期无关，保留以方便调用方判断默认年份。 */
  public static String currentYear() {
    return String.valueOf(LocalDate.now().getYear());
  }
}
