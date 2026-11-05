package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.*;
import org.teraschool.identity.api.*;
import org.teraschool.identity.api.AuthorizationManagement.*;
import org.teraschool.organization.api.*;
import org.teraschool.organization.api.ResourceManagement.*;
import org.teraschool.platform.api.TenantTransactions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchoolAndResourcesTest {
  @Autowired SchoolManagement schools;
  @Autowired ResourceManagement resources;
  @Autowired AuthorizationManagement authorization;
  @Autowired CurrentActor actors;
  @Autowired AccountLifecycle lifecycle;
  @Autowired TeacherQualifications qualifications;
  @Autowired EmployeeAccountLinks links;
  @Autowired PersonnelManagement personnel;
  @Autowired OrganizationCommands organization;
  @Autowired TenantTransactions tx;
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  AuthorizationManagementTest f;
  UUID campus, department, foreignCampus;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeEach
  void seed() throws Exception {
    f = new AuthorizationManagementTest();
    f.seed();
    campus = UUID.randomUUID();
    department = UUID.randomUUID();
    foreignCampus = UUID.randomUUID();
    for (var row :
        List.of(
            new Object[] {campus, f.tenant, f.a, "CAMPUS"},
            new Object[] {department, f.tenant, f.a, "DEPARTMENT"},
            new Object[] {foreignCampus, f.other, f.foreignSchool, "CAMPUS"}))
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,?,?)",
          row[0],
          row[1],
          row[2],
          row[3],
          "资源夹具");
    for (var kind : Kind.values())
      for (String suffix : List.of("_READ", "_MAINTAIN"))
        f.grant(f.root, kind.name() + suffix, f.a, "ALLOW");
  }

  @AfterEach
  void clear() {
    f.clear();
  }

  CreateResource input(
      UUID request,
      UUID school,
      String name,
      UUID campus,
      UUID dept,
      UUID employee,
      Integer capacity) {
    return new CreateResource(request, school, name, campus, dept, employee, capacity);
  }

  @Test
  void schoolCreationIsIdempotentAndPreservesFullAdministrator() throws Exception {
    var request = new SchoolManagement.CreateSchool(UUID.randomUUID(), "新学校");
    var first = schools.createSchool(request);
    assertThat(schools.createSchool(request)).isEqualTo(first);
    assertThatThrownBy(
            () ->
                schools.createSchool(
                    new SchoolManagement.CreateSchool(request.requestId(), "不同学校")))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(actors.permissions().manageableSchoolIds())
        .containsExactlyInAnyOrder(f.a, f.b, first.id());
    for (var kind : Kind.values())
      assertThat(resources.capabilities(kind).editableSchoolIds()).contains(first.id());
    assertThat(
            f.count(
                "select count(*) from audit.business_event where action='SCHOOL_CREATED' and resource_id=?",
                first.id()))
        .isEqualTo(1);
    assertThat(
            f.count(
                "select count(*) from audit.authorization_event where target_account_id=?", f.root))
        .isEqualTo(1);
    f.as(f.delegate);
    assertThat(schools.canCreate()).isFalse();
    assertThatThrownBy(
            () -> schools.createSchool(new SchoolManagement.CreateSchool(UUID.randomUUID(), "越权")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void resourceWriteSeesSessionRevocationWithinTheSameOuterTransaction() {
    var teacher =
        resources.create(
            Kind.TEACHER, input(UUID.randomUUID(), f.a, "撤销资质测试", null, null, null, null));
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      lifecycle.revokeSessions(
                          f.root, new AccountLifecycle.RevokeAccountSessions(0L));
                      resources.create(
                          Kind.ROOM, input(UUID.randomUUID(), f.a, "撤销后教室", null, null, null, 10));
                      return null;
                    }))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      lifecycle.revokeSessions(
                          f.root, new AccountLifecycle.RevokeAccountSessions(0L));
                      qualifications.getQualification(teacher.id());
                      return null;
                    }))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void schoolSuspendBlocksWritesAndResumePreservesHistory() throws Exception {
    f.grant(f.root, "ORGANIZATION_READ", f.a, "ALLOW");
    f.grant(f.root, "ORGANIZATION_MAINTAIN", f.a, "ALLOW");
    var teacher =
        resources.create(
            Kind.TEACHER, input(UUID.randomUUID(), f.a, "教师甲", null, null, null, null));
    var qualification =
        qualifications.updateQualification(
            teacher.id(), new TeacherQualifications.UpdateQualification(0L, "数学教学资质"));
    assertThat(qualification.version()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                qualifications.updateQualification(
                    teacher.id(), new TeacherQualifications.UpdateQualification(0L, "过期")))
        .isInstanceOf(OrganizationConflict.class);
    var original = new CreateOrganizationUnit(UUID.randomUUID(), f.a, "CAMPUS", "历史校区");
    var child = organization.createChild(original);
    var school = schools.getSchool(f.a);
    schools.updateSchool(f.a, new SchoolManagement.UpdateSchool(school.version(), "已停用学校", false));
    assertThat(qualifications.getQualification(teacher.id()).qualification()).isEqualTo("数学教学资质");
    assertThatThrownBy(
            () ->
                qualifications.updateQualification(
                    teacher.id(), new TeacherQualifications.UpdateQualification(1L, "不应写入")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                resources.create(
                    Kind.ROOM, input(UUID.randomUUID(), f.a, "停用学校教室", null, null, null, 10)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> organization.createChild(original))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> organization.rename(child.id(), new RenameOrganizationUnit(0L, "不应修改")))
        .isInstanceOf(AccessDeniedException.class);
    var suspended = schools.getSchool(f.a);
    schools.updateSchool(f.a, new SchoolManagement.UpdateSchool(suspended.version(), "恢复学校", true));
    assertThat(
            qualifications
                .updateQualification(
                    teacher.id(), new TeacherQualifications.UpdateQualification(1L, "恢复后"))
                .version())
        .isEqualTo(2);
  }

  @Test
  void linkingEmployeeRequiresBothAuthoritiesAndEnforcesUniqueness() {
    var first =
        resources.create(
            Kind.EMPLOYEE, input(UUID.randomUUID(), f.a, "员工甲", null, null, null, null));
    var second =
        resources.create(
            Kind.EMPLOYEE, input(UUID.randomUUID(), f.a, "员工乙", null, null, null, null));
    var linked =
        links.changeLink(first.id(), new EmployeeAccountLinks.ChangeAccountLink(0L, f.target));
    assertThat(linked.accountId()).isEqualTo(f.target);
    assertThatThrownBy(
            () ->
                links.changeLink(
                    second.id(), new EmployeeAccountLinks.ChangeAccountLink(0L, f.target)))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("ACCOUNT_ALREADY_LINKED");
    assertThatThrownBy(
            () ->
                links.changeLink(
                    second.id(), new EmployeeAccountLinks.ChangeAccountLink(0L, f.foreignUser)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            links
                .changeLink(
                    first.id(), new EmployeeAccountLinks.ChangeAccountLink(linked.version(), null))
                .accountId())
        .isNull();
    f.as(f.delegate);
    assertThatThrownBy(() -> links.getLink(first.id())).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void personnelDataRequiresSeparatePermissionAndDoesNotEnterAudit() throws Exception {
    f.grant(f.root, "PERSONNEL_READ", f.a, "ALLOW");
    f.grant(f.root, "PERSONNEL_MAINTAIN", f.a, "ALLOW");
    var employee =
        resources.create(
            Kind.EMPLOYEE, input(UUID.randomUUID(), f.a, "档案员工", null, null, null, null));
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    var teacher =
        resources.create(
            Kind.TEACHER, input(UUID.randomUUID(), f.a, "教师日志测试", null, null, null, null));
    var data =
        mapper.readValue(
            "{\"idNumber\":\"sensitive-test-id\",\"baseSalary\":\"12000.00\",\"contractStart\":\"2026-01-01\",\"contractEnd\":\"2026-12-31\"}",
            PersonnelManagement.PersonnelData.class);
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.jooq");
    var previousLevel = logger.getLevel();
    var captured =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.list = new java.util.concurrent.CopyOnWriteArrayList<>();
    captured.start();
    logger.addAppender(captured);
    logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    try {
      var result =
          personnel.updatePersonnel(
              employee.id(), new PersonnelManagement.UpdatePersonnel(0L, data));
      assertThat(result.version()).isEqualTo(1);
      assertThat(personnel.getPersonnel(employee.id()).data().idNumber())
          .isEqualTo("sensitive-test-id");
      qualifications.updateQualification(
          teacher.id(),
          new TeacherQualifications.UpdateQualification(0L, "private-qualification-sentinel"));
      assertThat(qualifications.getQualification(teacher.id()).qualification())
          .isEqualTo("private-qualification-sentinel");
      assertThat(
              captured.list.stream()
                  .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
          .noneMatch(value -> value.contains("sensitive-test-id"));
      assertThat(
              captured.list.stream()
                  .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
          .noneMatch(value -> value.contains("private-qualification-sentinel"));
    } finally {
      logger.detachAppender(captured);
      logger.setLevel(previousLevel);
      captured.stop();
    }
    assertThat(
            f.count(
                "select count(*) from audit.resource_event where resource_id=? and (before_json::text like '%sensitive-test-id%' or after_json::text like '%sensitive-test-id%')",
                employee.id()))
        .isZero();
    var invalid =
        mapper.readValue("{\"baseSalary\":\"1E+100\"}", PersonnelManagement.PersonnelData.class);
    assertThatThrownBy(
            () ->
                personnel.updatePersonnel(
                    employee.id(), new PersonnelManagement.UpdatePersonnel(1L, invalid)))
        .isInstanceOf(IllegalArgumentException.class);
    f.as(f.target);
    assertThatThrownBy(() -> personnel.getPersonnel(employee.id()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void queuedResourceWriteObservesConcurrentSchoolSuspension() throws Exception {
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var holder =
          pool.submit(
              () -> {
                f.as(f.root);
                try {
                  return tx.execute(
                      f.tenant,
                      () -> {
                        var school = schools.getSchool(f.a);
                        schools.updateSchool(
                            f.a,
                            new SchoolManagement.UpdateSchool(
                                school.version(), school.name(), false));
                        held.countDown();
                        try {
                          if (!release.await(20, TimeUnit.SECONDS))
                            throw new IllegalStateException("等待超时");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return true;
                      });
                } finally {
                  f.clear();
                }
              });
      assertThat(held.await(20, TimeUnit.SECONDS)).isTrue();
      var waiting =
          pool.submit(
              () -> {
                f.as(f.root);
                try {
                  try {
                    resources.create(
                        Kind.ROOM, input(UUID.randomUUID(), f.a, "排队写入", null, null, null, 5));
                    return false;
                  } catch (AccessDeniedException denied) {
                    return true;
                  }
                } finally {
                  f.clear();
                }
              });
      boolean locked = false;
      for (int n = 0; n < 100; n++) {
        if (f.count(
                "select count(*) from pg_stat_activity where usename='ts_app' and wait_event='advisory' and query like '%pg_advisory_xact_lock%'")
            > 0) {
          locked = true;
          break;
        }
        Thread.sleep(25);
      }
      assertThat(locked).isTrue();
      release.countDown();
      assertThat(holder.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(waiting.get(20, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void schoolCreationAndConcurrentAdministratorRemovalCannotLeaveNoFullAdmin() throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var create =
          pool.submit(
              () -> {
                f.as(f.root);
                try {
                  start.await();
                  return schools.createSchool(
                      new SchoolManagement.CreateSchool(UUID.randomUUID(), "并发学校"));
                } finally {
                  f.clear();
                }
              });
      var revoke =
          pool.submit(
              () -> {
                f.as(f.root);
                try {
                  start.await();
                  authorization.replace(f.root, new Replacement(0L, List.of(), List.of()));
                  return false;
                } catch (AuthorizationConflict denied) {
                  return true;
                } finally {
                  f.clear();
                }
              });
      start.countDown();
      var result = create.get(20, TimeUnit.SECONDS);
      assertThat(revoke.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(actors.permissions().manageableSchoolIds()).contains(result.id(), f.a, f.b);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void resourcesRequireTheirOwnActionAndLimitSchoolsBeforePagination() throws Exception {
    var item =
        resources.create(
            Kind.EMPLOYEE, input(UUID.randomUUID(), f.a, "员工甲", campus, department, null, null));
    assertThat(resources.list(Kind.EMPLOYEE, 0, 20).items())
        .extracting(ResourceView::id)
        .contains(item.id());
    assertThatThrownBy(
            () ->
                resources.create(
                    Kind.EMPLOYEE, input(UUID.randomUUID(), f.b, "越界员工", null, null, null, null)))
        .isInstanceOf(AccessDeniedException.class);
    f.as(f.target);
    assertThatThrownBy(() -> resources.list(Kind.EMPLOYEE, 0, 20))
        .isInstanceOf(AccessDeniedException.class);
    f.grant(f.target, "EMPLOYEE_READ", f.a, "ALLOW");
    assertThat(resources.list(Kind.EMPLOYEE, 0, 20).total()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                resources.update(
                    Kind.EMPLOYEE,
                    item.id(),
                    new UpdateResource(0L, "无权", campus, department, null, null, true)))
        .isInstanceOf(AccessDeniedException.class);
    f.grant(f.target, "EMPLOYEE_MAINTAIN", f.a, "ALLOW");
    f.grant(f.target, "EMPLOYEE_MAINTAIN", f.a, "DENY");
    assertThat(resources.capabilities(Kind.EMPLOYEE).editableSchoolIds()).isEmpty();
    assertThatThrownBy(() -> resources.list(Kind.TEACHER, 0, 20))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void resourceCreationReplayVersionAndAuditAreConsistent() throws Exception {
    UUID request = UUID.randomUUID();
    var payload = input(request, f.a, "员工甲", campus, department, null, null);
    var created = resources.create(Kind.EMPLOYEE, payload);
    assertThat(resources.create(Kind.EMPLOYEE, payload)).isEqualTo(created);
    assertThatThrownBy(
            () ->
                resources.create(
                    Kind.EMPLOYEE, input(request, f.a, "其他", campus, department, null, null)))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    var changed =
        resources.update(
            Kind.EMPLOYEE,
            created.id(),
            new UpdateResource(0L, "员工改名", campus, department, null, null, false));
    assertThat(changed.version()).isEqualTo(1);
    assertThat(changed.active()).isFalse();
    assertThatThrownBy(
            () ->
                resources.update(
                    Kind.EMPLOYEE,
                    created.id(),
                    new UpdateResource(0L, "旧版", campus, department, null, null, true)))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("VERSION_CONFLICT");
    assertThat(
            f.count("select count(*) from audit.resource_event where resource_id=?", created.id()))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      resources.create(
                          Kind.ROOM, input(UUID.randomUUID(), f.a, "回滚教室", campus, null, null, 20));
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            f.count(
                "select count(*) from audit.resource_event where school_id=? and kind='ROOM'", f.a))
        .isZero();
    assertThat(
            f.count(
                "select count(*) from audit.operation_failure where tenant_id=? and action='ROOM_CREATE' and code='TRANSACTION_ROLLED_BACK'",
                f.tenant))
        .isEqualTo(1);
  }

  @Test
  void teachersAreIndependentAndOptionalEmployeeLinkIsSchoolBound() throws Exception {
    var employee =
        resources.create(
            Kind.EMPLOYEE, input(UUID.randomUUID(), f.a, "员工", campus, department, null, null));
    var linked =
        resources.create(
            Kind.TEACHER, input(UUID.randomUUID(), f.a, "教师姓名", null, null, employee.id(), null));
    var independent =
        resources.create(
            Kind.TEACHER, input(UUID.randomUUID(), f.a, "独立教师", null, null, null, null));
    assertThat(linked.employeeId()).isEqualTo(employee.id());
    assertThat(independent.employeeId()).isNull();
    assertThatThrownBy(
            () ->
                resources.create(
                    Kind.TEACHER,
                    input(UUID.randomUUID(), f.a, "重复关联", null, null, employee.id(), null)))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("EMPLOYEE_ALREADY_TEACHER");
    assertThatThrownBy(
            () ->
                resources.create(
                    Kind.EMPLOYEE,
                    input(UUID.randomUUID(), f.a, "错校区", foreignCampus, null, null, null)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                update(
                    "insert into organization.teacher(tenant_id,school_id,name,employee_id) values (?,?,?,?)",
                    f.other,
                    f.foreignSchool,
                    "跨租户",
                    employee.id()))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () ->
                update(
                    "insert into organization.employee(tenant_id,school_id,name,campus_id) values (?,?,?,?)",
                    f.tenant,
                    f.a,
                    "错学校",
                    foreignCampus))
        .isInstanceOf(SQLException.class);
    f.as(f.target);
    f.grant(f.target, "TEACHER_READ", f.a, "ALLOW");
    f.grant(f.target, "TEACHER_MAINTAIN", f.a, "ALLOW");
    assertThatThrownBy(
            () ->
                resources.create(
                    Kind.TEACHER,
                    input(UUID.randomUUID(), f.a, "无权关联", null, null, employee.id(), null)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void roomCapacityStatusAndLocationsAreValidated() {
    var room =
        resources.create(Kind.ROOM, input(UUID.randomUUID(), f.a, "多媒体教室", campus, null, null, 30));
    assertThat(room.capacity()).isEqualTo(30);
    assertThatThrownBy(
            () ->
                resources.update(
                    Kind.ROOM,
                    room.id(),
                    new UpdateResource(0L, "异常", campus, null, null, -1, true)))
        .isInstanceOf(IllegalArgumentException.class);
    var result =
        resources.update(
            Kind.ROOM, room.id(), new UpdateResource(0L, "可容纳40人", campus, null, null, 40, false));
    assertThat(result.capacity()).isEqualTo(40);
    assertThat(result.active()).isFalse();
    assertThat(resources.options(Kind.ROOM, "CAMPUS", f.a, ""))
        .extracting(ResourceOption::id)
        .contains(campus)
        .doesNotContain(foreignCampus);
  }

  @Test
  void httpSchoolAndRoomRespectCsrfAndReturnActualResources() throws Exception {
    var helper = new AccountLifecycleTest();
    helper.port = port;
    var client = helper.client();
    assertThat(helper.login(client, f.root, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(204);
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    String body =
        mapper.writeValueAsString(input(UUID.randomUUID(), f.a, "HTTP教室", campus, null, null, 20));
    var uri = java.net.URI.create("http://127.0.0.1:" + port + "/api/organization/resources/ROOM");
    var unsafe =
        java.net.http.HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
    assertThat(client.send(unsafe, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(403);
    String token =
        mapper.readTree(helper.get(client, "/api/session/csrf").body()).get("token").asText();
    var request =
        java.net.http.HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", token)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
    assertThat(
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(201);
    assertThat(helper.get(client, "/api/organization/resources/ROOM").body()).contains("HTTP教室");
    var school =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + "/api/organization/schools"))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", token)
            .POST(
                java.net.http.HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(
                        new SchoolManagement.CreateSchool(UUID.randomUUID(), "HTTP学校"))))
            .build();
    assertThat(client.send(school, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(201);
  }
}
