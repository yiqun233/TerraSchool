package org.teraschool.organization.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.DataDictionaries;
import org.teraschool.organization.api.OrganizationConflict;
import org.teraschool.platform.api.BusinessAudit;
import org.teraschool.platform.api.TenantTransactions;

/** 学校范围的数据字典维护：代码稳定、记录软停用、并发更新使用版本号。 */
@Service
public class SchoolDictionaryService implements DataDictionaries {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final BusinessAudit audit;
  private final SchoolWriteGuard schoolGuard;

  public SchoolDictionaryService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      BusinessAudit audit,
      SchoolWriteGuard schoolGuard) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.audit = audit;
    this.schoolGuard = schoolGuard;
  }

  @Override
  public List<Item> list(UUID schoolId, String dictionaryCode, boolean includeInactive) {
    var actor = actors.requireOrganizationReader();
    requireSchool(actor, schoolId);
    var code = validCode(dictionaryCode, "字典代码");
    return tx.execute(
        actor.tenantId(),
        () -> {
          schoolGuard.requireActive(schoolId);
          return sql.fetch(
                  "select id,school_id,dictionary_code,item_code,label,sort_order,active,version "
                      + "from organization.dictionary_item where tenant_id=? and school_id=? "
                      + "and dictionary_code=? and (? or active) order by sort_order,item_code,id",
                  actor.tenantId(),
                  schoolId,
                  code,
                  includeInactive)
              .map(
                  row ->
                      new Item(
                          row.get("id", UUID.class),
                          row.get("school_id", UUID.class),
                          row.get("dictionary_code", String.class),
                          row.get("item_code", String.class),
                          row.get("label", String.class),
                          row.get("sort_order", Integer.class),
                          row.get("active", Boolean.class),
                          row.get("version", Long.class)));
        });
  }

  @Override
  public Item create(UUID schoolId, String dictionaryCode, CreateItem command) {
    var actor = actors.requireOrganizationMaintainer();
    if (schoolId == null || command == null || command.requestId() == null)
      throw new IllegalArgumentException("需要学校、字典项和请求身份");
    var category = validCode(dictionaryCode, "字典代码");
    var itemCode = validCode(command.itemCode(), "字典项代码");
    var label = validLabel(command.label());
    int sortOrder = command.sortOrder() == null ? 0 : command.sortOrder();
    if (sortOrder < 0 || sortOrder > 1_000_000)
      throw new IllegalArgumentException("排序值须在0至1000000之间");
    requireSchool(actor, schoolId);
    String requestHash =
        hash(schoolId + "|" + category + "|" + itemCode + "|" + label + "|" + sortOrder);
    return tx.execute(
        actor.tenantId(),
        () -> {
          schoolGuard.lock(actor.tenantId());
          var verified = actors.requireOrganizationMaintainerInTransaction();
          requireSchool(verified, schoolId);
          schoolGuard.requireActive(schoolId);
          var requestKey = actor.tenantId() + ":" + actor.accountId() + ":" + command.requestId();
          sql.fetch("select pg_advisory_xact_lock(hashtextextended(?,0))", requestKey);
          var prior =
              sql.fetchOne(
                  "select request_hash,item_id from organization.dictionary_item_request "
                      + "where tenant_id=? and actor_id=? and request_id=?",
                  actor.tenantId(),
                  actor.accountId(),
                  command.requestId());
          if (prior != null) {
            if (!requestHash.equals(prior.get("request_hash", String.class)))
              throw new OrganizationConflict("IDEMPOTENCY_MISMATCH");
            var existing =
                sql.fetchOne(
                    "select id,school_id,dictionary_code,item_code,label,sort_order,active,version "
                        + "from organization.dictionary_item where tenant_id=? and school_id=? and id=?",
                    actor.tenantId(),
                    schoolId,
                    prior.get("item_id", UUID.class));
            if (existing == null) throw new OrganizationConflict("DICTIONARY_ITEM_MISSING");
            return view(existing);
          }
          var inserted =
              sql.fetchOne(
                  "insert into organization.dictionary_item(tenant_id,school_id,dictionary_code,item_code,label,sort_order) "
                      + "values (?,?,?,?,?,?) on conflict (tenant_id,school_id,dictionary_code,item_code) do nothing "
                      + "returning id,school_id,dictionary_code,item_code,label,sort_order,active,version",
                  actor.tenantId(),
                  schoolId,
                  category,
                  itemCode,
                  label,
                  sortOrder);
          if (inserted == null) throw new OrganizationConflict("DICTIONARY_ITEM_EXISTS");
          sql.execute(
              "insert into organization.dictionary_item_request(tenant_id,actor_id,request_id,request_hash,item_id) values (?,?,?,?,?)",
              actor.tenantId(),
              actor.accountId(),
              command.requestId(),
              requestHash,
              inserted.get("id", UUID.class));
          audit.append(
              actor.tenantId(),
              actor.accountId(),
              "ORGANIZATION_DICTIONARY_ITEM_CREATED",
              inserted.get("id", UUID.class),
              schoolId,
              null,
              itemCode,
              null,
              "{\"dictionaryCode\":\""
                  + category
                  + "\",\"itemCode\":\""
                  + itemCode
                  + "\",\"label\":\""
                  + auditText(label)
                  + "\"}");
          return view(inserted);
        });
  }

  @Override
  public Item update(UUID id, UpdateItem command) {
    var actor = actors.requireOrganizationMaintainer();
    if (id == null
        || command == null
        || command.expectedVersion() == null
        || command.expectedVersion() < 0
        || command.active() == null) throw new IllegalArgumentException("需要字典项、版本及启停状态");
    var label = validLabel(command.label());
    int sortOrder = command.sortOrder() == null ? 0 : command.sortOrder();
    if (sortOrder < 0 || sortOrder > 1_000_000)
      throw new IllegalArgumentException("排序值须在0至1000000之间");
    return tx.execute(
        actor.tenantId(),
        () -> {
          schoolGuard.lock(actor.tenantId());
          var verified = actors.requireOrganizationMaintainerInTransaction();
          var current =
              sql.fetchOne(
                  "select school_id,dictionary_code,item_code,label,sort_order,active,version "
                      + "from organization.dictionary_item where tenant_id=? and id=? for update",
                  actor.tenantId(),
                  id);
          if (current == null) throw new AccessDeniedException("字典项不可访问");
          UUID schoolId = current.get("school_id", UUID.class);
          requireSchool(verified, schoolId);
          schoolGuard.requireActive(schoolId);
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new OrganizationConflict("VERSION_CONFLICT");
          var updated =
              sql.fetchOne(
                  "update organization.dictionary_item set label=?,sort_order=?,active=?,version=version+1 "
                      + "where tenant_id=? and id=? and version=? "
                      + "returning id,school_id,dictionary_code,item_code,label,sort_order,active,version",
                  label,
                  sortOrder,
                  command.active(),
                  actor.tenantId(),
                  id,
                  command.expectedVersion());
          if (updated == null) throw new OrganizationConflict("VERSION_CONFLICT");
          audit.append(
              actor.tenantId(),
              actor.accountId(),
              "ORGANIZATION_DICTIONARY_ITEM_UPDATED",
              id,
              schoolId,
              current.get("label", String.class),
              label,
              "{\"active\":" + current.get("active", Boolean.class) + "}",
              "{\"active\":" + command.active() + ",\"sortOrder\":" + sortOrder + "}");
          return view(updated);
        });
  }

  private static Item view(org.jooq.Record row) {
    return new Item(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("dictionary_code", String.class),
        row.get("item_code", String.class),
        row.get("label", String.class),
        row.get("sort_order", Integer.class),
        row.get("active", Boolean.class),
        row.get("version", Long.class));
  }

  private static void requireSchool(Actor actor, UUID schoolId) {
    if (schoolId == null || !actor.schools().contains(schoolId))
      throw new AccessDeniedException("无学校字典维护或读取权限");
  }

  private static String validCode(String input, String field) {
    if (input == null || !input.matches("[A-Z][A-Z0-9_]{0,63}"))
      throw new IllegalArgumentException(field + "须为大写字母开头的ASCII稳定代码");
    return input;
  }

  private static String validLabel(String input) {
    if (input == null
        || input.isBlank()
        || input.length() > 100
        || input.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("显示名称须为1至100字符且不含控制字符");
    return input.strip();
  }

  private static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String auditText(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
