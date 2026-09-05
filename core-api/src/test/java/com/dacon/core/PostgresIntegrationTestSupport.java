package com.dacon.core;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 실제 PostgreSQL과 migration을 모든 PostgreSQL 테스트에 동일하게 제공한다. */
public abstract class PostgresIntegrationTestSupport {
  static final PostgreSQLContainer<?> POSTGRES = startPostgres();

  private static PostgreSQLContainer<?> startPostgres() {
    PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16.4");
    container.start();
    try {
      container.copyFileToContainer(
          MountableFile.forHostPath(Path.of("../sql/01_schema.sql")), "/tmp/01_schema.sql");
      container.copyFileToContainer(
          MountableFile.forHostPath(Path.of("../sql/02_integrity.sql")), "/tmp/02_integrity.sql");
      var result =
          container.execInContainer(
              "psql",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              container.getUsername(),
              "-d",
              container.getDatabaseName(),
              "-f",
              "/tmp/01_schema.sql");
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(result.getStderr());
      }
    } catch (Exception exception) {
      container.stop();
      throw new IllegalStateException("Testcontainers PostgreSQL 기본 스키마 초기화 실패", exception);
    }
    return container;
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("app.analysis-token", () -> "test-analysis-token");
    registry.add("app.jwt-secret", () -> "test-jwt-secret-test-jwt-secret-32");
  }
}
