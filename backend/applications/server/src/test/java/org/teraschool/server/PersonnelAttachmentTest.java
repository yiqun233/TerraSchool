package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
class PersonnelAttachmentTest {
  static final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  static final HttpServer store = startStore();
  @Autowired PersonnelAttachments attachments;
  @Autowired PersonnelManagement personnel;
  @Autowired AccountLifecycle accounts;
  @Autowired ResourceManagement resources;
  @Autowired TenantTransactions tx;
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  AuthorizationManagementTest f;
  UUID employee;

  static HttpServer startStore() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            String key = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("PUT")) {
              objects.put(key, exchange.getRequestBody().readAllBytes());
              exchange.getResponseHeaders().add("ETag", "\"fake-etag\"");
              exchange.sendResponseHeaders(200, -1);
            } else if (exchange.getRequestMethod().equals("DELETE")) {
              objects.remove(key);
              exchange.sendResponseHeaders(204, -1);
            } else if (exchange.getRequestMethod().equals("GET") && objects.containsKey(key)) {
              byte[] bytes = objects.get(key);
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            } else exchange.sendResponseHeaders(404, -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException error) {
      throw new IllegalStateException(error);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
    r.add("teraschool.storage.endpoint", () -> "http://127.0.0.1:" + store.getAddress().getPort());
    r.add("teraschool.storage.region", () -> "us-east-1");
    r.add("teraschool.storage.bucket", () -> "isolated-test");
    r.add("teraschool.storage.access-key", () -> "test-only");
    r.add("teraschool.storage.secret-key", () -> "test-only-secret");
  }

  @BeforeEach
  void seed() throws Exception {
    f = new AuthorizationManagementTest();
    f.seed();
    for (String action :
        List.of("EMPLOYEE_READ", "EMPLOYEE_MAINTAIN", "PERSONNEL_READ", "PERSONNEL_MAINTAIN"))
      f.grant(f.root, action, f.a, "ALLOW");
    employee =
        resources
            .create(
                Kind.EMPLOYEE,
                new CreateResource(UUID.randomUUID(), f.a, "附件员工", null, null, null, null))
            .id();
  }

  @AfterEach
  void clear() {
    f.clear();
    objects.clear();
  }

  @AfterAll
  static void closeStore() {
    store.stop(0);
  }

  @Test
  void privateAttachmentIsIdempotentAndDoesNotExposeObjectKey() {
    var input =
        new PersonnelAttachments.UploadAttachment(
            UUID.randomUUID(), "档案.txt", "验收内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                attachments.createAttachment(
                    employee,
                    new PersonnelAttachments.UploadAttachment(
                        UUID.randomUUID(), "../escape.txt", new byte[] {1})))
        .isInstanceOf(IllegalArgumentException.class);
    var created = attachments.createAttachment(employee, input);
    assertThat(attachments.createAttachment(employee, input)).isEqualTo(created);
    assertThat(attachments.listAttachments(employee)).containsExactly(created);
    assertThat(attachments.readAttachment(employee, created.id()).content())
        .isEqualTo(input.content());
    assertThat(created.toString()).doesNotContain("teraschool/personnel/");
    assertThatThrownBy(
            () ->
                attachments.createAttachment(
                    employee,
                    new PersonnelAttachments.UploadAttachment(
                        input.requestId(), "不同名称", input.content())))
        .isInstanceOf(OrganizationConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(attachments.createAttachment(employee, input)).isEqualTo(created);
    f.as(f.target);
    assertThatThrownBy(() -> attachments.readAttachment(employee, created.id()))
        .isInstanceOf(AccessDeniedException.class);
    f.as(f.root);
    attachments.removeAttachment(
        employee, created.id(), new PersonnelAttachments.RemoveAttachment(0L));
    assertThat(attachments.listAttachments(employee)).isEmpty();
    assertThatThrownBy(() -> attachments.readAttachment(employee, created.id()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void outerTransactionRevocationAlsoBlocksPersonnelAndAttachmentRead() {
    var input =
        new PersonnelAttachments.UploadAttachment(
            UUID.randomUUID(), "档案.txt", new byte[] {1, 2, 3});
    var attachment = attachments.createAttachment(employee, input);
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      accounts.revokeSessions(
                          f.root, new AccountLifecycle.RevokeAccountSessions(0L));
                      attachments.readAttachment(employee, attachment.id());
                      return null;
                    }))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                tx.execute(
                    f.tenant,
                    () -> {
                      accounts.revokeSessions(
                          f.root, new AccountLifecycle.RevokeAccountSessions(0L));
                      personnel.getPersonnel(employee);
                      return null;
                    }))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void staleUnreferencedUploadIsReconciledWithoutDeletingCommittedFiles() throws Exception {
    var saved =
        attachments.createAttachment(
            employee,
            new PersonnelAttachments.UploadAttachment(
                UUID.randomUUID(), "保留.txt", new byte[] {8, 9}));
    UUID staleId = UUID.randomUUID();
    String key = "teraschool/personnel/" + f.tenant + "/" + employee + "/" + f.root + "/" + staleId;
    objects.put("/isolated-test/" + key, new byte[] {4, 5});
    EngineeringTest.update(
        "insert into organization.personnel_upload_pending(tenant_id,actor_id,request_id,employee_id,object_key,original_name,sha256,touched_at) values (?,?,?,?,?,'orphan',?,current_timestamp-interval '25 hours')",
        f.tenant,
        f.root,
        staleId,
        employee,
        key,
        "a".repeat(64));
    attachments.createAttachment(
        employee,
        new PersonnelAttachments.UploadAttachment(UUID.randomUUID(), "触发清理.txt", new byte[] {1}));
    assertThat(objects).doesNotContainKey("/isolated-test/" + key);
    assertThat(
            f.count(
                "select count(*) from organization.personnel_upload_pending where request_id=?",
                staleId))
        .isZero();
    assertThat(attachments.readAttachment(employee, saved.id()).content())
        .containsExactly((byte) 8, (byte) 9);
  }

  @Test
  void concurrentSameRequestReturnsSameAttachment() throws Exception {
    var input =
        new PersonnelAttachments.UploadAttachment(UUID.randomUUID(), "并发.txt", new byte[] {3, 4});
    var start = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<PersonnelAttachments.AttachmentView> work =
          () -> {
            f.as(f.root);
            try {
              start.await();
              return attachments.createAttachment(employee, input);
            } finally {
              f.clear();
            }
          };
      var first = pool.submit(work);
      var second = pool.submit(work);
      start.countDown();
      assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(second.get(30, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void httpMultipartRequiresCsrfAndDownloadsPrivateBytes() throws Exception {
    var helper = new AccountLifecycleTest();
    helper.port = port;
    var client = helper.client();
    assertThat(helper.login(client, f.root, AuthorizationManagementTest.PASSWORD).statusCode())
        .isEqualTo(204);
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    var token =
        mapper.readTree(helper.get(client, "/api/session/csrf").body()).get("token").asText();
    String boundary = "boundary" + UUID.randomUUID().toString().replace("-", "");
    String body =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\nContent-Type: text/plain\r\n\r\nhello-private\r\n--"
            + boundary
            + "--\r\n";
    String path = "/api/organization/employees/" + employee + "/attachments";
    var rejected =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://127.0.0.1:" + port + path + "?requestId=" + UUID.randomUUID()))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
    assertThat(
            client.send(rejected, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(403);
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://127.0.0.1:" + port + path + "?requestId=" + UUID.randomUUID()))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("X-CSRF-TOKEN", token)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
    var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    String id = mapper.readTree(response.body()).get("id").asText();
    var get =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + path + "/" + id))
            .GET()
            .build();
    var downloaded = client.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(downloaded.statusCode()).isEqualTo(200);
    assertThat(downloaded.body()).isEqualTo("hello-private");
    assertThat(downloaded.headers().firstValue("Content-Disposition"))
        .hasValueSatisfying(value -> assertThat(value).startsWith("attachment;"));
  }
}
