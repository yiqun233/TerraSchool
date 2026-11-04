package org.teraschool.product.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.product.api.*;

/**
 * 课程、产品与价格版本的完整用例。
 *
 * <p>价格版本发布后不可改写：改价只能新增版本再发布，报名因此总能引用到稳定的价格与课时快照。金额与课时以 十进制字符串进入、按 BigDecimal 校验存储，接口不接受二进制浮点作为权威值。
 */
@Service
public class ProductCatalogService implements ProductQueries, ProductCommands {
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
  private static final BigDecimal MAX_HOURS = new BigDecimal("9999999.99");
  private static final java.util.regex.Pattern DECIMAL =
      java.util.regex.Pattern.compile("\\d{1,12}(\\.\\d{1,2})?");
  private static final int MAX_ROWS = 500;

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;

  public ProductCatalogService(
      CurrentActor actors, TenantTransactions tx, DSLContext sql, SchoolAccess schools) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
  }

  // ---------------------------------------------------------------- 查询

  @Override
  public ProductCapabilities capabilities() {
    var scope = actors.requireResourceScope("PRODUCT");
    boolean canPublish = hasPublishPermission();
    if (scope.readableSchoolIds().isEmpty())
      return new ProductCapabilities(true, false, canPublish, List.of());
    return tx.execute(
        scope.tenantId(),
        () -> {
          var names = schools.names(scope.readableSchoolIds());
          var options =
              scope.readableSchoolIds().stream()
                  .filter(names::containsKey)
                  .sorted(
                      Comparator.comparing((UUID key) -> names.get(key)).thenComparing(key -> key))
                  .map(
                      key ->
                          new ProductCapabilities.ProductSchoolOption(
                              key, names.get(key), scope.maintainableSchoolIds().contains(key)))
                  .toList();
          return new ProductCapabilities(
              true,
              options.stream().anyMatch(ProductCapabilities.ProductSchoolOption::maintainable),
              canPublish,
              options);
        });
  }

  @Override
  public ProductCatalog catalog(UUID schoolId) {
    var scope = actors.requireResourceScope("PRODUCT");
    requireReadable(scope, schoolId);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var school = schools.require(schoolId);
          var curricula =
              sql.fetch(
                      "select id,school_id,name,category,subject,active,version from product.curriculum"
                          + " where school_id=? order by name, id limit ?",
                      schoolId,
                      MAX_ROWS)
                  .map(
                      row ->
                          new Curriculum(
                              row.get("id", UUID.class),
                              row.get("school_id", UUID.class),
                              school.name(),
                              row.get("name", String.class),
                              row.get("category", String.class),
                              row.get("subject", String.class),
                              row.get("active", Boolean.class),
                              row.get("version", Long.class)));
          var groups =
              sql.fetch(
                      "select id,school_id,name,active,version from product.product_group"
                          + " where school_id=? order by name,id limit ?",
                      schoolId,
                      MAX_ROWS)
                  .map(
                      row ->
                          new ProductGroup(
                              row.get("id", UUID.class),
                              schoolId,
                              school.name(),
                              row.get("name", String.class),
                              row.get("active", Boolean.class),
                              row.get("version", Long.class)));
          var published = new HashMap<UUID, PriceVersion>();
          for (var row :
              sql.fetch(
                  "select pv.id,pv.product_id,pv.version_no,pv.price,pv.hours,pv.service_fee,"
                      + "pv.settlement_mode,pv.study_days,pv.effective_from,pv.effective_to,pv.status,"
                      + "pv.version from product.price_version pv"
                      + " join product.product p on p.id=pv.product_id"
                      + " where p.school_id=? and pv.status='PUBLISHED'",
                  schoolId)) published.put(row.get("product_id", UUID.class), priceView(row));
          var products =
              sql.fetch(
                      "select p.id,p.school_id,p.curriculum_id,p.group_id,p.name,p.settlement_mode,p.active,"
                          + "p.version,c.name as curriculum_name,g.name as group_name"
                          + " from product.product p join product.curriculum c on c.id=p.curriculum_id"
                          + " left join product.product_group g on g.id=p.group_id"
                          + " where p.school_id=? order by p.name, p.id limit ?",
                      schoolId,
                      MAX_ROWS)
                  .map(
                      row ->
                          new CourseProduct(
                              row.get("id", UUID.class),
                              row.get("school_id", UUID.class),
                              school.name(),
                              row.get("curriculum_id", UUID.class),
                              row.get("curriculum_name", String.class),
                              row.get("group_id", UUID.class),
                              row.get("group_name", String.class),
                              row.get("name", String.class),
                              row.get("settlement_mode", Integer.class),
                              row.get("active", Boolean.class),
                              row.get("version", Long.class),
                              published.get(row.get("id", UUID.class))));
          return new ProductCatalog(schoolId, curricula, groups, products);
        });
  }

  @Override
  public List<PriceVersion> priceVersions(UUID productId) {
    if (productId == null) throw new IllegalArgumentException("需要产品");
    var scope = actors.requireResourceScope("PRODUCT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var product = requireProduct(scope, productId);
          return sql.fetch(
                  "select id,product_id,version_no,price,hours,service_fee,settlement_mode,study_days,"
                      + "effective_from,effective_to,status,version from product.price_version"
                      + " where product_id=? order by version_no desc limit ?",
                  product.get("id", UUID.class),
                  MAX_ROWS)
              .map(this::priceView);
        });
  }

  @Override
  public List<SellablePrice> sellable(UUID schoolId, String on) {
    var scope = actors.requireResourceScope("PRODUCT");
    requireReadable(scope, schoolId);
    var date = businessDate(on, "可售日期");
    return tx.execute(
        scope.tenantId(),
        () ->
            sql.fetch(
                    "select p.id as product_id,p.name as product_name,p.school_id,c.id as curriculum_id,"
                        + "c.name as curriculum_name,pv.id as price_version_id,pv.price,pv.hours,"
                        + "pv.service_fee,pv.settlement_mode,pv.study_days,pv.effective_from,"
                        + "pv.effective_to from product.price_version pv"
                        + " join product.product p on p.id=pv.product_id"
                        + " join product.curriculum c on c.id=p.curriculum_id"
                        + " where p.school_id=? and p.active and c.active and pv.status='PUBLISHED'"
                        + " and pv.effective_from<=? and (pv.effective_to is null or pv.effective_to>=?)"
                        + " order by c.name, p.name limit ?",
                    schoolId,
                    date,
                    date,
                    MAX_ROWS)
                .map(
                    row ->
                        new SellablePrice(
                            row.get("product_id", UUID.class),
                            row.get("product_name", String.class),
                            row.get("school_id", UUID.class),
                            row.get("curriculum_id", UUID.class),
                            row.get("curriculum_name", String.class),
                            row.get("price_version_id", UUID.class),
                            money(row.get("price", BigDecimal.class)),
                            money(row.get("hours", BigDecimal.class)),
                            money(row.get("service_fee", BigDecimal.class)),
                            row.get("settlement_mode", Integer.class),
                            row.get("study_days", Integer.class),
                            date(row.get("effective_from", LocalDate.class)),
                            date(row.get("effective_to", LocalDate.class)))));
  }

  // ---------------------------------------------------------------- 课程

  @Override
  public ProductGroup createProductGroup(CreateProductGroup command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createProductGroupWithTrace(command);
    }
  }

  private ProductGroup createProductGroupWithTrace(CreateProductGroup command) {
    if (command == null || command.requestId() == null || command.schoolId() == null)
      throw new IllegalArgumentException("需要请求身份和学校");
    var name = text(command.name(), 100, "产品组名称");
    if (name == null) throw new IllegalArgumentException("产品组名称必填");
    var scope = actors.requireResourceScope("PRODUCT");
    requireMaintainable(scope, command.schoolId());
    var fingerprint = fingerprint("PRODUCT_GROUP", name);
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("PRODUCT");
          requireMaintainable(verified, command.schoolId());
          var school = schools.require(command.schoolId());
          if (!school.active()) throw new AccessDeniedException("学校已停用，不能新增产品组");
          var previous = previousRequest(scope, command.requestId(), fingerprint, "PRODUCT_GROUP");
          if (previous != null)
            return productGroupView(previous, command.schoolId(), school.name());
          var id = UUID.randomUUID();
          try {
            sql.execute(
                "insert into product.product_group(id,tenant_id,school_id,name) values (?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                name);
          } catch (RuntimeException error) {
            if (uniqueViolation(error)) throw new ProductConflict("DUPLICATE_PRODUCT_GROUP_NAME");
            throw error;
          }
          rememberRequest(scope, command.requestId(), "PRODUCT_GROUP", id, fingerprint);
          audit(scope, "PRODUCT_GROUP_CREATED", id, command.schoolId(), "");
          return productGroupView(id, command.schoolId(), school.name());
        });
  }

  @Override
  public ProductGroup updateProductGroup(UUID id, UpdateProductGroup command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return updateProductGroupWithTrace(id, command);
    }
  }

  private ProductGroup updateProductGroupWithTrace(UUID id, UpdateProductGroup command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要产品组和版本");
    var name = text(command.name(), 100, "产品组名称");
    if (name == null) throw new IllegalArgumentException("产品组名称必填");
    var scope = actors.requireResourceScope("PRODUCT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var current =
              requireProductGroup(actors.requireResourceScopeInTransaction("PRODUCT"), id);
          var schoolId = current.get("school_id", UUID.class);
          requireMaintainable(actors.requireResourceScopeInTransaction("PRODUCT"), schoolId);
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能修改产品组");
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new ProductConflict("VERSION_CONFLICT");
          var active = command.active() == null || command.active();
          try {
            if (sql.execute(
                    "update product.product_group set name=?,active=?,version=version+1 where id=? and version=?",
                    name,
                    active,
                    id,
                    command.expectedVersion())
                != 1) throw new ProductConflict("VERSION_CONFLICT");
          } catch (RuntimeException error) {
            if (uniqueViolation(error)) throw new ProductConflict("DUPLICATE_PRODUCT_GROUP_NAME");
            throw error;
          }
          var changed = new LinkedHashSet<String>();
          if (!Objects.equals(current.get("name", String.class), name)) changed.add("name");
          if (!current.get("active", Boolean.class).equals(active)) changed.add("active");
          audit(scope, "PRODUCT_GROUP_UPDATED", id, schoolId, String.join(",", changed));
          return productGroupView(id, schoolId, schools.require(schoolId).name());
        });
  }

  @Override
  public CourseProduct changeProductGroup(UUID id, ChangeProductGroup command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return changeProductGroupWithTrace(id, command);
    }
  }

  private CourseProduct changeProductGroupWithTrace(UUID id, ChangeProductGroup command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要产品和版本");
    var scope = actors.requireResourceScope("PRODUCT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("PRODUCT");
          var current = requireProduct(verified, id);
          var schoolId = current.get("school_id", UUID.class);
          requireMaintainable(verified, schoolId);
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能修改产品");
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new ProductConflict("VERSION_CONFLICT");
          if (command.groupId() != null) {
            var group = requireProductGroup(verified, command.groupId());
            if (!schoolId.equals(group.get("school_id", UUID.class)))
              throw new AccessDeniedException("产品组不属于该学校");
            if (!group.get("active", Boolean.class))
              throw new ProductConflict("PRODUCT_GROUP_INACTIVE");
          }
          var oldGroup = current.get("group_id", UUID.class);
          if (Objects.equals(oldGroup, command.groupId()))
            return productView(id, schoolId, schools.require(schoolId).name());
          if (sql.execute(
                  "update product.product set group_id=?,version=version+1 where id=? and version=?",
                  command.groupId(),
                  id,
                  command.expectedVersion())
              != 1) throw new ProductConflict("VERSION_CONFLICT");
          audit(scope, "PRODUCT_GROUP_ASSIGNED", id, schoolId, "group_id");
          return productView(id, schoolId, schools.require(schoolId).name());
        });
  }

  @Override
  public CourseProduct clearProductGroup(UUID id, ExpectedVersion command) {
    if (command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要产品版本");
    return changeProductGroup(id, new ChangeProductGroup(command.expectedVersion(), null));
  }

  @Override
  public Curriculum createCurriculum(CreateCurriculum command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createCurriculumWithTrace(command);
    }
  }

  private Curriculum createCurriculumWithTrace(CreateCurriculum command) {
    if (command == null || command.requestId() == null || command.schoolId() == null)
      throw new IllegalArgumentException("需要请求身份和学校");
    var name = text(command.name(), 100, "课程名称");
    if (name == null) throw new IllegalArgumentException("课程名称必填");
    var category = text(command.category(), 50, "课程类型");
    var subject = text(command.subject(), 50, "科目");
    var scope = actors.requireResourceScope("PRODUCT");
    requireMaintainable(scope, command.schoolId());
    var fingerprint = fingerprint("CURRICULUM", name, category, subject);
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("PRODUCT");
          requireMaintainable(verified, command.schoolId());
          var school = schools.require(command.schoolId());
          if (!school.active()) throw new AccessDeniedException("学校已停用，不能新增课程");
          var previous = previousRequest(scope, command.requestId(), fingerprint, "CURRICULUM");
          if (previous != null) return curriculumView(previous, command.schoolId(), school.name());
          var id = UUID.randomUUID();
          try {
            sql.execute(
                "insert into product.curriculum(id,tenant_id,school_id,name,category,subject)"
                    + " values (?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                name,
                category,
                subject);
          } catch (RuntimeException error) {
            if (uniqueViolation(error)) throw new ProductConflict("DUPLICATE_CURRICULUM_NAME");
            throw error;
          }
          rememberRequest(scope, command.requestId(), "CURRICULUM", id, fingerprint);
          audit(scope, "CURRICULUM_CREATED", id, command.schoolId(), "");
          return curriculumView(id, command.schoolId(), school.name());
        });
  }

  @Override
  public Curriculum updateCurriculum(UUID id, UpdateCurriculum command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return updateCurriculumWithTrace(id, command);
    }
  }

  private Curriculum updateCurriculumWithTrace(UUID id, UpdateCurriculum command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要课程和版本");
    var name = text(command.name(), 100, "课程名称");
    if (name == null) throw new IllegalArgumentException("课程名称必填");
    var category = text(command.category(), 50, "课程类型");
    var subject = text(command.subject(), 50, "科目");
    var active = command.active() == null || command.active();
    var scope = actors.requireResourceScope("PRODUCT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("PRODUCT");
          var current = requireCurriculum(verified, id);
          var schoolId = current.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无课程维护权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能修改课程");
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new ProductConflict("VERSION_CONFLICT");
          int updated;
          try {
            updated =
                sql.execute(
                    "update product.curriculum set name=?,category=?,subject=?,active=?,"
                        + "version=version+1 where id=? and version=?",
                    name,
                    category,
                    subject,
                    active,
                    id,
                    command.expectedVersion());
          } catch (RuntimeException error) {
            if (uniqueViolation(error)) throw new ProductConflict("DUPLICATE_CURRICULUM_NAME");
            throw error;
          }
          if (updated != 1) throw new ProductConflict("VERSION_CONFLICT");
          var changed = new LinkedHashSet<String>();
          if (!Objects.equals(current.get("name", String.class), name)) changed.add("name");
          if (!Objects.equals(current.get("category", String.class), category))
            changed.add("category");
          if (!Objects.equals(current.get("subject", String.class), subject))
            changed.add("subject");
          if (!current.get("active", Boolean.class).equals(active)) changed.add("active");
          audit(scope, "CURRICULUM_UPDATED", id, schoolId, String.join(",", changed));
          return curriculumView(id, schoolId, schools.require(schoolId).name());
        });
  }

  // ---------------------------------------------------------------- 产品

  @Override
  public CourseProduct createProduct(CreateProduct command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createProductWithTrace(command);
    }
  }

  private CourseProduct createProductWithTrace(CreateProduct command) {
    if (command == null
        || command.requestId() == null
        || command.schoolId() == null
        || command.curriculumId() == null) throw new IllegalArgumentException("需要请求身份、学校和课程");
    var name = text(command.name(), 100, "产品名称");
    if (name == null) throw new IllegalArgumentException("产品名称必填");
    var mode = settlementMode(command.settlementMode(), 1);
    var scope = actors.requireResourceScope("PRODUCT");
    requireMaintainable(scope, command.schoolId());
    var fingerprint = fingerprint("PRODUCT", name, command.curriculumId().toString(), "" + mode);
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("PRODUCT");
          requireMaintainable(verified, command.schoolId());
          var school = schools.require(command.schoolId());
          if (!school.active()) throw new AccessDeniedException("学校已停用，不能新增产品");
          var previous = previousRequest(scope, command.requestId(), fingerprint, "PRODUCT");
          if (previous != null) return productView(previous, command.schoolId(), school.name());
          var curriculum = requireCurriculum(verified, command.curriculumId());
          if (!command.schoolId().equals(curriculum.get("school_id", UUID.class)))
            throw new AccessDeniedException("课程不属于该学校");
          var id = UUID.randomUUID();
          try {
            sql.execute(
                "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
                    + " values (?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                command.curriculumId(),
                name,
                mode);
          } catch (RuntimeException error) {
            if (uniqueViolation(error)) throw new ProductConflict("DUPLICATE_PRODUCT_NAME");
            throw error;
          }
          rememberRequest(scope, command.requestId(), "PRODUCT", id, fingerprint);
          audit(scope, "PRODUCT_CREATED", id, command.schoolId(), "");
          return productView(id, command.schoolId(), school.name());
        });
  }

  @Override
  public CourseProduct updateProduct(UUID id, UpdateProduct command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return updateProductWithTrace(id, command);
    }
  }

  private CourseProduct updateProductWithTrace(UUID id, UpdateProduct command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要产品和版本");
    var name = text(command.name(), 100, "产品名称");
    if (name == null) throw new IllegalArgumentException("产品名称必填");
    var scope = actors.requireResourceScope("PRODUCT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("PRODUCT");
          var current = requireProduct(verified, id);
          var schoolId = current.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无产品维护权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能修改产品");
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new ProductConflict("VERSION_CONFLICT");
          var mode =
              settlementMode(
                  command.settlementMode(), current.get("settlement_mode", Integer.class));
          var active = command.active() == null || command.active();
          int updated;
          try {
            updated =
                sql.execute(
                    "update product.product set name=?,settlement_mode=?,active=?,version=version+1"
                        + " where id=? and version=?",
                    name,
                    mode,
                    active,
                    id,
                    command.expectedVersion());
          } catch (RuntimeException error) {
            if (uniqueViolation(error)) throw new ProductConflict("DUPLICATE_PRODUCT_NAME");
            throw error;
          }
          if (updated != 1) throw new ProductConflict("VERSION_CONFLICT");
          var changed = new LinkedHashSet<String>();
          if (!Objects.equals(current.get("name", String.class), name)) changed.add("name");
          if (!current.get("settlement_mode", Integer.class).equals(mode))
            changed.add("settlement_mode");
          if (!current.get("active", Boolean.class).equals(active)) changed.add("active");
          audit(scope, "PRODUCT_UPDATED", id, schoolId, String.join(",", changed));
          return productView(id, schoolId, schools.require(schoolId).name());
        });
  }

  // ---------------------------------------------------------------- 价格版本

  @Override
  public PriceVersion draftPriceVersion(DraftPriceVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return draftPriceVersionWithTrace(command);
    }
  }

  private PriceVersion draftPriceVersionWithTrace(DraftPriceVersion command) {
    if (command == null || command.requestId() == null || command.productId() == null)
      throw new IllegalArgumentException("需要请求身份和产品");
    var price = amount(command.price(), "价格", true);
    var hours = amount(command.hours(), "课时", true);
    if (hours.compareTo(MAX_HOURS) > 0) throw new IllegalArgumentException("课时超出范围");
    var fee =
        command.serviceFee() == null ? BigDecimal.ZERO : amount(command.serviceFee(), "服务费", true);
    requirePriceShape(price, hours, fee);
    studyDays(command.studyDays());
    var from = businessDate(command.effectiveFrom(), "生效日期");
    var to = optionalDate(command.effectiveTo(), "失效日期");
    if (to != null && to.isBefore(from)) throw new IllegalArgumentException("失效日期不能早于生效日期");
    var scope = actors.requireResourceScope("PRODUCT");
    var requestedMode = command.settlementMode();
    var fingerprint =
        fingerprint(
            "PRICE_VERSION",
            price.toPlainString(),
            hours.toPlainString(),
            fee.toPlainString() + "|" + from + "|" + to + "|" + requestedMode);
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("PRODUCT");
          var product = requireProduct(verified, command.productId());
          var schoolId = product.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无价格维护权限");
          if (!schools.require(schoolId).active())
            throw new AccessDeniedException("学校已停用，不能新增价格版本");
          var previous = previousRequest(scope, command.requestId(), fingerprint, "PRICE_VERSION");
          if (previous != null) return priceView(requirePrice(previous));
          var mode = settlementMode(requestedMode, product.get("settlement_mode", Integer.class));
          var nextNo =
              sql.fetchOne(
                      "select coalesce(max(version_no),0)+1 as next from product.price_version where product_id=?",
                      command.productId())
                  .get("next", Integer.class);
          var id = UUID.randomUUID();
          sql.execute(
              "insert into product.price_version(id,tenant_id,product_id,version_no,price,hours,"
                  + "service_fee,settlement_mode,study_days,effective_from,effective_to)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?)",
              id,
              scope.tenantId(),
              command.productId(),
              nextNo,
              price,
              hours,
              fee,
              mode,
              command.studyDays(),
              from,
              to);
          rememberRequest(scope, command.requestId(), "PRICE_VERSION", id, fingerprint);
          audit(scope, "PRICE_VERSION_DRAFTED", id, schoolId, "");
          return priceView(requirePrice(id));
        });
  }

  @Override
  public PriceVersion updatePriceVersion(UUID id, UpdatePriceVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return updatePriceVersionWithTrace(id, command);
    }
  }

  private PriceVersion updatePriceVersionWithTrace(UUID id, UpdatePriceVersion command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要价格版本和版本号");
    var price = amount(command.price(), "价格", true);
    var hours = amount(command.hours(), "课时", true);
    if (hours.compareTo(MAX_HOURS) > 0) throw new IllegalArgumentException("课时超出范围");
    var fee =
        command.serviceFee() == null ? BigDecimal.ZERO : amount(command.serviceFee(), "服务费", true);
    requirePriceShape(price, hours, fee);
    studyDays(command.studyDays());
    var from = businessDate(command.effectiveFrom(), "生效日期");
    var to = optionalDate(command.effectiveTo(), "失效日期");
    if (to != null && to.isBefore(from)) throw new IllegalArgumentException("失效日期不能早于生效日期");
    var scope = actors.requireResourceScope("PRODUCT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("PRODUCT");
          var current = requirePrice(id);
          var product = requireProduct(verified, current.get("product_id", UUID.class));
          var schoolId = product.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无价格维护权限");
          if (!schools.require(schoolId).active())
            throw new AccessDeniedException("学校已停用，不能修改价格版本");
          if (!"DRAFT".equals(current.get("status", String.class)))
            throw new ProductConflict("PRICE_VERSION_IMMUTABLE");
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new ProductConflict("VERSION_CONFLICT");
          var mode =
              settlementMode(
                  command.settlementMode(), current.get("settlement_mode", Integer.class));
          int updated =
              sql.execute(
                  "update product.price_version set price=?,hours=?,service_fee=?,settlement_mode=?,"
                      + "study_days=?,effective_from=?,effective_to=?,version=version+1"
                      + " where id=? and version=? and status='DRAFT'",
                  price,
                  hours,
                  fee,
                  mode,
                  command.studyDays(),
                  from,
                  to,
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new ProductConflict("VERSION_CONFLICT");
          var changed = new LinkedHashSet<String>();
          if (current.get("price", BigDecimal.class).compareTo(price) != 0) changed.add("price");
          if (current.get("hours", BigDecimal.class).compareTo(hours) != 0) changed.add("hours");
          if (current.get("service_fee", BigDecimal.class).compareTo(fee) != 0)
            changed.add("service_fee");
          audit(scope, "PRICE_VERSION_UPDATED", id, schoolId, String.join(",", changed));
          return priceView(requirePrice(id));
        });
  }

  @Override
  public PriceVersion publishPriceVersion(UUID id, ExpectedVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return publishPriceVersionWithTrace(id, command);
    }
  }

  private PriceVersion publishPriceVersionWithTrace(UUID id, ExpectedVersion command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要价格版本和版本号");
    var publish = actors.requireAction("PRICE_PUBLISH");
    return tx.execute(
        publish.tenantId(),
        () -> {
          schools.lockTenant(publish.tenantId());
          var verified = actors.requireActionInTransaction("PRICE_PUBLISH");
          var current = requirePrice(id);
          var product = requireProductVisible(current.get("product_id", UUID.class));
          var schoolId = product.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的定价发布权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能发布价格");
          if (!"DRAFT".equals(current.get("status", String.class)))
            throw new ProductConflict("PRICE_VERSION_NOT_DRAFT");
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new ProductConflict("VERSION_CONFLICT");
          // 结算模式 0 是"自动"：进班时按班型解析成 1（VIP）或 2（大班/普通），其余 1–5 直接用公式。
          var mode = current.get("settlement_mode", Integer.class);
          if (mode == null || mode < 0 || mode > 5)
            throw new ProductConflict("SETTLEMENT_MODE_UNSUPPORTED");
          // 同一产品只保留一个已发布版本：先作废旧版本，旧版本仍保留为历史。
          var existing =
              sql.fetchOne(
                  "select id from product.price_version where product_id=? and status='PUBLISHED' for update",
                  current.get("product_id", UUID.class));
          var supersededId = existing == null ? null : existing.get("id", UUID.class);
          var superseded =
              existing == null
                  ? 0
                  : sql.execute(
                      "update product.price_version set status='RETIRED',version=version+1"
                          + " where id=? and status='PUBLISHED'",
                      supersededId);
          if (superseded > 0)
            audit(publish, "PRICE_VERSION_RETIRED", supersededId, schoolId, "superseded");
          int updated =
              sql.execute(
                  "update product.price_version set status='PUBLISHED',published_at=CURRENT_TIMESTAMP,"
                      + "version=version+1 where id=? and version=? and status='DRAFT'",
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new ProductConflict("VERSION_CONFLICT");
          audit(publish, "PRICE_VERSION_PUBLISHED", id, schoolId, "");
          return priceView(requirePrice(id));
        });
  }

  @Override
  public PriceVersion retirePriceVersion(UUID id, ExpectedVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return retirePriceVersionWithTrace(id, command);
    }
  }

  private PriceVersion retirePriceVersionWithTrace(UUID id, ExpectedVersion command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要价格版本和版本号");
    var publish = actors.requireAction("PRICE_PUBLISH");
    return tx.execute(
        publish.tenantId(),
        () -> {
          schools.lockTenant(publish.tenantId());
          var verified = actors.requireActionInTransaction("PRICE_PUBLISH");
          var current = requirePrice(id);
          var product = requireProductVisible(current.get("product_id", UUID.class));
          var schoolId = product.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的定价发布权限");
          if (!"PUBLISHED".equals(current.get("status", String.class)))
            throw new ProductConflict("PRICE_VERSION_NOT_PUBLISHED");
          if (!current.get("version", Long.class).equals(command.expectedVersion()))
            throw new ProductConflict("VERSION_CONFLICT");
          int updated =
              sql.execute(
                  "update product.price_version set status='RETIRED',version=version+1"
                      + " where id=? and version=? and status='PUBLISHED'",
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new ProductConflict("VERSION_CONFLICT");
          audit(publish, "PRICE_VERSION_RETIRED", id, schoolId, "");
          return priceView(requirePrice(id));
        });
  }

  // ---------------------------------------------------------------- 内部

  /** 发布权限与查看/维护分开判断：没有该动作时页面只显示只读信息。 */
  private boolean hasPublishPermission() {
    try {
      return !actors.requireAction("PRICE_PUBLISH").schools().isEmpty();
    } catch (AccessDeniedException denied) {
      return false;
    }
  }

  private void requireReadable(ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的课程产品读取权限");
  }

  private void requireMaintainable(ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的课程产品读取权限");
    if (!scope.maintainableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的课程产品维护权限");
  }

  private Record requireCurriculum(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,name,category,subject,active,version from product.curriculum where id=?",
            id);
    if (row == null || !scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("课程不存在或不可访问");
    return row;
  }

  private Record requireProduct(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,curriculum_id,group_id,name,settlement_mode,active,version from product.product"
                + " where id=?",
            id);
    if (row == null || !scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("产品不存在或不可访问");
    return row;
  }

  private Record requireProductVisible(UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,curriculum_id,group_id,name,settlement_mode,active,version from product.product"
                + " where id=?",
            id);
    if (row == null) throw new AccessDeniedException("产品不存在或不可访问");
    return row;
  }

  private Record requirePrice(UUID id) {
    var row =
        sql.fetchOne(
            "select id,product_id,version_no,price,hours,service_fee,settlement_mode,study_days,"
                + "effective_from,effective_to,status,version from product.price_version where id=?",
            id);
    if (row == null) throw new AccessDeniedException("价格版本不存在或不可访问");
    return row;
  }

  private Curriculum curriculumView(UUID id, UUID schoolId, String schoolName) {
    var row =
        sql.fetchOne(
            "select id,name,category,subject,active,version from product.curriculum where id=?",
            id);
    if (row == null) throw new AccessDeniedException("课程不存在或不可访问");
    return new Curriculum(
        row.get("id", UUID.class),
        schoolId,
        schoolName,
        row.get("name", String.class),
        row.get("category", String.class),
        row.get("subject", String.class),
        row.get("active", Boolean.class),
        row.get("version", Long.class));
  }

  private Record requireProductGroup(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,name,active,version from product.product_group where id=?", id);
    if (row == null || !scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("产品组不存在或不可访问");
    return row;
  }

  private ProductGroup productGroupView(UUID id, UUID schoolId, String schoolName) {
    var row =
        sql.fetchOne("select id,name,active,version from product.product_group where id=?", id);
    if (row == null) throw new AccessDeniedException("产品组不存在或不可访问");
    return new ProductGroup(
        id,
        schoolId,
        schoolName,
        row.get("name", String.class),
        row.get("active", Boolean.class),
        row.get("version", Long.class));
  }

  private CourseProduct productView(UUID id, UUID schoolId, String schoolName) {
    var row =
        sql.fetchOne(
            "select p.id,p.curriculum_id,p.group_id,p.name,p.settlement_mode,p.active,p.version,c.name as curriculum_name,g.name as group_name"
                + " from product.product p join product.curriculum c on c.id=p.curriculum_id"
                + " left join product.product_group g on g.id=p.group_id"
                + " where p.id=?",
            id);
    if (row == null) throw new AccessDeniedException("产品不存在或不可访问");
    var published =
        sql.fetchOne(
            "select id,product_id,version_no,price,hours,service_fee,settlement_mode,study_days,"
                + "effective_from,effective_to,status,version from product.price_version"
                + " where product_id=? and status='PUBLISHED'",
            id);
    return new CourseProduct(
        row.get("id", UUID.class),
        schoolId,
        schoolName,
        row.get("curriculum_id", UUID.class),
        row.get("curriculum_name", String.class),
        row.get("group_id", UUID.class),
        row.get("group_name", String.class),
        row.get("name", String.class),
        row.get("settlement_mode", Integer.class),
        row.get("active", Boolean.class),
        row.get("version", Long.class),
        published == null ? null : priceView(published));
  }

  private PriceVersion priceView(Record row) {
    return new PriceVersion(
        row.get("id", UUID.class),
        row.get("product_id", UUID.class),
        row.get("version_no", Integer.class),
        money(row.get("price", BigDecimal.class)),
        money(row.get("hours", BigDecimal.class)),
        money(row.get("service_fee", BigDecimal.class)),
        row.get("settlement_mode", Integer.class),
        row.get("study_days", Integer.class),
        date(row.get("effective_from", LocalDate.class)),
        date(row.get("effective_to", LocalDate.class)),
        row.get("status", String.class),
        row.get("version", Long.class));
  }

  private UUID previousRequest(
      ResourceScope scope, UUID requestId, String fingerprint, String kind) {
    var row =
        sql.fetchOne(
            "select resource_id,fingerprint,kind from product.creation_request"
                + " where tenant_id=? and actor_id=? and request_id=?",
            scope.tenantId(),
            scope.accountId(),
            requestId);
    if (row == null) return null;
    if (!fingerprint.equals(row.get("fingerprint", String.class))
        || !kind.equals(row.get("kind", String.class)))
      throw new ProductConflict("IDEMPOTENCY_MISMATCH");
    return row.get("resource_id", UUID.class);
  }

  private void rememberRequest(
      ResourceScope scope, UUID requestId, String kind, UUID resource, String fingerprint) {
    // 请求身份与业务值同事务：进程内锁之外仍由主键保证只写一次。
    sql.execute(
        "insert into product.creation_request(tenant_id,actor_id,request_id,kind,resource_id,fingerprint)"
            + " values (?,?,?,?,?,?)",
        scope.tenantId(),
        scope.accountId(),
        requestId,
        kind,
        resource,
        fingerprint);
  }

  private boolean uniqueViolation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sqlError && "23505".equals(sqlError.getSQLState()))
        return true;
    return false;
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
  }

  private void studyDays(Integer value) {
    if (value != null && (value < 1 || value > 3650))
      throw new IllegalArgumentException("学习期限须为 1–3650 天");
  }

  private int settlementMode(Integer value, int fallback) {
    if (value == null) return fallback;
    if (value < 0 || value > 5) throw new IllegalArgumentException("结算模式须为 0–5");
    return value;
  }

  private BigDecimal amount(String value, String label, boolean allowZero) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "必填");
    var text = value.strip();
    if (!DECIMAL.matcher(text).matches())
      throw new IllegalArgumentException(label + "须为最多两位小数的十进制数");
    var amount = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    if (amount.signum() < 0 || (!allowZero && amount.signum() == 0))
      throw new IllegalArgumentException(label + "必须大于零");
    if (amount.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException(label + "超出范围");
    return amount;
  }

  private void requirePriceShape(BigDecimal price, BigDecimal hours, BigDecimal serviceFee) {
    if (price.signum() == 0 && hours.signum() == 0 && serviceFee.signum() > 0) return;
    if (price.signum() <= 0 || hours.signum() <= 0)
      throw new IllegalArgumentException("课程价格与课时须同时大于零；仅服务费价格要求两者均为零且服务费大于零");
    if (price.compareTo(serviceFee) < 0) throw new IllegalArgumentException("服务费不能高于课程价格");
  }

  private LocalDate optionalDate(String value, String label) {
    if (value == null || value.isBlank()) return null;
    return businessDate(value, label);
  }

  private LocalDate businessDate(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "必填");
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException(label + "须为 YYYY-MM-DD");
    }
  }

  private String date(LocalDate value) {
    return value == null ? null : value.toString();
  }

  private String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private String fingerprint(String kind, String... parts) {
    var text = kind + "\u001f" + String.join("\u001f", parts);
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

  private void audit(Actor actor, String action, UUID resource, UUID school, String fields) {
    sql.execute(
        "insert into audit.product_event(tenant_id,actor_id,action,resource_id,school_id,changed_fields,trace_id)"
            + " values (?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        action,
        resource,
        school,
        fields,
        RequestTrace.id());
  }

  private void audit(
      ResourceScope scope, String action, UUID resource, UUID school, String fields) {
    audit(scope.reader(), action, resource, school, fields);
  }
}
