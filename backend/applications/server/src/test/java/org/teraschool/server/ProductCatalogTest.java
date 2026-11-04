package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.product.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 课程、产品与价格版本：真实 PostgreSQL 下的金额表示、发布不可改写、权限分离与第二入口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCatalogTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER_TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER_SCHOOL = UUID.randomUUID(),
      MAINTAINER = UUID.randomUUID(),
      PUBLISHER = UUID.randomUUID(),
      READER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired ProductCommands commands;
  @Autowired ProductQueries queries;
  @Autowired org.jooq.DSLContext sql;
  @Autowired TenantTransactions tx;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update(
        "insert into identity.tenant(id,name) values (?,?),(?,?)",
        TENANT,
        "产品机构甲",
        OTHER_TENANT,
        "产品机构乙");
    for (var id : new UUID[] {SCHOOL, OTHER_SCHOOL})
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL',?)",
          id,
          TENANT,
          id,
          id.equals(SCHOOL) ? "产品学校甲" : "产品学校乙");
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    for (var entry :
        Map.of(
                MAINTAINER,
                "product-maintainer",
                PUBLISHER,
                "product-publisher",
                READER,
                "product-reader")
            .entrySet())
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          entry.getValue(),
          encoder.encode(PASSWORD));
    for (var action : new String[] {"PRODUCT_READ", "PRODUCT_MAINTAIN"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
          TENANT,
          MAINTAINER,
          SCHOOL,
          action);
    for (var action : new String[] {"PRODUCT_READ", "PRICE_PUBLISH"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
          TENANT,
          PUBLISHER,
          SCHOOL,
          action);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'PRODUCT_READ','ALLOW')",
        TENANT,
        READER,
        SCHOOL);
    // 维护者还需要能“看见”另一个学校，才能验证课程归属校验而不是被范围直接挡住。
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'PRODUCT_READ','ALLOW')",
        TENANT,
        MAINTAINER,
        OTHER_SCHOOL);
  }

  <T> T asActor(UUID account, Supplier<T> work) {
    var old = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            EngineeringTest.principal(account), null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(old);
    }
  }

  Curriculum curriculum(String name) {
    return asActor(
        MAINTAINER,
        () ->
            commands.createCurriculum(
                new CreateCurriculum(UUID.randomUUID(), SCHOOL, name, "常规", "英语")));
  }

  CourseProduct product(String name, UUID curriculumId) {
    return asActor(
        MAINTAINER,
        () ->
            commands.createProduct(
                new CreateProduct(UUID.randomUUID(), SCHOOL, curriculumId, name, 1)));
  }

  PriceVersion draft(UUID productId, String price, String hours, String from, String to) {
    return asActor(
        MAINTAINER,
        () ->
            commands.draftPriceVersion(
                new DraftPriceVersion(
                    UUID.randomUUID(), productId, price, hours, "0", 1, null, from, to)));
  }

  PriceVersion publish(UUID id, long version) {
    return asActor(PUBLISHER, () -> commands.publishPriceVersion(id, new ExpectedVersion(version)));
  }

  long count(String table, UUID resource) throws Exception {
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement("select count(*) from " + table + " where resource_id=?")) {
      s.setObject(1, resource);
      var r = s.executeQuery();
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void curriculumCreationIsIdempotentAndNameUnique() throws Exception {
    var input = new CreateCurriculum(UUID.randomUUID(), SCHOOL, "少儿英语", "常规", "英语");
    var first = asActor(MAINTAINER, () -> commands.createCurriculum(input));
    assertThat(asActor(MAINTAINER, () -> commands.createCurriculum(input)).id())
        .isEqualTo(first.id());
    assertThatThrownBy(
            () ->
                asActor(
                    MAINTAINER,
                    () ->
                        commands.createCurriculum(
                            new CreateCurriculum(UUID.randomUUID(), SCHOOL, "少儿英语", null, null))))
        .isInstanceOf(ProductConflict.class)
        .hasMessage("DUPLICATE_CURRICULUM_NAME");
    var changed = new CreateCurriculum(input.requestId(), SCHOOL, "改名课程", "常规", "英语");
    assertThatThrownBy(() -> asActor(MAINTAINER, () -> commands.createCurriculum(changed)))
        .isInstanceOf(ProductConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(count("audit.product_event", first.id())).isEqualTo(1);
  }

  @Test
  void productNeedsCurriculumFromSameSchool() throws Exception {
    var curriculum = curriculum("数学思维");
    var product = product("数学思维年度班", curriculum.id());
    assertThat(product.curriculumName()).isEqualTo("数学思维");
    var otherCurriculumId = UUID.randomUUID();
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'外校课程')",
        otherCurriculumId,
        TENANT,
        OTHER_SCHOOL);
    assertThatThrownBy(
            () ->
                asActor(
                    MAINTAINER,
                    () ->
                        commands.createProduct(
                            new CreateProduct(
                                UUID.randomUUID(), SCHOOL, otherCurriculumId, "跨校产品", 1))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
        .hasMessageContaining("课程不属于该学校");
  }

  @Test
  void productGroupsAreSchoolScopedVersionedAndDoNotRewritePriceSnapshots() {
    var groupRequest = new CreateProductGroup(UUID.randomUUID(), SCHOOL, "秋季班");
    var group = asActor(MAINTAINER, () -> commands.createProductGroup(groupRequest));
    assertThat(asActor(MAINTAINER, () -> commands.createProductGroup(groupRequest)).id())
        .isEqualTo(group.id());
    var curriculum = curriculum("产品组测试课程");
    var product = product("产品组测试产品", curriculum.id());
    var draft = draft(product.id(), "900", "9", "2026-01-01", null);
    var assigned =
        asActor(
            MAINTAINER,
            () ->
                commands.changeProductGroup(
                    product.id(), new ChangeProductGroup(product.version(), group.id())));
    assertThat(assigned.groupId()).isEqualTo(group.id());
    var catalog = asActor(MAINTAINER, () -> queries.catalog(SCHOOL));
    assertThat(catalog.groups()).extracting(ProductGroup::name).contains("秋季班");
    assertThat(catalog.products())
        .filteredOn(p -> p.id().equals(product.id()))
        .singleElement()
        .extracting(CourseProduct::groupName)
        .isEqualTo("秋季班");
    assertThat(asActor(MAINTAINER, () -> queries.priceVersions(product.id())))
        .extracting(PriceVersion::id)
        .containsExactly(draft.id());
    var cleared =
        asActor(
            MAINTAINER,
            () ->
                commands.clearProductGroup(product.id(), new ExpectedVersion(assigned.version())));
    assertThat(cleared.groupId()).isNull();
    assertThatThrownBy(
            () ->
                asActor(
                    MAINTAINER,
                    () ->
                        commands.changeProductGroup(
                            product.id(), new ChangeProductGroup(product.version(), null))))
        .isInstanceOf(ProductConflict.class)
        .hasMessage("VERSION_CONFLICT");
  }

  @Test
  void priceDraftNormalisesMoneyAndRejectsInvalidInput() {
    var curriculum = curriculum("物理");
    var product = product("物理全年班", curriculum.id());
    var draft = draft(product.id(), "1000", "10", "2026-01-01", "2026-12-31");
    assertThat(draft.price()).isEqualTo("1000.00");
    assertThat(draft.hours()).isEqualTo("10.00");
    assertThat(draft.status()).isEqualTo("DRAFT");
    assertThatThrownBy(() -> draft(product.id(), "1000.005", "10", "2026-01-01", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> draft(product.id(), "0", "10", "2026-01-01", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> draft(product.id(), "100", "0", "2026-01-01", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> draft(product.id(), "100", "10", "2026-12-31", "2026-01-01"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> draft(product.id(), "-1", "10", "2026-01-01", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void priceDraftAllowsServiceFeeOnlyShapeAndRejectsZeroValueProducts() {
    var curriculum = curriculum("纯服务费课程目录");
    var product = product("纯服务费产品", curriculum.id());
    var serviceOnly =
        asActor(
            MAINTAINER,
            () ->
                commands.draftPriceVersion(
                    new DraftPriceVersion(
                        UUID.randomUUID(),
                        product.id(),
                        "0",
                        "0",
                        "150",
                        1,
                        null,
                        "2026-01-01",
                        null)));
    assertThat(serviceOnly.price()).isEqualTo("0.00");
    assertThat(serviceOnly.hours()).isEqualTo("0.00");
    assertThat(serviceOnly.serviceFee()).isEqualTo("150.00");

    assertThatThrownBy(
            () ->
                asActor(
                    MAINTAINER,
                    () ->
                        commands.draftPriceVersion(
                            new DraftPriceVersion(
                                UUID.randomUUID(),
                                product.id(),
                                "0",
                                "0",
                                "0",
                                1,
                                null,
                                "2026-01-01",
                                null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("仅服务费");
  }

  @Test
  void publishingNeedsSeparatePermission() {
    var curriculum = curriculum("化学");
    var product = product("化学冲刺班", curriculum.id());
    var draft = draft(product.id(), "2000", "20", "2026-01-01", null);
    assertThatThrownBy(
            () ->
                asActor(
                    MAINTAINER,
                    () ->
                        commands.publishPriceVersion(
                            draft.id(), new ExpectedVersion(draft.version()))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(publish(draft.id(), draft.version()).status()).isEqualTo("PUBLISHED");
  }

  @Test
  void publishedPriceCannotBeEdited() {
    var curriculum = curriculum("生物");
    var product = product("生物提高班", curriculum.id());
    var draft = draft(product.id(), "3000", "30", "2026-01-01", null);
    var published = publish(draft.id(), draft.version());
    assertThatThrownBy(
            () ->
                asActor(
                    MAINTAINER,
                    () ->
                        commands.updatePriceVersion(
                            published.id(),
                            new UpdatePriceVersion(
                                published.version(),
                                "3100",
                                "30",
                                "0",
                                1,
                                null,
                                "2026-01-01",
                                null))))
        .isInstanceOf(ProductConflict.class)
        .hasMessage("PRICE_VERSION_IMMUTABLE");
  }

  @Test
  void publishingSupersedesPreviousVersion() {
    var curriculum = curriculum("历史");
    var product = product("历史常年班", curriculum.id());
    var first = publish(draft(product.id(), "1000", "10", "2026-01-01", null).id(), 0L);
    var secondDraft = draft(product.id(), "1100", "10", "2026-03-01", null);
    var second = publish(secondDraft.id(), secondDraft.version());
    var versions = asActor(READER, () -> queries.priceVersions(product.id()));
    assertThat(versions)
        .extracting(PriceVersion::status)
        .containsExactlyInAnyOrder("RETIRED", "PUBLISHED");
    var current =
        asActor(READER, () -> queries.catalog(SCHOOL)).products().stream()
            .filter(item -> item.id().equals(product.id()))
            .findFirst()
            .orElseThrow();
    assertThat(current.publishedPrice()).isNotNull();
    assertThat(current.publishedPrice().id()).isEqualTo(second.id());
    assertThat(current.publishedPrice().price()).isEqualTo("1100.00");
    assertThat(first.id()).isNotEqualTo(second.id());
  }

  @Test
  void sellableDependsOnWindowAndStatus() {
    var curriculum = curriculum("地理");
    var product = product("地理短期班", curriculum.id());
    var draft = draft(product.id(), "800", "8", "2026-02-01", "2026-02-28");
    var published = publish(draft.id(), draft.version());
    assertThat(asActor(READER, () -> queries.sellable(SCHOOL, "2026-02-10")))
        .extracting(SellablePrice::productId)
        .contains(product.id());
    assertThat(asActor(READER, () -> queries.sellable(SCHOOL, "2026-03-10"))).isEmpty();
    assertThatThrownBy(() -> asActor(READER, () -> queries.sellable(SCHOOL, "2026/02/10")))
        .isInstanceOf(IllegalArgumentException.class);
    asActor(
        PUBLISHER,
        () ->
            commands.retirePriceVersion(published.id(), new ExpectedVersion(published.version())));
    assertThat(asActor(READER, () -> queries.sellable(SCHOOL, "2026-02-10"))).isEmpty();
  }

  @Test
  void stalePublishVersionConflicts() {
    var curriculum = curriculum("政治");
    var product = product("政治冲刺班", curriculum.id());
    var draft = draft(product.id(), "900", "9", "2026-01-01", null);
    assertThatThrownBy(
            () ->
                asActor(
                    PUBLISHER,
                    () -> commands.publishPriceVersion(draft.id(), new ExpectedVersion(99L))))
        .isInstanceOf(ProductConflict.class)
        .hasMessage("VERSION_CONFLICT");
  }

  @Test
  void denyOverridesAllowAndReaderCannotWrite() throws Exception {
    var curriculum = curriculum("美术");
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        commands.createCurriculum(
                            new CreateCurriculum(UUID.randomUUID(), SCHOOL, "只读课程", null, null))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> asActor(READER, () -> queries.catalog(OTHER_SCHOOL)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'PRODUCT_READ','DENY')",
        TENANT,
        READER,
        SCHOOL);
    try {
      // DENY 后该校不再可读：目录查询按范围拒绝，而不是返回空集合。
      assertThatThrownBy(() -> asActor(READER, () -> queries.catalog(SCHOOL)))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThat(asActor(READER, () -> queries.capabilities()).canRead()).isTrue();
    } finally {
      update(
          "delete from identity.account_permission where account_id=? and action='PRODUCT_READ' and effect='DENY'",
          READER);
    }
    assertThat(curriculum.id()).isNotNull();
  }

  @Test
  void crossTenantWriteRejectedByRls() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    OTHER_TENANT,
                    () ->
                        sql.execute(
                            "insert into product.curriculum(tenant_id,school_id,name) values (?,?,'越租户课程')",
                            TENANT,
                            SCHOOL)))
        .satisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("42501");
            });
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(HttpClient client, String path, String body, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(
                "Content-Type",
                path.endsWith("login") ? "application/x-www-form-urlencoded" : "application/json");
    if (token != null) builder.header("X-CSRF-TOKEN", token);
    return client.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void httpKeepsDecimalStringsAndSharesRules() throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var json = JsonMapper.builder().build();
    var csrf = json.readTree(get(client, "/api/session/csrf").body());
    assertThat(
            post(
                    client,
                    "/api/session/login",
                    "username=product-publisher&password=" + PASSWORD,
                    csrf.get("token").asText())
                .statusCode())
        .isEqualTo(204);
    var token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    // 只有发布权限的账号不能建课程：功能权限不被发布动作替代。
    var body =
        json.writeValueAsString(
            new CreateCurriculum(UUID.randomUUID(), SCHOOL, "接口课程", "常规", "英语"));
    assertThat(post(client, "/api/product/curricula", body, token).statusCode()).isEqualTo(403);
    // 只读目录返回空集合且不报错。
    var catalog = get(client, "/api/product/catalog?schoolId=" + SCHOOL);
    assertThat(catalog.statusCode()).isEqualTo(200);
    var sellable =
        json.readTree(
            get(client, "/api/product/sellable?schoolId=" + SCHOOL + "&on=2026-02-10").body());
    assertThat(sellable.isArray()).isTrue();
    // 金额与课时以字符串返回，前端不得按普通数字计算。
    var curriculum = curriculum("接口课程甲");
    var product = product("接口产品甲", curriculum.id());
    var draft = draft(product.id(), "1000", "10", "2026-02-01", "2026-02-28");
    publish(draft.id(), draft.version());
    var rows =
        json.readTree(
            get(client, "/api/product/sellable?schoolId=" + SCHOOL + "&on=2026-02-10").body());
    tools.jackson.databind.JsonNode row = null;
    for (var candidate : rows)
      if (candidate.get("productId").asText().equals(product.id().toString())) row = candidate;
    assertThat(row).isNotNull();
    assertThat(row.get("price").isTextual()).isTrue();
    assertThat(row.get("price").asText()).isEqualTo("1000.00");
    assertThat(row.get("hours").asText()).isEqualTo("10.00");
    assertThat(row.get("settlementMode").asInt()).isEqualTo(1);
  }
}
