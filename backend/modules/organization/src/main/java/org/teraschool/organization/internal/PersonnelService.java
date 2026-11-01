package org.teraschool.organization.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.organization.api.OrganizationConflict;
import org.teraschool.organization.api.PersonnelManagement.*;
import org.teraschool.platform.api.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PersonnelService {
  private final CurrentActor actors;
  private final TenantTransactions transactions;
  private final DSLContext sql;
  private final SchoolWriteGuard guard;
  private final JsonMapper json = JsonMapper.builder().build();

  public PersonnelService(
      CurrentActor actors,
      TenantTransactions transactions,
      DSLContext sql,
      SchoolWriteGuard guard) {
    this.actors = actors;
    this.transactions = transactions;
    this.sql = sql;
    this.guard = guard;
  }

  private void authorized(UUID school, Actor actor, boolean write) {
    if (!actor.schools().contains(school)
        || !actors.requireResourceInTransaction("EMPLOYEE", write).schools().contains(school))
      throw new AccessDeniedException("人事档案不可访问");
  }

  private DSLContext privateSql() {
    var settings = (org.jooq.conf.Settings) sql.settings().clone();
    return org.jooq.impl.DSL.using(sql.configuration().derive(settings.withExecuteLogging(false)));
  }

  public PersonnelView get(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要员工身份");
    var actor = actors.requireResource("PERSONNEL", false);
    return transactions.execute(
        actor.tenantId(),
        () -> {
          var current = actors.requireResourceInTransaction("PERSONNEL", false);
          var row =
              privateSql()
                  .fetchOne(
                      "select e.school_id,e.version,p.profile from organization.employee e left join organization.personnel_profile p on p.employee_id=e.id where e.id=?",
                      id);
          if (row == null) throw new AccessDeniedException("人事档案不可访问");
          UUID school = row.get("school_id", UUID.class);
          authorized(school, current, false);
          var profile = row.get("profile", org.jooq.JSONB.class);
          return new PersonnelView(
              id,
              school,
              row.get("version", Long.class),
              profile == null ? null : json.readValue(profile.data(), PersonnelData.class));
        });
  }

  private void validate(PersonnelData data) {
    if (data == null) throw new IllegalArgumentException("需要档案资料");
    var tree = json.valueToTree(data);
    tree.properties()
        .forEach(
            entry -> {
              if (!entry.getValue().isNull() && entry.getValue().isTextual()) {
                String value = entry.getValue().asText();
                if (value.length() > (entry.getKey().equals("memo") ? 2000 : 200)
                    || value
                        .codePoints()
                        .anyMatch(
                            c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                  throw new IllegalArgumentException("档案字段长度或字符无效");
              }
            });
    for (String value :
        new String[] {
          data.entryDate(),
          data.leaveDate(),
          data.contractStart(),
          data.contractEnd(),
          data.probationStart(),
          data.probationEnd(),
          data.birthday()
        }) {
      if (value != null && !value.isBlank())
        try {
          LocalDate.parse(value);
        } catch (RuntimeException invalid) {
          throw new IllegalArgumentException("日期须为YYYY-MM-DD");
        }
    }
    if (data.children() != null) {
      if (data.children().size() > 100) throw new IllegalArgumentException("子女资料数量超限");
      for (var child : data.children()) {
        if (child == null
            || child.name() == null
            || child.name().isBlank()
            || child.name().length() > 100
            || child.name().codePoints().anyMatch(Character::isISOControl)
            || child.gender() != null && child.gender().length() > 100)
          throw new IllegalArgumentException("子女资料无效");
        if (child.birthday() != null && !child.birthday().isBlank())
          try {
            LocalDate.parse(child.birthday());
          } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("子女出生日期无效");
          }
      }
    }
    if (data.contributionPeriods() != null) {
      if (data.contributionPeriods().size() > 200) throw new IllegalArgumentException("金额阶段数量超限");
      var seen = new java.util.HashSet<String>();
      for (var period : data.contributionPeriods()) {
        if (period == null
            || !java.util.Set.of("SOCIAL_INSURANCE", "HOUSING_FUND")
                .contains(java.util.Objects.toString(period.type(), ""))
            || period.startMonth() == null
            || !period.startMonth().matches("[0-9]{4}-(0[1-9]|1[0-2])")
            || period.amount() == null
            || !period.amount().matches("[0-9]{1,14}(\\.[0-9]{1,2})?")
            || !seen.add(period.type() + ":" + period.startMonth()))
          throw new IllegalArgumentException("金额阶段无效或重复");
      }
    }
    checkDates(data.entryDate(), data.leaveDate());
    checkDates(data.contractStart(), data.contractEnd());
    checkDates(data.probationStart(), data.probationEnd());
    for (String value :
        new String[] {
          data.baseSalary(), data.employerSocialInsurance(), data.employerHousingFund()
        }) {
      if (value != null && !value.isBlank())
        try {
          if (!value.matches("[0-9]{1,14}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("金额格式无效");
          var amount = new BigDecimal(value);
          if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() > 16)
            throw new IllegalArgumentException("金额无效");
        } catch (NumberFormatException invalid) {
          throw new IllegalArgumentException("金额格式无效");
        }
    }
  }

  private void checkDates(String start, String end) {
    if (start != null
        && !start.isBlank()
        && end != null
        && !end.isBlank()
        && LocalDate.parse(start).isAfter(LocalDate.parse(end)))
      throw new IllegalArgumentException("结束日期不能早于开始日期");
  }

  public PersonnelView update(UUID id, UpdatePersonnel input) {
    if (id == null
        || input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0) throw new IllegalArgumentException("需要有效员工身份和版本");
    validate(input.data());
    var actor = actors.requireResource("PERSONNEL", true);
    return transactions.execute(
        actor.tenantId(),
        () -> {
          guard.lock(actor.tenantId());
          var current = actors.requireResourceInTransaction("PERSONNEL", true);
          var employee =
              sql.fetchOne(
                  "select school_id,version from organization.employee where id=? for update", id);
          if (employee == null) throw new AccessDeniedException("人事档案不可访问");
          UUID school = employee.get("school_id", UUID.class);
          authorized(school, current, true);
          guard.requireActive(school);
          long version = employee.get("version", Long.class);
          if (version != input.expectedVersion())
            throw new OrganizationConflict("VERSION_CONFLICT");
          // jOOQ DEBUG 执行日志默认可能输出绑定值；档案正文始终禁用该执行日志。
          privateSql()
              .execute(
                  "insert into organization.personnel_profile(tenant_id,school_id,employee_id,profile) values (?,?,?,?::jsonb) on conflict(employee_id) do update set profile=excluded.profile",
                  actor.tenantId(),
                  school,
                  id,
                  json.writeValueAsString(input.data()));
          sql.execute(
              "update organization.employee set version=version+1 where id=? and version=?",
              id,
              version);
          sql.execute(
              "insert into audit.resource_event(tenant_id,actor_id,school_id,resource_id,kind,action,before_json,after_json,trace_id) values (?,?,?,?,'PERSONNEL','UPDATE',?::jsonb,?::jsonb,?)",
              actor.tenantId(),
              actor.accountId(),
              school,
              id,
              json.writeValueAsString(Map.of("version", version)),
              json.writeValueAsString(Map.of("version", version + 1)),
              RequestTrace.id());
          return new PersonnelView(id, school, version + 1, input.data());
        });
  }
}
