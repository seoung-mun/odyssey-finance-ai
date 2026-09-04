package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("e2e")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=false", "app.e2e-token=informational-e2e-token"})
@EnabledIfEnvironmentVariable(named = "REAL_POSTGRES_URL", matches = ".+")
class PolicyInformationalExposureHttpPostgresTest {
  private static final Path ARTIFACT =
      Path.of("../data/policy/policy-artifact-informational-approved-23.json");

  @LocalServerPort private int port;
  @Autowired private PolicyArtifactImportService importer;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void importInformationalCatalog() throws Exception {
    importer.importArtifact(Files.readAllBytes(ARTIFACT));
  }

  @Test
  void presetProfilesExposeRepresentativePoliciesWithoutCalculableActions() throws Exception {
    assertSearch(
        "gwangju", LocalDate.of(1997, 1, 1), "12240", "MOVING_COST", "서구 청년 천원 복비(부동산 중개보수) 지원사업");
    assertSearch("iksan", LocalDate.of(1997, 1, 1), "52140", "MONTHLY_RENT", "익산형 청년월세 지원사업");
    assertSearch("jeju", LocalDate.of(1991, 1, 1), "50110", "MONTHLY_RENT", "제주 청년 희망충전 월세 지원");

    assertThat(jdbc.queryForObject("select count(*) from policy_calculation_rules", Long.class))
        .isZero();
  }

  private void assertSearch(
      String username,
      LocalDate birthDate,
      String regionCode,
      String supportGoal,
      String expectedTitle)
      throws Exception {
    String token = login(username);
    jdbc.update(
        "insert into user_profiles(user_id,birth_date,region_code) select user_id,?,? from social_accounts where provider_subject=? on conflict (user_id) do update set birth_date=excluded.birth_date,region_code=excluded.region_code",
        birthDate,
        regionCode,
        "e2e:" + username);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(uri("/api/v1/policies/search"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"supportGoal\":\"" + supportGoal + "\",\"answers\":[]}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode results = mapper.readTree(response.body()).path("results");
    assertThat(results.size()).isBetween(1, 3);
    assertThat(results)
        .anySatisfy(item -> assertThat(item.path("title").asText()).isEqualTo(expectedTitle));
    assertThat(results)
        .allSatisfy(
            item -> {
              assertThat(item.path("calculationMode").asText()).isEqualTo("INFORMATIONAL");
              assertThat(item.path("source").path("officialUrl").asText()).isNotBlank();
            });
  }

  private String login(String username) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/e2e?username=" + username))
                .header("X-E2E-Token", "informational-e2e-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return mapper.readTree(response.body()).path("accessToken").asText();
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
