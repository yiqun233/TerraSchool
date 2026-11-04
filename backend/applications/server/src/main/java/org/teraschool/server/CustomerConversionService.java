package org.teraschool.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.admissions.api.CustomerConflict;
import org.teraschool.archive.api.CreateStudent;
import org.teraschool.archive.api.StudentCommands;
import org.teraschool.archive.api.StudentContactInput;
import org.teraschool.archive.api.StudentCreation;
import org.teraschool.archive.api.StudentQueries;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/** 在同一租户事务中创建学员并关联负责人客户线索。 */
@Service
class CustomerConversionService {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final StudentCommands students;
  private final StudentQueries studentQueries;

  CustomerConversionService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      StudentCommands students,
      StudentQueries studentQueries) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.students = students;
    this.studentQueries = studentQueries;
  }

  CustomerStudentLinkView link(UUID customerId) {
    if (customerId == null) throw new IllegalArgumentException("需要客户");
    var scope = actors.requireResourceScope("CUSTOMER");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var customer = ownCustomer(scope, customerId);
          var row =
              sql.fetchOne(
                  "select customer_id,student_id,school_id,owner_id from admissions.customer_student_link where customer_id=?",
                  customerId);
          return row == null ? null : linkView(row, customer.get("version", Long.class));
        });
  }

  CustomerConversionResult convert(UUID customerId, ConvertCustomerToStudent command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (customerId == null
          || command == null
          || command.requestId() == null
          || command.expectedCustomerVersion() == null)
        throw new IllegalArgumentException("需要客户、请求身份和客户版本");
      var relation = relation(command.customerContactRelation());
      var customerScope = actors.requireResourceScope("CUSTOMER");
      return tx.execute(
          customerScope.tenantId(),
          () -> {
            schools.lockTenant(customerScope.tenantId());
            var verifiedCustomer = actors.requireResourceScopeInTransaction("CUSTOMER");
            var verifiedStudent = actors.requireResourceScopeInTransaction("STUDENT");
            var phoneReader = actors.requireActionInTransaction("CUSTOMER_PHONE_READ");
            sql.fetch(
                "select pg_advisory_xact_lock(hashtextextended(?,0))",
                customerScope.tenantId()
                    + ":"
                    + verifiedCustomer.accountId()
                    + ":"
                    + command.requestId());
            var customer = ownCustomer(verifiedCustomer, customerId);
            var schoolId = customer.get("school_id", UUID.class);
            var ownerId = customer.get("owner_id", UUID.class);
            if (!phoneReader.schools().contains(schoolId))
              throw new AccessDeniedException("转化客户联系人需要完整电话查看权限");
            if (ownerId == null || !ownerId.equals(verifiedCustomer.accountId()))
              throw new AccessDeniedException("仅客户当前负责人可转为学员");
            if (!verifiedCustomer.maintainableSchoolIds().contains(schoolId)
                || !verifiedStudent.maintainableSchoolIds().contains(schoolId))
              throw new AccessDeniedException("客户和学员都需要该学校的维护权限");
            if (!schools.require(schoolId).active())
              throw new AccessDeniedException("学校已停用，不能转为学员");
            if (Boolean.TRUE.equals(customer.get("in_pool", Boolean.class)))
              throw new CustomerConflict("CUSTOMER_IN_POOL");
            if (customer.get("merged_into_id", UUID.class) != null)
              throw new CustomerConflict("CUSTOMER_MERGED");

            var fingerprint = fingerprint(customer, command, relation);
            var previous =
                sql.fetchOne(
                    "select customer_id,student_id,fingerprint from admissions.customer_student_link"
                        + " where tenant_id=? and owner_id=? and request_id=?",
                    customerScope.tenantId(),
                    ownerId,
                    command.requestId());
            if (previous != null) {
              if (!customerId.equals(previous.get("customer_id", UUID.class))
                  || !fingerprint.equals(previous.get("fingerprint", String.class)))
                throw new CustomerConflict("IDEMPOTENCY_MISMATCH");
              var link =
                  sql.fetchOne(
                      "select customer_id,student_id,school_id,owner_id from admissions.customer_student_link where customer_id=?",
                      customerId);
              return new CustomerConversionResult(
                  linkView(link, customer.get("version", Long.class)),
                  new StudentCreation(
                      studentQueries.detail(previous.get("student_id", UUID.class)),
                      List.of(),
                      List.of()));
            }

            var existing =
                sql.fetchOne(
                    "select student_id from admissions.customer_student_link where customer_id=?",
                    customerId);
            if (existing != null) throw new CustomerConflict("CUSTOMER_ALREADY_CONVERTED");
            if (!customer.get("version", Long.class).equals(command.expectedCustomerVersion()))
              throw new CustomerConflict("VERSION_CONFLICT");

            var contact =
                new StudentContactInput(
                    customer.get("name", String.class),
                    relation,
                    customer.get("phone", String.class),
                    null,
                    null,
                    null,
                    true);
            var studentRequestId =
                UUID.nameUUIDFromBytes(
                    ("customer-conversion:"
                            + customerScope.tenantId()
                            + ":"
                            + ownerId
                            + ":"
                            + command.requestId())
                        .getBytes(StandardCharsets.UTF_8));
            var created =
                students.create(
                    new CreateStudent(
                        studentRequestId,
                        schoolId,
                        command.campusId(),
                        command.studentName(),
                        command.studentNameEnglish(),
                        command.sex(),
                        command.birthday(),
                        command.certType(),
                        command.certCode(),
                        command.studentPhone(),
                        command.email(),
                        List.of(contact),
                        command.confirmDuplicate()));

            sql.execute(
                "insert into admissions.customer_student_link(tenant_id,customer_id,student_id,school_id,owner_id,request_id,fingerprint)"
                    + " values (?,?,?,?,?,?,?)",
                customerScope.tenantId(),
                customerId,
                created.profile().id(),
                schoolId,
                ownerId,
                command.requestId(),
                fingerprint);
            sql.execute(
                "insert into audit.customer_event(tenant_id,actor_id,school_id,customer_id,request_id,action,trace_id)"
                    + " values (?,?,?,?,?,'STUDENT_LINKED',?)",
                customerScope.tenantId(),
                ownerId,
                schoolId,
                customerId,
                command.requestId(),
                RequestTrace.id());
            var link =
                sql.fetchOne(
                    "select customer_id,student_id,school_id,owner_id from admissions.customer_student_link where customer_id=?",
                    customerId);
            return new CustomerConversionResult(
                linkView(link, customer.get("version", Long.class)), created);
          });
    }
  }

  private org.jooq.Record ownCustomer(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,name,phone,owner_id,in_pool,merged_into_id,version"
                + " from admissions.customer where id=?",
            id);
    if (row == null || !scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("客户不存在或不可访问");
    if (!scope.accountId().equals(row.get("owner_id", UUID.class)))
      throw new AccessDeniedException("仅客户当前负责人可查看转学员关联");
    return row;
  }

  private String relation(String value) {
    if (value == null
        || !List.of("FATHER", "MOTHER", "GUARDIAN", "OTHER")
            .contains(value.strip().toUpperCase(Locale.ROOT)))
      throw new IllegalArgumentException("需要选择客户联系人与学员的关系");
    return value.strip().toUpperCase(Locale.ROOT);
  }

  private String fingerprint(
      org.jooq.Record customer, ConvertCustomerToStudent command, String relation) {
    var source =
        String.join(
            "\u001f",
            customer.get("id", UUID.class).toString(),
            String.valueOf(command.expectedCustomerVersion()),
            String.valueOf(command.campusId()),
            String.valueOf(command.studentName()),
            String.valueOf(command.studentNameEnglish()),
            String.valueOf(command.sex()),
            String.valueOf(command.birthday()),
            String.valueOf(command.certType()),
            String.valueOf(command.certCode()),
            String.valueOf(command.studentPhone()),
            String.valueOf(command.email()),
            relation,
            Boolean.toString(command.confirmDuplicate()),
            customer.get("name", String.class),
            customer.get("phone", String.class));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("无法计算客户转学员请求摘要", unavailable);
    }
  }

  private CustomerStudentLinkView linkView(org.jooq.Record row, long customerVersion) {
    return new CustomerStudentLinkView(
        row.get("customer_id", UUID.class),
        row.get("student_id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("owner_id", UUID.class),
        customerVersion);
  }
}
