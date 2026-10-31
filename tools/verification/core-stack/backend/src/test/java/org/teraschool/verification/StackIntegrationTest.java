package org.teraschool.verification;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.verification.generated.tables.ProbeDraft.PROBE_DRAFT;
import static org.teraschool.verification.generated.tables.ProbeAudit.PROBE_AUDIT;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 预期来自已确认隔离/契约约束；所有数据只写入脚本创建的临时库。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class StackIntegrationTest {
    @LocalServerPort int port;
    @Autowired DSLContext sql;
    @Autowired DraftService service;
    @Autowired TenantTransactions tx;
    @Autowired @Qualifier("appDataSource") DataSource app;
    final JsonMapper json = JsonMapper.builder().build();

    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

    DraftCommand command() { return new DraftCommand("123456789012345678.123456", LocalDate.of(2026,10,31), null); }
    <T> T as(String name, String role, Supplier<T> work) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(name, "unused", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        var previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
        try { return work.get(); } finally { SecurityContextHolder.setContext(previous); }
    }

    @Test void ordinaryRoleIsNotOwnerAndCannotTruncate() throws Exception {
        try (var connection = app.getConnection(); var statement = connection.createStatement()) {
            var row = statement.executeQuery("SELECT r.rolsuper, r.rolbypassrls, c.relowner=r.oid, c.relrowsecurity, c.relforcerowsecurity, has_table_privilege(current_user, 'probe_draft', 'TRUNCATE') FROM pg_roles r JOIN pg_class c ON c.relname='probe_draft' WHERE r.rolname=current_user");
            assertThat(row.next()).isTrue();
            assertThat(row.getBoolean(1)).isFalse(); assertThat(row.getBoolean(2)).isFalse();
            assertThat(row.getBoolean(3)).isFalse(); assertThat(row.getBoolean(4)).isTrue();
            assertThat(row.getBoolean(5)).isTrue(); assertThat(row.getBoolean(6)).isFalse();
        }
    }

    @Test void missingTenantReadsNothingAndRejectsWrite() throws Exception {
        assertThat(sql.selectCount().from(PROBE_DRAFT).fetchOne(0, int.class)).isZero();
        try (var c = app.getConnection(); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO probe_draft(tenant_id,amount,business_date,happened_at) VALUES ('11111111-1111-4111-8111-111111111111',1,CURRENT_DATE,CURRENT_TIMESTAMP)"))
                .isInstanceOf(java.sql.SQLException.class).extracting(e -> ((java.sql.SQLException)e).getSQLState()).isEqualTo("42501");
        }
    }

    @Test void rlsPreventsCrossTenantReadsUpdatesAndInserts() {
        var a = as("writer-a", "WRITER", () -> service.create(command()));
        assertThat(tx.inTenant(TrustedActor.TENANT_B, () -> sql.fetchCount(PROBE_DRAFT, PROBE_DRAFT.ID.eq(a.id())))).isZero();
        assertThat(tx.inTenant(TrustedActor.TENANT_B, () -> sql.update(PROBE_DRAFT).set(PROBE_DRAFT.NOTE,"wrong tenant").where(PROBE_DRAFT.ID.eq(a.id())).execute())).isZero();
        assertThatThrownBy(() -> tx.inTenant(TrustedActor.TENANT_A, () -> sql.insertInto(PROBE_DRAFT)
            .set(PROBE_DRAFT.TENANT_ID,TrustedActor.TENANT_B).set(PROBE_DRAFT.AMOUNT,java.math.BigDecimal.ONE)
            .set(PROBE_DRAFT.BUSINESS_DATE,LocalDate.now()).set(PROBE_DRAFT.HAPPENED_AT,OffsetDateTime.now()).execute()))
            .satisfies(error -> {
                Throwable cause=error;
                while (cause.getCause()!=null) cause=cause.getCause();
                assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo("42501");
            });
    }

    @Test void transactionRollbackAndPoolReuseDoNotLeakTenantOrData() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> tx.inTenant(TrustedActor.TENANT_A, () -> {
            sql.insertInto(PROBE_DRAFT).set(PROBE_DRAFT.ID,id).set(PROBE_DRAFT.TENANT_ID,TrustedActor.TENANT_A)
                .set(PROBE_DRAFT.AMOUNT,java.math.BigDecimal.ONE).set(PROBE_DRAFT.BUSINESS_DATE,LocalDate.now())
                .set(PROBE_DRAFT.HAPPENED_AT,OffsetDateTime.now()).execute();
            throw new IllegalStateException("模拟提交前失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tx.inTenant(TrustedActor.TENANT_A, () -> sql.fetchCount(PROBE_DRAFT, PROBE_DRAFT.ID.eq(id)))).isZero();
        assertThat(sql.selectCount().from(PROBE_DRAFT).fetchOne(0,int.class)).isZero();
        assertThat(tx.inTenant(TrustedActor.TENANT_B, () -> sql.fetchOne("select current_setting('app.tenant_id')").get(0, String.class))).isEqualTo(TrustedActor.TENANT_B.toString());
        assertThat(sql.selectCount().from(PROBE_DRAFT).fetchOne(0,int.class)).isZero();
    }

    @Test void nestedTransactionCannotSwitchTenant() {
        assertThatThrownBy(() -> tx.inTenant(TrustedActor.TENANT_A, () -> tx.inTenant(TrustedActor.TENANT_B, () -> 1)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("不得切换租户");
    }

    @Test void staleVersionHasZeroAffectedRows() {
        var created = as("writer-a","WRITER",() -> service.create(command()));
        assertThat(tx.inTenant(TrustedActor.TENANT_A, () -> sql.update(PROBE_DRAFT).set(PROBE_DRAFT.VERSION,1)
            .where(PROBE_DRAFT.ID.eq(created.id()).and(PROBE_DRAFT.VERSION.eq(0))).execute())).isOne();
        assertThat(tx.inTenant(TrustedActor.TENANT_A, () -> sql.update(PROBE_DRAFT).set(PROBE_DRAFT.VERSION,2)
            .where(PROBE_DRAFT.ID.eq(created.id()).and(PROBE_DRAFT.VERSION.eq(0))).execute())).isZero();
    }

    @Test void secondEntryEnforcesIdentityPermissionAndInputValidation() {
        assertThatThrownBy(() -> service.create(command())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> as("reader-a","READER", () -> service.create(command()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> as("writer-a","WRITER", () -> service.create(new DraftCommand("1e6",LocalDate.now(),null))))
            .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        var result = as("writer-a","WRITER", () -> service.create(command()));
        assertThat(new java.math.BigDecimal(result.amount())).isEqualByComparingTo(command().amount());
        assertThat(result.id().version()).isEqualTo(7);
    }

    @Test void sessionRoleAndBusinessRoleAreSeparated() throws Exception {
        try (var c = app.getConnection(); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT count(*) FROM infra.spring_session")).isInstanceOf(java.sql.SQLException.class);
        }
        try (var c = DriverManager.getConnection(System.getenv("PROBE_JDBC_URL"),"probe_session",System.getenv("PROBE_SESSION_PASSWORD")); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT count(*) FROM public.probe_draft")).isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test void successfulEntriesAuditTogetherAndRollbackLeavesNeitherFact() throws Exception {
        var c=login("writer-a");
        var response=request(c,"/probe/drafts","POST",body(),csrf(c),"application/json");
        assertThat(response.statusCode()).isEqualTo(201);
        UUID httpId=UUID.fromString(json.readTree(response.body()).get("id").asText());
        var direct=as("writer-a","WRITER",() -> service.create(command()));
        assertThat(tx.inTenant(TrustedActor.TENANT_A, () -> sql.fetchCount(PROBE_AUDIT,
            PROBE_AUDIT.OBJECT_ID.in(httpId,direct.id()).and(PROBE_AUDIT.ACTOR.eq("writer-a"))))).isEqualTo(2);
        assertThat(tx.inTenant(TrustedActor.TENANT_B, () -> sql.fetchCount(PROBE_AUDIT,
            PROBE_AUDIT.OBJECT_ID.in(httpId,direct.id())))).isZero();
        var aborted=new java.util.concurrent.atomic.AtomicReference<UUID>();
        assertThatThrownBy(() -> as("writer-a","WRITER", () -> tx.inTenant(TrustedActor.TENANT_A, () -> {
            aborted.set(service.create(command()).id());
            throw new IllegalStateException("模拟业务完成但事务尚未提交时失败");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(tx.inTenant(TrustedActor.TENANT_A, () -> sql.fetchCount(PROBE_DRAFT,PROBE_DRAFT.ID.eq(aborted.get())))).isZero();
        assertThat(tx.inTenant(TrustedActor.TENANT_A, () -> sql.fetchCount(PROBE_AUDIT,PROBE_AUDIT.OBJECT_ID.eq(aborted.get())))).isZero();
    }

    HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); }
    HttpResponse<String> request(HttpClient c, String path, String method, String body, String token, String type) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("X-CSRF-TOKEN",token);
        if (type != null) builder.header("Content-Type",type);
        return c.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String csrf(HttpClient c) throws Exception { return json.readTree(request(c,"/probe/csrf","GET",null,null,null).body()).get("token").asText(); }
    HttpClient login(String user) throws Exception {
        var c=client(); var token=csrf(c);
        String form="username=" + user + "&password=" + URLEncoder.encode(System.getenv("PROBE_FIXTURE_PASSWORD"),StandardCharsets.UTF_8);
        assertThat(request(c,"/login","POST",form,token,"application/x-www-form-urlencoded").statusCode()).isEqualTo(302);
        assertThat(request(c,"/probe/drafts","GET",null,null,null).statusCode()).isEqualTo(200);
        return c;
    }
    String body() { return "{\"amount\":\"123456789012345678.123456\",\"businessDate\":\"2026-10-31\",\"note\":null}"; }

    @Test void httpRequiresLoginAndCsrfAndPersistsJdbcSession() throws Exception {
        assertThat(request(client(),"/probe/drafts","GET",null,null,null).statusCode()).isEqualTo(401);
        var c=login("writer-a");
        assertThat(request(c,"/probe/drafts","POST",body(),null,"application/json").statusCode()).isEqualTo(403);
        assertThat(request(c,"/probe/drafts","POST",body(),csrf(c),"application/json").statusCode()).isEqualTo(201);
        try (var connection=DriverManager.getConnection(System.getenv("PROBE_JDBC_URL"),"postgres",System.getenv("PROBE_ADMIN_PASSWORD")); var s=connection.createStatement()) {
            var row=s.executeQuery("SELECT count(*) FROM infra.spring_session WHERE principal_name='writer-a'");
            row.next(); assertThat(row.getInt(1)).isPositive();
        }
    }

    @Test void httpTenantAndWritePermissionMatchApplicationEntry() throws Exception {
        var a=login("writer-a");
        var created=request(a,"/probe/drafts","POST",body(),csrf(a),"application/json");
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).get("id").asText();
        assertThat(request(login("writer-b"),"/probe/drafts","GET",null,null,null).body()).doesNotContain(id);
        var reader=login("reader-a");
        var denied=request(reader,"/probe/drafts","POST",body(),csrf(reader),"application/json");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        assertThat(json.readTree(denied.body()).get("code").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test void jsonCannotCoerceNumericAmountOrSupplyTenant() throws Exception {
        var c=login("writer-a");
        for (String amount : List.of("123", "1.25", "true")) {
            var invalid=request(c,"/probe/drafts","POST","{\"amount\":" + amount + ",\"businessDate\":\"2026-10-31\"}",csrf(c),"application/json");
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(json.readTree(invalid.body()).get("code").asText()).isEqualTo("INVALID_INPUT");
        }
        String forged=body().replace("\"note\":null", "\"note\":null,\"tenantId\":\"22222222-2222-4222-8222-222222222222\"");
        assertThat(request(c,"/probe/drafts","POST",forged,csrf(c),"application/json").statusCode()).isEqualTo(400);
    }

    @Test void realJsonAndExportedOpenapiAgreeOnMoneyDateAndNullable() throws Exception {
        var c=login("writer-a");
        var response=request(c,"/probe/drafts","POST",body(),csrf(c),"application/json");
        assertThat(response.statusCode()).isEqualTo(201);
        var actual=json.readTree(response.body());
        assertThat(actual.get("amount").isString()).isTrue();
        assertThat(actual.get("businessDate").asText()).isEqualTo("2026-10-31");
        assertThat(actual.get("happenedAt").asText()).endsWith("Z");
        assertThat(actual.get("note").isNull()).isTrue();
        var export=request(c,"/v3/api-docs","GET",null,null,null);
        assertThat(export.statusCode()).isEqualTo(200);
        JsonNode schema=json.readTree(export.body());
        assertThat(schema.at("/components/schemas/DraftView/properties/amount/type").asText()).isEqualTo("string");
        assertThat(schema.at("/components/schemas/DraftView/properties/businessDate/format").asText()).isEqualTo("date");
        assertThat(schema.at("/components/schemas/DraftView/properties/note/nullable").asBoolean()).isTrue();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"),export.body());
    }
}
