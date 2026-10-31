package org.teraschool.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** 真正由 Java Testcontainers 创建第二个临时 PostgreSQL，验证 Windows 到引擎/映射端口的链路。 */
class ContainerRuntimeTest {
  @Test
  void testcontainersCanStartConnectAndDisposePostgres() throws Exception {
    String password = UUID.randomUUID().toString();
    try (var database =
        new GenericContainer<>(DockerImageName.parse(System.getenv("TS_POSTGRES_IMAGE")))
            .withEnv("POSTGRES_PASSWORD", password)
            .withEnv("POSTGRES_DB", "container_probe")
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort())) {
      database.start();
      String url =
          "jdbc:postgresql://"
              + database.getHost()
              + ":"
              + database.getMappedPort(5432)
              + "/container_probe";
      try (var c = DriverManager.getConnection(url, "postgres", password);
          var s = c.createStatement()) {
        var row = s.executeQuery("SELECT current_setting('server_version_num')::integer >= 180000");
        assertThat(row.next()).isTrue();
        assertThat(row.getBoolean(1)).isTrue();
      }
    }
  }
}
