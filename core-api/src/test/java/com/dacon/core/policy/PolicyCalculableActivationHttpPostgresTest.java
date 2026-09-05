package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("e2e")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=false", "app.e2e-token=calculable-e2e-token"})
class PolicyCalculableActivationHttpPostgresTest
    extends com.dacon.core.PostgresIntegrationTestSupport {
  private static final Path INFORMATIONAL =
      Path.of("../data/policy/policy-artifact-informational-approved-23.json");
  private static final Path CALCULABLE =
      Path.of("../data/policy/policy-artifact-calculable-approved-23.json");

  @LocalServerPort private int port;
  @Autowired private PolicyArtifactImportService importer;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void upgradesInformationalCatalogIdempotentlyAndExposesCalculableModes() throws Exception {
    importer.importArtifact(Files.readAllBytes(INFORMATIONAL));
    long first = importer.importArtifact(Files.readAllBytes(CALCULABLE));
    long second = importer.importArtifact(Files.readAllBytes(CALCULABLE));

    assertThat(second).isEqualTo(first);
    assertThat(activeCount("policy_snapshot_versions")).isEqualTo(23);
    assertThat(activeCount("policy_calculation_rules")).isEqualTo(11);
    assertThat(activeRuleCount("ONE_TIME_FUNDING")).isEqualTo(9);
    assertThat(activeRuleCount("MONTHLY_EXPENSE_REDUCTION")).isEqualTo(2);
    assertThat(activeInformationalOnlyCount()).isEqualTo(12);
    assertThat(activeDecisionCount("ALLOW")).isEqualTo(23);
    assertThat(activeDecisionCount("EXCLUDE")).isZero();
    assertThat(activeDecisionCount("RECHECK")).isZero();
    assertThat(p0RuleCount()).isEqualTo(2);

    assertSearch(
        "gwangju-calculable",
        LocalDate.of(1997, 1, 1),
        "12240",
        "MOVING_COST",
        "서구 청년 천원 복비(부동산 중개보수) 지원사업",
        "ONE_TIME_FUNDING");
    assertSearch(
        "iksan-calculable",
        LocalDate.of(1997, 1, 1),
        "52140",
        "MONTHLY_RENT",
        "익산형 청년월세 지원사업",
        "MONTHLY_EXPENSE_REDUCTION");
    assertSearch(
        "jeju-calculable",
        LocalDate.of(1991, 1, 1),
        "50110",
        "MONTHLY_RENT",
        "제주 청년 희망충전 월세 지원",
        "MONTHLY_EXPENSE_REDUCTION");
  }

  private long activeCount(String joinedTable) {
    String join =
        joinedTable.equals("policy_snapshot_versions")
            ? "join policy_snapshot_versions item on item.snapshot_id=snapshot.id"
            : "join policy_snapshot_versions member on member.snapshot_id=snapshot.id "
                + "join policy_calculation_rules item on item.policy_version_id=member.policy_version_id";
    return jdbc.queryForObject(
        "select count(*) from policy_index_snapshots snapshot "
            + join
            + " where snapshot.status='ACTIVE'",
        Long.class);
  }

  private long activeRuleCount(String mode) {
    return jdbc.queryForObject(
        "select count(*) from policy_index_snapshots snapshot "
            + "join policy_snapshot_versions member on member.snapshot_id=snapshot.id "
            + "join policy_calculation_rules rule on rule.policy_version_id=member.policy_version_id "
            + "where snapshot.status='ACTIVE' and rule.adjustment_type=?",
        Long.class,
        mode);
  }

  private long activeInformationalOnlyCount() {
    return jdbc.queryForObject(
        "select count(*) from policy_index_snapshots snapshot "
            + "join policy_snapshot_versions member on member.snapshot_id=snapshot.id "
            + "join policy_versions version on version.id=member.policy_version_id "
            + "left join policy_calculation_rules rule on rule.policy_version_id=version.id "
            + "where snapshot.status='ACTIVE' and version.calculation_mode='ELIGIBILITY_ONLY' "
            + "and rule.policy_version_id is null",
        Long.class);
  }

  private long activeDecisionCount(String decision) {
    return jdbc.queryForObject(
        "select count(*) from policy_index_snapshots snapshot "
            + "join policy_snapshot_versions member on member.snapshot_id=snapshot.id "
            + "join policy_version_application_status status on status.policy_version_id=member.policy_version_id "
            + "where snapshot.status='ACTIVE' and status.decision=?",
        Long.class,
        decision);
  }

  private long p0RuleCount() {
    return jdbc.queryForObject(
        "select count(*) from policy_index_snapshots snapshot "
            + "join policy_snapshot_versions member on member.snapshot_id=snapshot.id "
            + "join policy_calculation_rules rule on rule.policy_version_id=member.policy_version_id "
            + "where snapshot.status='ACTIVE' and rule.adjustment_type='ONE_TIME_FUNDING' "
            + "and rule.amount_upper_bound=300000 "
            + "and rule.golden_case->'p0Simplifications' @> '[{\"code\":\"P0_EXPLICIT_SIMPLIFICATION\"}]'::jsonb",
        Long.class);
  }

  private void assertSearch(
      String username,
      LocalDate birthDate,
      String regionCode,
      String supportGoal,
      String expectedTitle,
      String expectedMode)
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
    assertThat(mapper.readTree(response.body()).path("results"))
        .anySatisfy(
            item -> {
              assertThat(item.path("title").asText()).isEqualTo(expectedTitle);
              assertThat(item.path("calculationMode").asText()).isEqualTo(expectedMode);
            });
  }

  private String login(String username) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/e2e?username=" + username))
                .header("X-E2E-Token", "calculable-e2e-token")
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
