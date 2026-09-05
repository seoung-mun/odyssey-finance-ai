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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("e2e")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=false", "app.e2e-token=benefit-e2e-token"})
@EnabledIfEnvironmentVariable(named = "REAL_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolicyBenefitHttpPostgresTest {
  private static final Path INFORMATIONAL =
      Path.of("../data/policy/policy-artifact-informational-approved-23.json");
  private static final Path CALCULABLE =
      Path.of("../data/policy/policy-artifact-calculable-approved-23.json");

  @LocalServerPort private int port;
  @Autowired private PolicyArtifactImportService importer;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  void importApprovedCatalog() throws Exception {
    importer.importArtifact(Files.readAllBytes(INFORMATIONAL));
    importer.importArtifact(Files.readAllBytes(CALCULABLE));
  }

  @Test
  void confirmsListsConflictsCancelsAndReconfirmsOneTimeBenefit() throws Exception {
    Policy oneTime = policy("ONE_TIME_FUNDING");
    User user = user("one", oneTime);
    int goalId = goal(user.id());

    HttpResponse<String> created =
        confirm(user.token(), oneTime.id(), goalId, true, 300_000, "2026-09", null);
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode benefit = mapper.readTree(created.body());
    long benefitId = benefit.path("id").asLong();
    assertThat(benefit.path("adjustmentType").asText()).isEqualTo("ONE_TIME_FUNDING");
    assertThat(benefit.path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from policy_benefits where id=? and amount_won=300000 and start_year_month=date '2026-09-01' and end_year_month is null and status='CONFIRMED'",
                Long.class,
                benefitId))
        .isEqualTo(1);

    HttpResponse<String> retry =
        confirm(user.token(), oneTime.id(), goalId, true, 300_000, "2026-09", null);
    assertThat(retry.statusCode()).isEqualTo(201);
    assertThat(mapper.readTree(retry.body()).path("id").asLong()).isEqualTo(benefitId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from policy_benefits where user_id=? and goal_id=? and policy_version_id=?",
                Long.class,
                user.id(),
                goalId,
                oneTime.id()))
        .isEqualTo(1);

    assertThat(
            confirm(user.token(), oneTime.id(), goalId, true, 299_999, "2026-09", null)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            confirm(user.token(), oneTime.id(), goalId, true, 300_000, "2026-10", null)
                .statusCode())
        .isEqualTo(409);
    assertThat(list(user.token(), goalId).path(0).path("id").asLong()).isEqualTo(benefitId);

    User other = user("cancel-owner", oneTime);
    assertThat(
            send("DELETE", "/api/v1/policy-benefits/" + benefitId, other.token(), null)
                .statusCode())
        .isEqualTo(404);
    JsonNode cancelled = delete(user.token(), benefitId);
    assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
    assertThat(delete(user.token(), benefitId).path("id").asLong()).isEqualTo(benefitId);
    assertThat(list(user.token(), goalId).isEmpty()).isTrue();

    JsonNode reconfirmed =
        mapper.readTree(
            confirm(user.token(), oneTime.id(), goalId, true, 300_000, "2026-09", null).body());
    assertThat(reconfirmed.path("id").asLong()).isNotEqualTo(benefitId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from policy_benefits where user_id=? and goal_id=? and policy_version_id=? and status='CONFIRMED'",
                Long.class,
                user.id(),
                goalId,
                oneTime.id()))
        .isEqualTo(1);
  }

  @Test
  void concurrentExactConfirmationConvergesToOneBenefit() throws Exception {
    Policy oneTime = policy("ONE_TIME_FUNDING");
    User user = user("concurrent", oneTime);
    int goalId = goal(user.id());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var request =
          (java.util.concurrent.Callable<HttpResponse<String>>)
              () -> {
                ready.countDown();
                start.await();
                return confirm(user.token(), oneTime.id(), goalId, true, 300_000, "2026-09", null);
              };
      var first = executor.submit(request);
      var second = executor.submit(request);
      ready.await();
      start.countDown();
      HttpResponse<String> firstResponse = first.get();
      HttpResponse<String> secondResponse = second.get();

      assertThat(firstResponse.statusCode()).isEqualTo(201);
      assertThat(secondResponse.statusCode()).isEqualTo(201);
      assertThat(mapper.readTree(firstResponse.body()).path("id").asLong())
          .isEqualTo(mapper.readTree(secondResponse.body()).path("id").asLong());
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from policy_benefits where user_id=? and goal_id=? and policy_version_id=? and status='CONFIRMED'",
                  Long.class,
                  user.id(),
                  goalId,
                  oneTime.id()))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void validatesConfirmationOwnershipRuleCapsAndMonthlyPeriods() throws Exception {
    Policy oneTime = policy("ONE_TIME_FUNDING");
    Policy monthly = policy("MONTHLY_EXPENSE_REDUCTION");
    User user = user("validation", monthly);
    int goalId = goal(user.id());

    HttpResponse<String> monthlyCreated =
        confirm(
            user.token(),
            monthly.id(),
            goalId,
            true,
            Math.min(monthly.cap(), 100_000),
            "2026-09",
            "2027-08");
    assertThat(monthlyCreated.statusCode()).isEqualTo(201);
    delete(user.token(), mapper.readTree(monthlyCreated.body()).path("id").asLong());
    int anotherGoal = goal(user("other", monthly).id());
    assertThat(
            confirm(user.token(), monthly.id(), anotherGoal, true, 1, "2026-09", "2026-09")
                .statusCode())
        .isEqualTo(404);
    assertThat(confirm(user.token(), oneTime.id(), goalId, false, 1, "2026-09", null).statusCode())
        .isEqualTo(400);
    assertThat(confirm(user.token(), oneTime.id(), goalId, true, 0, "2026-09", null).statusCode())
        .isEqualTo(400);
    assertThat(confirmWithoutStart(user.token(), oneTime.id(), goalId).statusCode()).isEqualTo(400);
    assertThat(
            confirm(user.token(), oneTime.id(), goalId, true, 300_001, "2026-09", null)
                .statusCode())
        .isEqualTo(400);

    assertThat(confirm(user.token(), monthly.id(), goalId, true, 1, "2026-09", null).statusCode())
        .isEqualTo(400);
    assertThat(
            confirm(user.token(), monthly.id(), goalId, true, 1, "2026-09", "2026-08").statusCode())
        .isEqualTo(400);
    assertThat(
            confirm(user.token(), monthly.id(), goalId, true, 1, "2026-09", "2027-09").statusCode())
        .isEqualTo(400);

    long informational =
        jdbc.queryForObject(
            "select v.id from policy_index_snapshots s join policy_snapshot_versions m on m.snapshot_id=s.id join policy_versions v on v.id=m.policy_version_id left join policy_calculation_rules r on r.policy_version_id=v.id where s.status='ACTIVE' and r.policy_version_id is null limit 1",
            Long.class);
    assertThat(confirm(user.token(), informational, goalId, true, 1, "2026-09", null).statusCode())
        .isEqualTo(400);
  }

  private Policy policy(String type) {
    return jdbc.queryForObject(
        "select v.id,r.amount_upper_bound::bigint,coalesce((select btrim(region_code) from policy_version_regions where policy_version_id=v.id limit 1),'11110') from policy_index_snapshots s join policy_snapshot_versions m on m.snapshot_id=s.id join policy_versions v on v.id=m.policy_version_id join policy_calculation_rules r on r.policy_version_id=v.id where s.status='ACTIVE' and r.adjustment_type=? order by case when v.policy_id=(select id from policies where policy_key='YOUTH_CENTER_20260406005400212460') then 0 else 1 end,v.id limit 1",
        (rs, n) -> new Policy(rs.getLong(1), rs.getLong(2), rs.getString(3)),
        type);
  }

  private User user(String prefix, Policy policy) throws Exception {
    String username = "stage2-benefit-" + prefix + "-" + UUID.randomUUID();
    HttpResponse<String> login =
        http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/e2e?username=" + username))
                .header("X-E2E-Token", "benefit-e2e-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(200);
    String token = mapper.readTree(login.body()).path("accessToken").asText();
    int id =
        jdbc.queryForObject(
            "select user_id from social_accounts where provider_subject=?",
            Integer.class,
            "e2e:" + username);
    jdbc.update(
        "insert into user_profiles(user_id,birth_date,region_code) values (?,date '2000-01-01',?) on conflict(user_id) do update set birth_date=excluded.birth_date,region_code=excluded.region_code",
        id,
        policy.region());
    return new User(id, token);
  }

  private int goal(int userId) {
    return jdbc.queryForObject(
        "insert into financial_goals(user_id,name,target_amount,current_saved_amount,target_date,status,created_at,updated_at) values (?,'stage2 benefit',10000000,0,date '2027-12-31','ACTIVE',now(),now()) returning id",
        Integer.class,
        userId);
  }

  private HttpResponse<String> confirm(
      String token,
      long policyId,
      int goalId,
      boolean confirmed,
      long amount,
      String start,
      String end)
      throws Exception {
    String body =
        mapper.writeValueAsString(
            java.util.Map.of(
                "goalId",
                goalId,
                "institutionConfirmed",
                confirmed,
                "amountWon",
                amount,
                "startYearMonth",
                start,
                "endYearMonth",
                end == null ? "" : end));
    if (end == null)
      body = body.replace(",\"endYearMonth\":\"\"", "").replace("\"endYearMonth\":\"\",", "");
    return send("POST", "/api/v1/policy-versions/" + policyId + "/benefits", token, body);
  }

  private HttpResponse<String> confirmWithoutStart(String token, long policyId, int goalId)
      throws Exception {
    String body =
        mapper.writeValueAsString(
            java.util.Map.of("goalId", goalId, "institutionConfirmed", true, "amountWon", 1));
    return send("POST", "/api/v1/policy-versions/" + policyId + "/benefits", token, body);
  }

  private JsonNode list(String token, int goalId) throws Exception {
    return mapper.readTree(
        send("GET", "/api/v1/goals/" + goalId + "/policy-benefits", token, null).body());
  }

  private JsonNode delete(String token, long id) throws Exception {
    return mapper.readTree(send("DELETE", "/api/v1/policy-benefits/" + id, token, null).body());
  }

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token);
    if (body != null)
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    else request.method(method, HttpRequest.BodyPublishers.noBody());
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private record Policy(long id, long cap, String region) {}

  private record User(int id, String token) {}
}
