package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.*;
import org.teraschool.identity.api.AccountLifecycle;
import org.teraschool.organization.api.*;
import org.teraschool.organization.api.ResourceManagement.*;
import org.teraschool.platform.api.TenantTransactions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmployeeEmailAndExternalTest {
  static final String INITIAL_KEY = Base64.getEncoder().encodeToString(new byte[32]);
  static final AtomicReference<String> KEY = new AtomicReference<>(INITIAL_KEY);
  @Autowired EmployeeEmailConfiguration email;
  @Autowired PersonnelExternalProfile external;
  @Autowired PersonnelManagement personnel;
  @Autowired ResourceManagement resources;
  @Autowired EmployeeAccountLinks links;
  @Autowired AccountLifecycle accounts;
  @Autowired SchoolManagement schools;
  @Autowired TenantTransactions tx;
  AuthorizationManagementTest f;
  UUID employee;
  final tools.jackson.databind.json.JsonMapper mapper =
      tools.jackson.databind.json.JsonMapper.builder().build();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
    r.add("teraschool.mail-credentials.master-key", KEY::get);
  }

  @BeforeEach
  void seed() throws Exception {
    KEY.set(INITIAL_KEY);
    f = new AuthorizationManagementTest();
    f.seed();
    for (String action :
        List.of("EMPLOYEE_READ", "EMPLOYEE_MAINTAIN", "PERSONNEL_READ", "PERSONNEL_MAINTAIN"))
      f.grant(f.root, action, f.a, "ALLOW");
    employee =
        resources
            .create(
                Kind.EMPLOYEE,
                new CreateResource(UUID.randomUUID(), f.a, "邮箱员工", null, null, null, null))
            .id();
    links.changeLink(employee, new EmployeeAccountLinks.ChangeAccountLink(0L, f.root));
  }

  @AfterEach
  void clear() {
    KEY.set(INITIAL_KEY);
    f.clear();
  }

  EmployeeEmailConfiguration.ChangeEmailConfiguration change(
      long version, String mode, String secret) {
    return new EmployeeEmailConfiguration.ChangeEmailConfiguration(
        version, "smtp.example.test", "mail-user", "sender@example.test", mode, secret);
  }

  @Test
  void ownMailboxUsesEncryptedWriteOnlyCredentialAndDetectsWrongMasterKey() throws Exception {
    var before = email.getMyEmailConfiguration();
    assertThat(before.hasCredential()).isFalse();
    String secret = "synthetic-mail-secret-" + UUID.randomUUID();
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.jooq");
    var prior = logger.getLevel();
    var captured =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    try {
      var saved = email.changeMyEmailConfiguration(change(before.version(), "REPLACE", secret));
      assertThat(saved.hasCredential()).isTrue();
      assertThat(saved.toString()).doesNotContain(secret);
      assertThat(email.getMyEmailConfiguration().hasCredential()).isTrue();
      assertThat(
              f.count(
                  "select count(*) from organization.employee_email_config where employee_id=? and position(convert_to(?,'UTF8') in credential_ciphertext)>0",
                  employee,
                  secret))
          .isZero();
      try (var connection = EngineeringTest.admin();
          var statement =
              connection.prepareStatement(
                  "select credential_nonce,credential_ciphertext from organization.employee_email_config where employee_id=?")) {
        statement.setObject(1, employee);
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
          cipher.init(
              javax.crypto.Cipher.DECRYPT_MODE,
              new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(INITIAL_KEY), "AES"),
              new javax.crypto.spec.GCMParameterSpec(128, rows.getBytes(1)));
          cipher.updateAAD(
              ("teraschool:employee-email:v1:" + f.tenant + ":" + employee + ":" + f.root)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
          assertThat(
                  new String(
                      cipher.doFinal(rows.getBytes(2)), java.nio.charset.StandardCharsets.UTF_8))
              .isEqualTo(secret);
        }
      }
      assertThat(
              f.count(
                  "select count(*) from audit.resource_event where resource_id=? and (before_json::text like ? or after_json::text like ?)",
                  employee,
                  "%" + secret + "%",
                  "%" + secret + "%"))
          .isZero();
      assertThat(
              captured.list.stream()
                  .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
          .noneMatch(x -> x.contains(secret));
      var retained = email.changeMyEmailConfiguration(change(saved.version(), "KEEP", null));
      assertThat(retained.hasCredential()).isTrue();
      byte[] other = new byte[32];
      other[0] = 1;
      KEY.set(Base64.getEncoder().encodeToString(other));
      assertThatThrownBy(
              () ->
                  email.changeMyEmailConfiguration(
                      change(retained.version(), "REPLACE", "another-secret")))
          .isInstanceOf(OrganizationConflict.class)
          .hasMessage("MAIL_KEY_MISMATCH");
      UUID foreignEmployee = UUID.randomUUID();
      EngineeringTest.update(
          "insert into organization.employee(id,tenant_id,school_id,name,account_id) values (?,?,?,'外机构员工',?)",
          foreignEmployee,
          f.other,
          f.foreignSchool,
          f.foreignUser);
      f.as(f.foreignUser);
      assertThatThrownBy(
              () -> email.changeMyEmailConfiguration(change(0L, "REPLACE", "foreign-secret")))
          .isInstanceOf(OrganizationConflict.class)
          .hasMessage("MAIL_KEY_MISMATCH");
      f.as(f.root);
      KEY.set(INITIAL_KEY);
      var cleared = email.changeMyEmailConfiguration(change(retained.version(), "CLEAR", null));
      assertThat(cleared.hasCredential()).isFalse();
    } finally {
      logger.detachAppender(captured);
      logger.setLevel(prior);
      captured.stop();
    }
  }

  @Test
  void unbindingClearsCredentialAndDoesNotGrantNextAccountAccess() throws Exception {
    var config = email.getMyEmailConfiguration();
    var saved =
        email.changeMyEmailConfiguration(change(config.version(), "REPLACE", "temporary-secret"));
    links.changeLink(
        employee, new EmployeeAccountLinks.ChangeAccountLink(saved.version(), f.target));
    assertThat(
            f.count(
                "select count(*) from organization.employee_email_config where employee_id=? and credential_ciphertext is not null",
                employee))
        .isZero();
    f.as(f.target);
    assertThat(email.getMyEmailConfiguration().hasCredential()).isFalse();
    var own = email.getMyEmailConfiguration();
    assertThat(own.smtpHost()).isEmpty();
    f.as(f.root);
    assertThatThrownBy(() -> email.getMyEmailConfiguration())
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void malformedCredentialColumnsAreRejectedAndSuspendedSchoolBlocksChanges() throws Exception {
    var config = email.getMyEmailConfiguration();
    var saved =
        email.changeMyEmailConfiguration(change(config.version(), "REPLACE", "temporary-secret"));
    assertThatThrownBy(
            () ->
                EngineeringTest.update(
                    "update organization.employee_email_config set credential_nonce=null where employee_id=?",
                    employee))
        .isInstanceOf(Exception.class);
    var school = schools.getSchool(f.a);
    schools.updateSchool(
        f.a, new SchoolManagement.UpdateSchool(school.version(), school.name(), false));
    assertThat(email.getMyEmailConfiguration().hasCredential()).isTrue();
    assertThatThrownBy(
            () -> email.changeMyEmailConfiguration(change(saved.version(), "CLEAR", null)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void emailReadRechecksSessionWithinOuterTransaction() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      accounts.revokeSessions(
                          f.root, new AccountLifecycle.RevokeAccountSessions(0L));
                      email.getMyEmailConfiguration();
                      return null;
                    }))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void independentExternalIdentifiersSurviveOlderPersonnelFormReplacement() throws Exception {
    var original = personnel.getPersonnel(employee);
    var oldData = mapper.readValue("{\"post\":\"教师\"}", PersonnelManagement.PersonnelData.class);
    var saved =
        personnel.updatePersonnel(
            employee, new PersonnelManagement.UpdatePersonnel(original.version(), oldData));
    var externalData =
        mapper.readValue(
            "{\"workWechatUserId\":\"test-id\",\"seatNumber\":\"A12\"}",
            PersonnelExternalProfile.ExternalIdentifiers.class);
    var extended =
        external.updateExternalProfile(
            employee,
            new PersonnelExternalProfile.UpdateExternalProfile(saved.version(), externalData));
    var changed =
        personnel.updatePersonnel(
            employee, new PersonnelManagement.UpdatePersonnel(extended.version(), oldData));
    assertThat(external.getExternalProfile(employee).data().workWechatUserId())
        .isEqualTo("test-id");
    assertThatThrownBy(
            () ->
                external.updateExternalProfile(
                    employee,
                    new PersonnelExternalProfile.UpdateExternalProfile(
                        saved.version(), externalData)))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("VERSION_CONFLICT");
    assertThat(changed.data().post()).isEqualTo("教师");
    f.as(f.target);
    assertThatThrownBy(() -> external.getExternalProfile(employee))
        .isInstanceOf(AccessDeniedException.class);
  }
}
