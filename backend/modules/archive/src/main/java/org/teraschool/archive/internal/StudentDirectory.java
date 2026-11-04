package org.teraschool.archive.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.*;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/**
 * 学员与家长档案的完整用例。
 *
 * <p>事务、授权、敏感字段脱敏和审计都在这里完成：HTTP 适配、受控任务和将来的外部入口调用同一份规则，
 * 不存在只在页面上生效的校验。跨域只通过组织公开契约读取学校身份与状态，不直接读写组织表。
 */
@Service
public class StudentDirectory implements StudentQueries, StudentCommands {
  /** 学员号前缀与位数只是当前可读标识，正式编号规则仍待业务确认。 */
  private static final String NUMBER_PREFIX = "S";

  private static final int NUMBER_WIDTH = 6;
  private static final Set<String> SEX = Set.of("MALE", "FEMALE", "UNKNOWN");
  private static final Set<String> CERT_TYPES = Set.of("ID_CARD", "PASSPORT", "OTHER");
  private static final Set<String> RELATIONS = Set.of("FATHER", "MOTHER", "GUARDIAN", "OTHER");
  private static final Set<String> STATUS = Set.of("ACTIVE", "INACTIVE");
  private static final Pattern PHONE = Pattern.compile("[0-9+()\\- ]{5,30}");
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,60}");
  private static final Pattern CERT_CODE = Pattern.compile("[0-9A-Z]{4,40}");
  private static final int MAX_CONTACTS = 5;

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final org.teraschool.organization.api.OrganizationQueries organizationQueries;

  private final org.teraschool.identity.api.FieldAccess fieldAccess;

  public StudentDirectory(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      org.teraschool.organization.api.OrganizationQueries organizationQueries,
      org.teraschool.identity.api.FieldAccess fieldAccess) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.organizationQueries = organizationQueries;
    this.fieldAccess = fieldAccess;
  }

  // ---------------------------------------------------------------- 查询

  @Override
  public StudentPage list(int page, int size, String search) {
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("分页超出范围");
    if (search != null && search.length() > 50) throw new IllegalArgumentException("检索条件过长");
    var scope = actors.requireResourceScope("STUDENT");
    if (scope.readableSchoolIds().isEmpty()) return new StudentPage(List.of(), page, size, 0);
    var keyword = "%" + (search == null ? "" : search.strip()) + "%";
    var filter =
        " from archive.student s where s.school_id in ("
            + marks(scope.readableSchoolIds().size())
            + ") and (s.name ilike ? or s.student_no ilike ?)";
    var args = new ArrayList<Object>(scope.readableSchoolIds());
    args.add(keyword);
    args.add(keyword);
    return tx.execute(
        scope.tenantId(),
        () -> {
          long total = sql.fetchOne("select count(*)" + filter, args.toArray()).get(0, Long.class);
          var pageArgs = new ArrayList<>(args);
          pageArgs.add(size);
          pageArgs.add(page * size);
          var names = schools.names(scope.readableSchoolIds());
          var items =
              sql.fetch(
                      "select s.id,s.student_no,s.name,s.sex,s.phone,s.school_id,s.campus_id,"
                          + "s.owner_id,s.status,s.version,"
                          + "(select count(*) from archive.student_contact c where c.student_id=s.id)"
                          + " as contact_count"
                          + filter
                          + " order by s.student_no, s.id limit ? offset ?",
                      pageArgs.toArray())
                  .map(
                      row -> {
                        var schoolId = row.get("school_id", UUID.class);
                        return summary(
                            row,
                            names.get(schoolId),
                            !scope.sensitiveSchoolIds().contains(schoolId));
                      });
          return new StudentPage(items, page, size, total);
        });
  }

  @Override
  public StudentProfile detail(UUID id) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return detailWithTrace(id);
    }
  }

  private StudentProfile detailWithTrace(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要学员");
    var scope = actors.requireResourceScope("STUDENT");
    return tx.execute(scope.tenantId(), () -> profile(scope, id, requireReadable(scope, id)));
  }

  @Override
  public StudentCapabilities capabilities() {
    var scope = actors.requireResourceScope("STUDENT");
    var maintainable = scope.maintainableSchoolIds();
    var assignable = assignableSchoolIds();
    if (maintainable.isEmpty())
      return new StudentCapabilities(
          true, false, !scope.sensitiveSchoolIds().isEmpty(), !assignable.isEmpty(), List.of());
    return tx.execute(
        scope.tenantId(),
        () -> {
          var names = schools.names(maintainable);
          var options =
              maintainable.stream()
                  .filter(names::containsKey)
                  .sorted(
                      Comparator.comparing((UUID key) -> names.get(key)).thenComparing(key -> key))
                  .map(key -> new StudentCapabilities.StudentSchoolOption(key, names.get(key)))
                  .toList();
          return new StudentCapabilities(
              true,
              !options.isEmpty(),
              !scope.sensitiveSchoolIds().isEmpty(),
              !assignable.isEmpty(),
              options);
        });
  }

  @Override
  public List<org.teraschool.organization.api.OrganizationQueries.EmployeeAssignmentOption>
      assignmentOptions(UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    return organizationQueries.studentAssignmentOptions(schoolId);
  }

  private Set<UUID> assignableSchoolIds() {
    try {
      return actors.requireAction("STUDENT_ASSIGN").schools();
    } catch (AccessDeniedException denied) {
      return Set.of();
    }
  }

  // ---------------------------------------------------------------- 写入

  @Override
  public StudentCreation create(CreateStudent command) {
    // 页面之外的第二入口也要有追踪号：没有入口上下文时在这里建立应用级追踪。
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createWithTrace(command);
    }
  }

  private StudentCreation createWithTrace(CreateStudent command) {
    if (command == null || command.requestId() == null || command.schoolId() == null)
      throw new IllegalArgumentException("需要请求身份和学校");
    var input = normalise(command);
    var scope = actors.requireResourceScope("STUDENT");
    requireMaintainable(scope, command.schoolId());
    var fingerprint = fingerprint(input);
    return tx.execute(
        scope.tenantId(),
        () -> {
          // 与学校停用、授权替换共用机构锁，锁内重新确认操作者和学校状态。
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("STUDENT");
          requireMaintainable(verified, command.schoolId());
          var school = schools.require(command.schoolId());
          if (!school.active()) throw new AccessDeniedException("学校已停用，不能新增学员档案");
          if (input.campusId() != null) schools.requireCampus(command.schoolId(), input.campusId());
          sql.fetch(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              scope.tenantId() + ":" + scope.accountId() + ":" + command.requestId());
          var previous =
              sql.fetchOne(
                  "select student_id,fingerprint from archive.student_request"
                      + " where tenant_id=? and actor_id=? and request_id=?",
                  scope.tenantId(),
                  scope.accountId(),
                  command.requestId());
          if (previous != null) {
            if (!fingerprint.equals(previous.get("fingerprint", String.class)))
              throw new StudentConflict("IDEMPOTENCY_MISMATCH");
            // 返回首次创建的结果；后续修改不会改写这次操作的历史返回。
            var stored = previous.get("student_id", UUID.class);
            return new StudentCreation(
                profile(verified, stored, requireReadable(verified, stored)), List.of(), List.of());
          }
          var studentNo = nextStudentNo(scope.tenantId());
          var studentId = UUID.randomUUID();
          insertStudent(scope, studentId, studentNo, command.schoolId(), input);
          var reused = new ArrayList<UUID>();
          var contacts = input.contacts();
          for (int index = 0; index < contacts.size(); index++)
            linkContact(scope.tenantId(), studentId, contacts.get(index), index, reused);
          sql.execute(
              "insert into archive.student_request(tenant_id,actor_id,request_id,student_id,fingerprint)"
                  + " values (?,?,?,?,?)",
              scope.tenantId(),
              scope.accountId(),
              command.requestId(),
              studentId,
              fingerprint);
          var hints = duplicates(scope, studentId, input);
          // 只记录发生过重复提示/人工确认这一事实，不复制姓名、证件号等正文。
          audit(
              scope,
              "STUDENT_CREATED",
              studentId,
              command.schoolId(),
              hints.isEmpty()
                  ? ""
                  : command.confirmDuplicate() ? "duplicate_confirmed" : "duplicate_hinted");
          return new StudentCreation(
              profile(verified, studentId, requireReadable(verified, studentId)), hints, reused);
        });
  }

  @Override
  public StudentProfile update(UUID id, UpdateStudent command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return updateWithTrace(id, command);
    }
  }

  private StudentProfile updateWithTrace(UUID id, UpdateStudent command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要学员、请求身份和版本");
    if (command.expectedVersion() < 0) throw new IllegalArgumentException("版本无效");
    var input = normalise(command);
    var scope = actors.requireResourceScope("STUDENT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("STUDENT");
          var current = requireReadable(verified, id);
          var schoolId = current.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无学员档案维护权限");
          if (!schools.require(schoolId).active())
            throw new AccessDeniedException("学校已停用，不能修改学员档案");
          if (input.campusId() != null) schools.requireCampus(schoolId, input.campusId());
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new StudentConflict("VERSION_CONFLICT");
          var changed = new LinkedHashSet<String>();
          for (var field :
              List.of(
                  "name",
                  "name_english",
                  "sex",
                  "birthday",
                  "cert_type",
                  "cert_code",
                  "phone",
                  "email",
                  "campus_id",
                  "status"))
            if (!Objects.equals(current.get(field), column(input, field))) changed.add(field);
          int updated =
              sql.execute(
                  "update archive.student set name=?,name_english=?,sex=?,birthday=?,cert_type=?,"
                      + "cert_code=?,phone=?,email=?,campus_id=?,campus_kind=?,status=?,"
                      + "version=version+1,updated_at=CURRENT_TIMESTAMP where id=? and version=?",
                  input.name(),
                  input.nameEnglish(),
                  input.sex(),
                  dateOf(input.birthday()),
                  input.certType(),
                  input.certCode(),
                  input.phone(),
                  input.email(),
                  input.campusId(),
                  input.campusId() == null ? null : "CAMPUS",
                  input.status(),
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new StudentConflict("VERSION_CONFLICT");
          var reused = replaceContacts(scope.tenantId(), id, input.contacts());
          if (!reused.isEmpty() || !input.contacts().isEmpty()) changed.add("contacts");
          audit(scope, "STUDENT_UPDATED", id, schoolId, String.join(",", changed));
          return profile(verified, id, requireReadable(verified, id));
        });
  }

  // ---------------------------------------------------------------- 内部

  @Override
  public StudentProfile changeStatus(UUID id, ChangeStudentStatus command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return changeStatusWithTrace(id, command);
    }
  }

  @Override
  public StudentProfile assignOwner(UUID id, AssignStudent command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (id == null
          || command == null
          || command.requestId() == null
          || command.expectedVersion() == null
          || command.ownerAccountId() == null
          || command.expectedVersion() < 0)
        throw new IllegalArgumentException("需要学员、请求身份、版本和负责人");
      var reason = command.reason() == null ? null : command.reason().strip();
      if (reason == null || reason.isEmpty() || reason.length() > 200)
        throw new IllegalArgumentException("分派原因必填且不能超过 200 字");
      var assign = actors.requireAction("STUDENT_ASSIGN");
      var scope = actors.requireResourceScope("STUDENT");
      if (!assign.tenantId().equals(scope.tenantId())
          || !assign.accountId().equals(scope.accountId()))
        throw new AccessDeniedException("学员分派权限不匹配");
      var fingerprint =
          fingerprint(
              id.toString(),
              Long.toString(command.expectedVersion()),
              command.ownerAccountId().toString(),
              reason);
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verifiedAssign = actors.requireActionInTransaction("STUDENT_ASSIGN");
            var verifiedScope = actors.requireResourceScopeInTransaction("STUDENT");
            if (!verifiedAssign.accountId().equals(verifiedScope.accountId())
                || !verifiedAssign.tenantId().equals(verifiedScope.tenantId()))
              throw new AccessDeniedException("学员分派权限不匹配");
            var existing =
                sql.fetchOne(
                    "select student_id,fingerprint from archive.student_assignment_request"
                        + " where tenant_id=? and actor_id=? and request_id=?",
                    scope.tenantId(),
                    scope.accountId(),
                    command.requestId());
            if (existing != null) {
              if (!fingerprint.equals(existing.get("fingerprint", String.class)))
                throw new StudentConflict("IDEMPOTENCY_MISMATCH");
              var previousStudent = existing.get("student_id", UUID.class);
              return profile(
                  verifiedScope,
                  previousStudent,
                  requireReadable(verifiedScope, previousStudent));
            }
            var current = requireReadable(verifiedScope, id);
            var schoolId = current.get("school_id", UUID.class);
            if (!verifiedAssign.schools().contains(schoolId))
              throw new AccessDeniedException("无该校学员分派权限");
            if (!"ACTIVE".equals(current.get("status", String.class)))
              throw new StudentConflict("STUDENT_NOT_ACTIVE");
            if (current.get("version", Long.class) != command.expectedVersion())
              throw new StudentConflict("VERSION_CONFLICT");
            if (!schools.require(schoolId).active())
              throw new AccessDeniedException("学校已停用，不能分派学员");
            var previousOwner = current.get("owner_id", UUID.class);
            if (command.ownerAccountId().equals(previousOwner))
              throw new StudentConflict("OWNER_UNCHANGED");
            if (!organizationQueries.isActiveStudentAssignee(schoolId, command.ownerAccountId()))
              throw new StudentConflict("ASSIGNEE_NOT_ACTIVE_IN_SCHOOL");
            int updated =
                sql.execute(
                    "update archive.student set owner_id=?,version=version+1,"
                        + "updated_at=CURRENT_TIMESTAMP where id=? and version=?",
                    command.ownerAccountId(),
                    id,
                    command.expectedVersion());
            if (updated != 1) throw new StudentConflict("VERSION_CONFLICT");
            sql.execute(
                "insert into archive.student_owner_assignment(tenant_id,student_id,from_account_id,"
                    + "to_account_id,reason,actor_id) values (?,?,?,?,?,?)",
                scope.tenantId(),
                id,
                previousOwner,
                command.ownerAccountId(),
                reason,
                scope.accountId());
            sql.execute(
                "insert into archive.student_assignment_request(tenant_id,actor_id,request_id,"
                    + "student_id,fingerprint) values (?,?,?,?,?)",
                scope.tenantId(),
                scope.accountId(),
                command.requestId(),
                id,
                fingerprint);
            audit(scope, "STUDENT_ASSIGNED", id, schoolId, "owner_changed");
            return profile(verifiedScope, id, requireReadable(verifiedScope, id));
          });
    }
  }

  /**
   * 只变更启停状态。
   *
   * <p>没有敏感字段权限的账号只能看到脱敏值，整套提交会把脱敏值写成权威字段；状态变更因此只接受版本与目标状态。
   */
  private StudentProfile changeStatusWithTrace(UUID id, ChangeStudentStatus command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要学员和版本");
    if (command.expectedVersion() < 0) throw new IllegalArgumentException("版本无效");
    var status =
        command.status() == null ? null : command.status().strip().toUpperCase(Locale.ROOT);
    if (status == null || !STATUS.contains(status)) throw new IllegalArgumentException("状态无效");
    var scope = actors.requireResourceScope("STUDENT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("STUDENT");
          var current = requireReadable(verified, id);
          var schoolId = current.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无学员档案维护权限");
          if (!schools.require(schoolId).active())
            throw new AccessDeniedException("学校已停用，不能修改学员档案");
          int updated =
              sql.execute(
                  "update archive.student set status=?,version=version+1,updated_at=CURRENT_TIMESTAMP"
                      + " where id=? and version=?",
                  status,
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new StudentConflict("VERSION_CONFLICT");
          audit(scope, "STUDENT_UPDATED", id, schoolId, "status");
          return profile(verified, id, requireReadable(verified, id));
        });
  }

  private record Normalised(
      UUID campusId,
      String name,
      String nameEnglish,
      String sex,
      String birthday,
      String certType,
      String certCode,
      String phone,
      String email,
      String status,
      List<Contact> contacts) {}

  private record Contact(
      String name,
      String relation,
      String phone,
      String phoneAlt,
      String email,
      String wechat,
      boolean primary) {}

  private Normalised normalise(CreateStudent command) {
    return normalise(
        command.campusId(),
        command.name(),
        command.nameEnglish(),
        command.sex(),
        command.birthday(),
        command.certType(),
        command.certCode(),
        command.phone(),
        command.email(),
        "ACTIVE",
        command.contacts());
  }

  private Normalised normalise(UpdateStudent command) {
    return normalise(
        command.campusId(),
        command.name(),
        command.nameEnglish(),
        command.sex(),
        command.birthday(),
        command.certType(),
        command.certCode(),
        command.phone(),
        command.email(),
        command.status(),
        command.contacts());
  }

  /** 结构与业务校验集中在一处，任何入口都无法提交绕过校验的值。 */
  private Normalised normalise(
      UUID campusId,
      String name,
      String nameEnglish,
      String sex,
      String birthday,
      String certType,
      String certCode,
      String phone,
      String email,
      String status,
      List<StudentContactInput> contacts) {
    var cleanName = text(name, 50, "学员姓名");
    if (cleanName == null) throw new IllegalArgumentException("学员姓名必填");
    var cleanSex = upper(sex, SEX, "UNKNOWN", "性别无效");
    var cleanStatus = upper(status, STATUS, "ACTIVE", "状态无效");
    var cleanBirthday = birthday(birthday);
    var cleanCertType =
        certType == null || certType.isBlank() ? null : certType.toUpperCase(Locale.ROOT);
    if (cleanCertType != null && !CERT_TYPES.contains(cleanCertType))
      throw new IllegalArgumentException("证件类型无效");
    var cleanCertCode = certCode == null || certCode.isBlank() ? null : certCode.strip();
    if (cleanCertCode != null) {
      cleanCertCode = cleanCertCode.toUpperCase(Locale.ROOT);
      if (cleanCertType == null || !CERT_CODE.matcher(cleanCertCode).matches())
        throw new IllegalArgumentException("证件号须为4–40位字母数字，且同时提供证件类型");
    }
    return new Normalised(
        campusId,
        cleanName,
        text(nameEnglish, 50, "英文名"),
        cleanSex,
        cleanBirthday,
        cleanCertType,
        cleanCertCode,
        phone(phone),
        email(email),
        cleanStatus,
        contacts(contacts));
  }

  private String upper(String value, Set<String> allowed, String fallback, String message) {
    var text = value == null ? fallback : value.strip().toUpperCase(Locale.ROOT);
    if (text.isEmpty()) text = fallback;
    if (!allowed.contains(text)) throw new IllegalArgumentException(message);
    return text;
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
  }

  private String birthday(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      var date = LocalDate.parse(value.strip());
      if (date.getYear() < 1900 || date.isAfter(LocalDate.now()))
        throw new IllegalArgumentException("出生日期超出合理范围");
      return date.toString();
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("出生日期须为 YYYY-MM-DD");
    }
  }

  /** 日期列使用 LocalDate 绑定，字符串只用于接口与摘要。 */
  private LocalDate dateOf(String value) {
    return value == null ? null : LocalDate.parse(value);
  }

  private String phone(String value) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (!PHONE.matcher(text).matches()) throw new IllegalArgumentException("电话格式无效");
    return text;
  }

  private String email(String value) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    // 脱敏值不能被当成新值写回：列表与详情返回的是掩码，提交时必须拒绝。
    if (text.contains("***")) throw new IllegalArgumentException("邮箱疑似脱敏值，不能作为档案内容");
    if (!EMAIL.matcher(text).matches()) throw new IllegalArgumentException("邮箱格式无效");
    return text;
  }

  private List<Contact> contacts(List<StudentContactInput> inputs) {
    if (inputs.size() > MAX_CONTACTS)
      throw new IllegalArgumentException("联系人最多" + MAX_CONTACTS + "位");
    var result = new ArrayList<Contact>();
    boolean declared = inputs.stream().anyMatch(input -> input != null && input.primary());
    // 主要联系人最多一个：数据库唯一索引是最后防线，这里先给出可读的错误。
    if (inputs.stream().filter(input -> input != null && input.primary()).count() > 1)
      throw new IllegalArgumentException("只能有一位主要联系人");
    for (int index = 0; index < inputs.size(); index++) {
      var item = inputs.get(index);
      if (item == null) throw new IllegalArgumentException("联系人不能为空");
      var name = text(item.name(), 50, "联系人姓名");
      if (name == null) throw new IllegalArgumentException("联系人须有姓名");
      var phone = phone(item.phone());
      var email = email(item.email());
      var wechat = text(item.wechat(), 50, "微信");
      if (phone == null && email == null && wechat == null)
        throw new IllegalArgumentException("联系人至少要有电话、邮箱或微信之一");
      // 没有显式主要联系人时，第一位作为默认主要联系人，避免页面默认值不确定。
      result.add(
          new Contact(
              name,
              upper(item.relation(), RELATIONS, "OTHER", "联系人关系无效"),
              phone,
              phone(item.phoneAlt()),
              email,
              wechat,
              declared ? item.primary() : index == 0));
    }
    return result;
  }

  private Object column(Normalised input, String field) {
    return switch (field) {
      case "name" -> input.name();
      case "name_english" -> input.nameEnglish();
      case "sex" -> input.sex();
      case "birthday" -> dateOf(input.birthday());
      case "cert_type" -> input.certType();
      case "cert_code" -> input.certCode();
      case "phone" -> input.phone();
      case "email" -> input.email();
      case "campus_id" -> input.campusId();
      default -> input.status();
    };
  }

  private void requireMaintainable(ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId)) throw new AccessDeniedException("无学校读取权限");
    if (!scope.maintainableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无学员档案维护权限");
  }

  private Record requireReadable(ResourceScope scope, UUID id) {
    if (scope.readableSchoolIds().isEmpty()) throw new AccessDeniedException("无学员档案读取权限");
    var args = new ArrayList<Object>();
    args.add(id);
    args.addAll(scope.readableSchoolIds());
    var row =
        sql.fetchOne(
            "select id,student_no,name,name_english,sex,birthday,cert_type,cert_code,phone,email,"
                + "school_id,campus_id,owner_id,status,version from archive.student"
                + " where id=? and school_id in ("
                + marks(scope.readableSchoolIds().size())
                + ")",
            args.toArray());
    if (row == null) throw new AccessDeniedException("学员档案不存在或不可访问");
    return row;
  }

  /** 详情读取：返回完整敏感字段时在同一事务写入独立审计，便于事后追溯谁看过。 */
  private StudentProfile profile(ResourceScope scope, UUID studentId, Record row) {
    var schoolId = row.get("school_id", UUID.class);
    var full = scope.sensitiveSchoolIds().contains(schoolId);
    if (full) audit(scope, "STUDENT_SENSITIVE_READ", studentId, schoolId, "");
    // 字段级授权：功能动作之外再按字段判断；STUDENT_SENSITIVE_READ 视为拥有全部字段。
    var granted = fieldAccess.readableFields(scope.accountId(), schoolId);
    var denied = fieldAccess.deniedFields(scope.accountId(), schoolId);
    var phoneOk = (full || granted.contains("STUDENT_PHONE")) && !denied.contains("STUDENT_PHONE");
    var certOk = (full || granted.contains("STUDENT_CERT")) && !denied.contains("STUDENT_CERT");
    var birthdayOk =
        (full || granted.contains("STUDENT_BIRTHDAY")) && !denied.contains("STUDENT_BIRTHDAY");
    var emailOk = (full || granted.contains("STUDENT_EMAIL")) && !denied.contains("STUDENT_EMAIL");
    var contactPhoneOk =
        (full || granted.contains("CONTACT_PHONE")) && !denied.contains("CONTACT_PHONE");
    var maskedFields = new java.util.ArrayList<String>();
    if (!phoneOk) maskedFields.add("STUDENT_PHONE");
    if (!certOk) maskedFields.add("STUDENT_CERT");
    if (!birthdayOk) maskedFields.add("STUDENT_BIRTHDAY");
    if (!emailOk) maskedFields.add("STUDENT_EMAIL");
    if (!contactPhoneOk) maskedFields.add("CONTACT_PHONE");
    var contacts =
        sql.fetch(
                "select c.id,c.name,sc.relation,sc.is_primary,c.phone,c.phone_alt,c.email,c.wechat"
                    + " from archive.student_contact sc"
                    + " join archive.contact c on c.id=sc.contact_id and c.tenant_id=sc.tenant_id"
                    + " where sc.student_id=? order by sc.position, c.name",
                studentId)
            .map(
                row2 ->
                    new StudentContact(
                        row2.get("id", UUID.class),
                        row2.get("name", String.class),
                        row2.get("relation", String.class),
                        row2.get("is_primary", Boolean.class),
                        masked(contactPhoneOk, Masking::phone, row2.get("phone", String.class)),
                        masked(contactPhoneOk, Masking::phone, row2.get("phone_alt", String.class)),
                        masked(emailOk, Masking::email, row2.get("email", String.class)),
                        row2.get("wechat", String.class)));
    var names = schools.names(List.of(schoolId));
    var birthday = row.get("birthday", LocalDate.class);
    return new StudentProfile(
        studentId,
        row.get("student_no", String.class),
        row.get("name", String.class),
        row.get("name_english", String.class),
        row.get("sex", String.class),
        masked(birthdayOk, Masking::birthday, birthday == null ? null : birthday.toString()),
        row.get("cert_type", String.class),
        masked(certOk, Masking::certificate, row.get("cert_code", String.class)),
        masked(phoneOk, Masking::phone, row.get("phone", String.class)),
        masked(emailOk, Masking::email, row.get("email", String.class)),
        schoolId,
        names.get(schoolId),
        row.get("campus_id", UUID.class),
        row.get("owner_id", UUID.class),
        row.get("status", String.class),
        row.get("version", Long.class),
        contacts,
        !full,
        maskedFields);
  }

  private String masked(boolean full, java.util.function.UnaryOperator<String> mask, String value) {
    return full ? value : mask.apply(value);
  }

  private StudentSummary summary(Record row, String schoolName, boolean masked) {
    return new StudentSummary(
        row.get("id", UUID.class),
        row.get("student_no", String.class),
        row.get("name", String.class),
        row.get("sex", String.class),
        masked ? Masking.phone(row.get("phone", String.class)) : row.get("phone", String.class),
        row.get("school_id", UUID.class),
        schoolName,
        row.get("campus_id", UUID.class),
        row.get("owner_id", UUID.class),
        row.get("status", String.class),
        row.get("version", Long.class),
        row.get("contact_count", Integer.class),
        masked);
  }

  private void insertStudent(
      ResourceScope scope, UUID studentId, String studentNo, UUID schoolId, Normalised input) {
    try {
      sql.execute(
          "insert into archive.student(id,tenant_id,student_no,name,name_english,sex,birthday,"
              + "cert_type,cert_code,phone,email,school_id,campus_id,campus_kind,status)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          studentId,
          scope.tenantId(),
          studentNo,
          input.name(),
          input.nameEnglish(),
          input.sex(),
          dateOf(input.birthday()),
          input.certType(),
          input.certCode(),
          input.phone(),
          input.email(),
          schoolId,
          input.campusId(),
          input.campusId() == null ? null : "CAMPUS",
          input.status());
    } catch (RuntimeException error) {
      // 证件号是硬重复：靠数据库唯一索引兜底，避免并发创建出两份同证件档案。
      // Spring 的 jOOQ 异常翻译会包装成 DataIntegrityViolationException，因此按原因链判断 SQLState。
      if (uniqueViolation(error, "student_cert"))
        throw new StudentConflict("DUPLICATE_CERTIFICATE");
      throw error;
    }
  }

  private boolean uniqueViolation(Throwable error, String constraint) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sqlError
          && "23505".equals(sqlError.getSQLState())
          && sqlError.getMessage() != null
          && sqlError.getMessage().contains(constraint)) return true;
    return false;
  }

  private String nextStudentNo(UUID tenant) {
    // 按租户递增：跨租户不共享序列，避免从编号看出其他机构的业务量。
    var row =
        sql.fetchOne(
            "insert into archive.student_number(tenant_id,next_value) values (?,2)"
                + " on conflict (tenant_id) do update set next_value=archive.student_number.next_value+1"
                + " returning next_value-1 as used",
            tenant);
    if (row == null) throw new IllegalStateException("无法分配学员号");
    return NUMBER_PREFIX
        + String.format(Locale.ROOT, "%0" + NUMBER_WIDTH + "d", row.get("used", Long.class));
  }

  private void linkContact(
      UUID tenant, UUID studentId, Contact contact, int position, List<UUID> reused) {
    UUID contactId = findReusableContact(tenant, contact);
    if (contactId != null) reused.add(contactId);
    else {
      contactId = UUID.randomUUID();
      sql.execute(
          "insert into archive.contact(id,tenant_id,name,phone,phone_alt,email,wechat) values (?,?,?,?,?,?,?)",
          contactId,
          tenant,
          contact.name(),
          contact.phone(),
          contact.phoneAlt(),
          contact.email(),
          contact.wechat());
    }
    sql.execute(
        "insert into archive.student_contact(tenant_id,student_id,contact_id,relation,is_primary,position)"
            + " values (?,?,?,?,?,?) on conflict (student_id,contact_id) do update set"
            + " relation=excluded.relation,is_primary=excluded.is_primary,position=excluded.position",
        tenant,
        studentId,
        contactId,
        contact.relation(),
        contact.primary(),
        position);
  }

  /** 姓名与手机号都一致才复用；只有手机号相同不能证明是同一位家长，因此不自动合并。 */
  private UUID findReusableContact(UUID tenant, Contact contact) {
    Record row;
    if (contact.phone() != null)
      row =
          sql.fetchOne(
              "select id from archive.contact where tenant_id=? and name=? and phone=?",
              tenant,
              contact.name(),
              contact.phone());
    else if (contact.email() != null)
      row =
          sql.fetchOne(
              "select id from archive.contact where tenant_id=? and name=? and email=?",
              tenant,
              contact.name(),
              contact.email());
    else return null;
    return row == null ? null : row.get("id", UUID.class);
  }

  private List<UUID> replaceContacts(UUID tenant, UUID studentId, List<Contact> contacts) {
    sql.execute("delete from archive.student_contact where student_id=?", studentId);
    var reused = new ArrayList<UUID>();
    for (int index = 0; index < contacts.size(); index++)
      linkContact(tenant, studentId, contacts.get(index), index, reused);
    return reused;
  }

  /** 只返回提示，不自动合并；同名同生日或同名同电话都需人工核验。 */
  private List<DuplicateHint> duplicates(ResourceScope scope, UUID studentId, Normalised input) {
    if (input.birthday() == null && input.phone() == null) return List.of();
    var args = new ArrayList<Object>(scope.readableSchoolIds());
    args.add(input.name());
    args.add(studentId);
    var condition = new StringBuilder();
    if (input.birthday() != null) {
      condition.append(condition.isEmpty() ? "" : " or ").append("s.birthday=cast(? as date)");
      args.add(input.birthday());
    }
    if (input.phone() != null) {
      condition.append(condition.isEmpty() ? "" : " or ").append("s.phone=?");
      args.add(input.phone());
    }
    return sql.fetch(
            "select s.id,s.student_no,s.name,s.birthday from archive.student s"
                + " where s.school_id in ("
                + marks(scope.readableSchoolIds().size())
                + ") and s.name=? and s.id<>? and ("
                + condition
                + ") order by s.student_no limit 5",
            args.toArray())
        .map(
            row -> {
              var birthday = row.get("birthday", LocalDate.class);
              var reason =
                  input.birthday() != null
                          && birthday != null
                          && input.birthday().equals(birthday.toString())
                      ? DuplicateHint.SAME_NAME_AND_BIRTHDAY
                      : DuplicateHint.SAME_NAME_AND_PHONE;
              return new DuplicateHint(
                  row.get("id", UUID.class),
                  row.get("student_no", String.class),
                  row.get("name", String.class),
                  reason);
            });
  }

  private String fingerprint(Normalised input) {
    return fingerprint(
            String.valueOf(input.name()),
            String.valueOf(input.nameEnglish()),
            input.sex(),
            String.valueOf(input.birthday()),
            String.valueOf(input.certType()),
            String.valueOf(input.certCode()),
            String.valueOf(input.phone()),
            String.valueOf(input.email()),
            String.valueOf(input.campusId()),
            input.contacts().toString());
  }

  private String fingerprint(String... values) {
    var text = String.join("\u001f", values);
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      var hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
      return hex.toString();
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("无法计算请求摘要", unavailable);
    }
  }

  private String marks(int size) {
    if (size < 1) throw new IllegalArgumentException("学校范围为空");
    return String.join(",", Collections.nCopies(size, "?"));
  }

  @Override
  public String exportCsv(UUID schoolId) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireResourceScope("STUDENT");
      if (schoolId == null || !scope.readableSchoolIds().contains(schoolId))
        throw new AccessDeniedException("无该学校的学员读取权限");
      return tx.execute(
          scope.tenantId(),
          () -> {
            // 导出与详情用同一套字段授权：没有字段权限的列一律输出脱敏值。
            var granted = fieldAccess.readableFields(scope.accountId(), schoolId);
            var denied = fieldAccess.deniedFields(scope.accountId(), schoolId);
            var full = scope.sensitiveSchoolIds().contains(schoolId);
            var phoneOk =
                (full || granted.contains("STUDENT_PHONE")) && !denied.contains("STUDENT_PHONE");
            var certOk =
                (full || granted.contains("STUDENT_CERT")) && !denied.contains("STUDENT_CERT");
            var birthdayOk =
                (full || granted.contains("STUDENT_BIRTHDAY"))
                    && !denied.contains("STUDENT_BIRTHDAY");
            var emailOk =
                (full || granted.contains("STUDENT_EMAIL")) && !denied.contains("STUDENT_EMAIL");
            var rows =
                sql.fetch(
                    "select student_no,name,sex,birthday,cert_code,phone,email from archive.student"
                        + " where school_id=? order by student_no limit 5001",
                    schoolId);
            org.teraschool.platform.api.CsvExport.requireComplete(rows.size());
            var csv = new StringBuilder();
            csv.append("学员号,姓名,性别,出生日期,证件号,电话,邮箱").append(NEWLINE);
            for (var row : rows) {
              var birthday = row.get("birthday", LocalDate.class);
              csv.append(cell(row.get("student_no", String.class)))
                  .append(',')
                  .append(cell(row.get("name", String.class)))
                  .append(',')
                  .append(cell(row.get("sex", String.class)))
                  .append(',')
                  .append(
                      cell(
                          masked(
                              birthdayOk,
                              Masking::birthday,
                              birthday == null ? null : birthday.toString())))
                  .append(',')
                  .append(
                      cell(
                          masked(certOk, Masking::certificate, row.get("cert_code", String.class))))
                  .append(',')
                  .append(cell(masked(phoneOk, Masking::phone, row.get("phone", String.class))))
                  .append(',')
                  .append(cell(masked(emailOk, Masking::email, row.get("email", String.class))))
                  .append(NEWLINE);
            }
            audit(scope, "STUDENT_EXPORTED", schoolId, schoolId, "rows=" + rows.size());
            return csv.toString();
          });
    }
  }

  private static final char NEWLINE = (char) 10;

  private String cell(String value) {
    return org.teraschool.platform.api.CsvExport.cell(value);
  }

  private void audit(ResourceScope scope, String action, UUID student, UUID school, String fields) {
    sql.execute(
        "insert into audit.student_event(tenant_id,actor_id,action,resource_id,school_id,changed_fields,trace_id)"
            + " values (?,?,?,?,?,?,?)",
        scope.tenantId(),
        scope.accountId(),
        action,
        student,
        school,
        fields,
        RequestTrace.id());
  }
}
