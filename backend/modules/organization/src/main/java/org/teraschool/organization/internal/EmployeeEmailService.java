package org.teraschool.organization.internal;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.EmployeeEmailConfiguration.*;
import org.teraschool.organization.api.OrganizationConflict;
import org.teraschool.platform.api.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EmployeeEmailService {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolWriteGuard guard;
  private final Environment environment;
  private final SecureRandom random = new SecureRandom();
  private final JsonMapper json = JsonMapper.builder().build();

  public EmployeeEmailService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolWriteGuard guard,
      Environment environment) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.guard = guard;
    this.environment = environment;
  }

  private DSLContext privateSql() {
    var settings = (org.jooq.conf.Settings) sql.settings().clone();
    return org.jooq.impl.DSL.using(sql.configuration().derive(settings.withExecuteLogging(false)));
  }

  private org.jooq.Record employee(UUID account, boolean lock) {
    var row =
        sql.fetchOne(
            "select id,school_id,version from organization.employee where account_id=?"
                + (lock ? " for update" : ""),
            account);
    if (row == null) throw new AccessDeniedException("当前账号未关联员工");
    return row;
  }

  private String clean(String input, String label) {
    if (input == null
        || input.length() > 200
        || input.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "无效");
    return input.strip();
  }

  private byte[][] encrypt(UUID tenant, UUID employee, UUID account, String credential) {
    String encoded = environment.getProperty("teraschool.mail-credentials.master-key");
    byte[] key;
    try {
      key = Base64.getDecoder().decode(Objects.toString(encoded, ""));
    } catch (IllegalArgumentException invalid) {
      throw new OrganizationConflict("MAIL_KEY_UNAVAILABLE");
    }
    if (key.length != 32) {
      Arrays.fill(key, (byte) 0);
      throw new OrganizationConflict("MAIL_KEY_UNAVAILABLE");
    }
    byte[] plaintext = credential.getBytes(StandardCharsets.UTF_8), nonce = new byte[12];
    random.nextBytes(nonce);
    try {
      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] verifier =
          mac.doFinal("teraschool:employee-email-master-key:v1".getBytes(StandardCharsets.UTF_8));
      var row = privateSql().fetchOne("select verifier from infra.mail_key_verifier where id=1");
      if (row == null) {
        if (sql.fetchOne(
                "select 1 from organization.employee_email_config where credential_ciphertext is not null limit 1")
            != null) throw new OrganizationConflict("MAIL_KEY_MISMATCH");
        privateSql()
            .execute(
                "insert into infra.mail_key_verifier(id,verifier) values (1,?) on conflict(id) do nothing",
                verifier);
        row = privateSql().fetchOne("select verifier from infra.mail_key_verifier where id=1");
      }
      if (row == null
          || !java.security.MessageDigest.isEqual(verifier, row.get("verifier", byte[].class)))
        throw new OrganizationConflict("MAIL_KEY_MISMATCH");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(
          ("teraschool:employee-email:v1:" + tenant + ":" + employee + ":" + account)
              .getBytes(StandardCharsets.UTF_8));
      return new byte[][] {nonce, cipher.doFinal(plaintext)};
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("邮件凭据加密失败", error);
    } finally {
      Arrays.fill(key, (byte) 0);
      Arrays.fill(plaintext, (byte) 0);
    }
  }

  EmailConfigurationView get() {
    var identity = actors.permissions();
    return tx.execute(
        identity.tenantId(),
        () -> {
          var current = actors.requireIdentityInTransaction();
          var employee = employee(current.accountId(), false);
          var config =
              privateSql()
                  .fetchOne(
                      "select smtp_host,smtp_user,sender_address,(credential_ciphertext is not null) as has_credential from organization.employee_email_config where employee_id=? and account_id=?",
                      employee.get("id", UUID.class),
                      current.accountId());
          return new EmailConfigurationView(
              employee.get("id", UUID.class),
              config == null ? "" : config.get("smtp_host", String.class),
              config == null ? "" : config.get("smtp_user", String.class),
              config == null ? "" : config.get("sender_address", String.class),
              config != null && config.get("has_credential", Boolean.class),
              employee.get("version", Long.class));
        });
  }

  EmailConfigurationView change(ChangeEmailConfiguration input) {
    if (input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0
        || !Set.of("KEEP", "REPLACE", "CLEAR")
            .contains(Objects.toString(input.credentialAction(), "")))
      throw new IllegalArgumentException("需要版本和凭据操作");
    String host = clean(input.smtpHost(), "SMTP地址"),
        user = clean(input.smtpUser(), "SMTP用户名"),
        address = clean(input.senderAddress(), "发件地址");
    if (input.credentialAction().equals("REPLACE")) {
      if (input.credential() == null
          || input.credential().isEmpty()
          || input.credential().getBytes(StandardCharsets.UTF_8).length > 1024)
        throw new IllegalArgumentException("凭据长度无效");
    } else if (input.credential() != null && !input.credential().isEmpty())
      throw new IllegalArgumentException("非替换操作不接受凭据");
    var identity = actors.permissions();
    return tx.execute(
        identity.tenantId(),
        () -> {
          guard.lock(identity.tenantId());
          var current = actors.requireIdentityInTransaction();
          var employee = employee(current.accountId(), true);
          UUID id = employee.get("id", UUID.class), school = employee.get("school_id", UUID.class);
          guard.requireActive(school);
          long version = employee.get("version", Long.class);
          if (version != input.expectedVersion())
            throw new OrganizationConflict("VERSION_CONFLICT");
          byte[][] encrypted =
              input.credentialAction().equals("REPLACE")
                  ? encrypt(current.tenantId(), id, current.accountId(), input.credential())
                  : null;
          privateSql()
              .execute(
                  "insert into organization.employee_email_config(tenant_id,school_id,employee_id,account_id,smtp_host,smtp_user,sender_address,credential_nonce,credential_ciphertext,credential_key_version) values (?,?,?,?,?,?,?,?,?,?) on conflict(employee_id) do update set account_id=excluded.account_id,smtp_host=excluded.smtp_host,smtp_user=excluded.smtp_user,sender_address=excluded.sender_address,credential_nonce=case when ?='KEEP' and employee_email_config.account_id=excluded.account_id then employee_email_config.credential_nonce else excluded.credential_nonce end,credential_ciphertext=case when ?='KEEP' and employee_email_config.account_id=excluded.account_id then employee_email_config.credential_ciphertext else excluded.credential_ciphertext end,credential_key_version=case when ?='KEEP' and employee_email_config.account_id=excluded.account_id then employee_email_config.credential_key_version else excluded.credential_key_version end",
                  current.tenantId(),
                  school,
                  id,
                  current.accountId(),
                  host,
                  user,
                  address,
                  encrypted == null ? null : encrypted[0],
                  encrypted == null ? null : encrypted[1],
                  encrypted == null ? null : 1,
                  input.credentialAction(),
                  input.credentialAction(),
                  input.credentialAction());
          sql.execute(
              "update organization.employee set version=version+1 where id=? and version=?",
              id,
              version);
          boolean hasCredential =
              Boolean.TRUE.equals(
                  sql.fetchOne(
                          "select credential_ciphertext is not null from organization.employee_email_config where employee_id=?",
                          id)
                      .get(0, Boolean.class));
          sql.execute(
              "insert into audit.resource_event(tenant_id,actor_id,school_id,resource_id,kind,action,before_json,after_json,trace_id) values (?,?,?,?,'EMPLOYEE_EMAIL','UPDATE',?::jsonb,?::jsonb,?)",
              current.tenantId(),
              current.accountId(),
              school,
              id,
              json.writeValueAsString(Map.of("version", version)),
              json.writeValueAsString(
                  Map.of("version", version + 1, "credentialAction", input.credentialAction())),
              RequestTrace.id());
          return new EmailConfigurationView(id, host, user, address, hasCredential, version + 1);
        });
  }
}
