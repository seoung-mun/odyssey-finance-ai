package com.dacon.core.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** 명시된 로컬 canonical artifact 파일만 비공개 Spring command로 적재한다. */
@Component
@ConditionalOnProperty(name = "app.policy-artifact-path")
public class PolicyArtifactImportCommand implements ApplicationRunner {
  static final long MAX_ARTIFACT_BYTES = 5L * 1024 * 1024;
  private final PolicyArtifactImportService importer;
  private final Path artifactPath;

  public PolicyArtifactImportCommand(
      PolicyArtifactImportService importer,
      @Value("${app.policy-artifact-path}") String artifactPath) {
    this.importer = importer;
    this.artifactPath = Path.of(artifactPath);
  }

  public static void main(String[] arguments) {
    SpringApplication.run(CommandConfiguration.class, arguments);
  }

  @Override
  public void run(ApplicationArguments arguments) throws IOException {
    if (!Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)
        || !Files.isReadable(artifactPath)
        || Files.size(artifactPath) == 0
        || Files.size(artifactPath) > MAX_ARTIFACT_BYTES) {
      throw new IllegalArgumentException("policy artifact path must be a readable regular file");
    }
    importer.importArtifact(Files.readAllBytes(artifactPath));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class CommandConfiguration {
    @Bean
    PolicyArtifactImportService importer(
        jakarta.persistence.EntityManager entityManager,
        com.fasterxml.jackson.databind.ObjectMapper mapper) {
      return new PolicyArtifactImportService(entityManager, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "app.policy-artifact-path")
    PolicyArtifactImportCommand command(
        PolicyArtifactImportService importer,
        @Value("${app.policy-artifact-path}") String artifactPath) {
      return new PolicyArtifactImportCommand(importer, artifactPath);
    }
  }
}
