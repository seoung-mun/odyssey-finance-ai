package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("e2e")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "app.e2e-token=task5-e2e-token",
      "app.jwt-secret=task5-jwt-secret-task5-jwt-secret-32",
      "app.analysis-base-url=${REAL_ANALYSIS_URL}",
      "app.analysis-token=${REAL_ANALYSIS_TOKEN}"
    })
@EnabledIfEnvironmentVariable(named = "REAL_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "REAL_ANALYSIS_URL", matches = ".+")
class PolicyScenarioHttpPostgresTest {
  @LocalServerPort private int port;
  @Autowired private PolicyArtifactImportService importer;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;

  private final HttpClient http = HttpClient.newHttpClient();
  private String accessToken;
  private int planId;
  private long policyVersionId;

  @BeforeEach
  void prepare() throws Exception {
    importer.importArtifact(PolicyTestArtifacts.fourPolicies(mapper));
    String username = "task5-" + UUID.randomUUID();
    HttpResponse<String> login =
        http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/e2e?username=" + username))
                .header("X-E2E-Token", "task5-e2e-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(200);
    accessToken = mapper.readTree(login.body()).path("accessToken").asText();
    int userId =
        jdbc.queryForObject(
            "select user_id from social_accounts where provider_subject=?",
            Integer.class,
            "e2e:" + username);
    int goalId =
        jdbc.queryForObject(
            "insert into financial_goals(user_id,name,target_amount,current_saved_amount,target_date,status) values (?,?,?,?,?,'ACTIVE') returning id",
            Integer.class,
            userId,
            "scenario",
            1000L,
            0L,
            LocalDate.of(2026, 10, 1));
    planId =
        jdbc.queryForObject(
            "insert into plan_versions(goal_id,version_no,generation_type,status,as_of_date,monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,current_saved_snapshot,target_date_snapshot,available_variable_budget,current_avg_variable_spending,policy_snapshot,explanation_status,activated_at) values (?,1,'INITIAL','ACTIVE','2026-09-01',1000,0,1000,0,'2026-10-01',500,100,'{\"aggressiveWarningPct\":0.1}','FAILED',now()) returning id",
            Integer.class,
            goalId);
    jdbc.update(
        "insert into simulation_runs(plan_version_id,method,n_paths,random_seed,input_snapshot,input_hash,engine_version,result_summary) values (?,'IID_BOOTSTRAP',10000,3,cast(? as jsonb),?,'test','{\"ok\":true}')",
        planId,
        "{\"randomSeed\":3,\"nPaths\":10000,\"horizonMonths\":2,\"periodRatios\":[1.0,1.0],\"availableVariableBudget\":500,\"historicalMonthlyVariableSpending\":[100,100,100],\"currentAvgVariableSpending\":100,\"remainingScheduledExpenses\":[],\"policySnapshot\":{}}",
        "a".repeat(64));
    jdbc.update(
        "insert into plan_options(plan_version_id,option_type,nominal_level,recommended_monthly_spending,required_reduction_rate,simulation_coverage,historical_feasibility_ratio,selected_at) values (?,'PRESET',0.7,100,0.1,0.8,0.5,now())",
        planId);
    policyVersionId =
        jdbc.queryForObject(
            "select version.id from policy_versions version join policies policy on policy.id=version.policy_id where policy.policy_key='task3-policy-0'",
            Long.class);
  }

  @Test
  void realPostgresAndUvicornReturnComparisonWithoutPersistingOrLeakingIdentifiers()
      throws Exception {
    String before = fingerprint();
    HttpResponse<String> response = post(policyVersionId, request(planId));
    JsonNode body = mapper.readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(body.path("adjustment").path("startYearMonth").asText()).isEqualTo("2026-09");
    assertThat(fingerprint()).isEqualTo(before);
    assertThat(body.toString())
        .doesNotContain("userId", "policyVersionId", "supportGoal", "answers");
  }

  @Test
  void absentPolicyAndForeignPlanAreNotFoundBeforeOutboundCall() throws Exception {
    assertThat(post(999_999L, request(planId)).statusCode()).isEqualTo(404);
    int foreignUser =
        jdbc.queryForObject("insert into users default values returning id", Integer.class);
    int foreignGoal =
        jdbc.queryForObject(
            "insert into financial_goals(user_id,name,target_amount,current_saved_amount,target_date,status) values (?,?,?,?,?,'ACTIVE') returning id",
            Integer.class,
            foreignUser,
            "foreign",
            1000L,
            0L,
            LocalDate.of(2026, 10, 1));
    int foreignPlan =
        jdbc.queryForObject(
            "insert into plan_versions(goal_id,version_no,generation_type,status,as_of_date,monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,current_saved_snapshot,target_date_snapshot,available_variable_budget,current_avg_variable_spending,policy_snapshot,explanation_status,activated_at) values (?,1,'INITIAL','ACTIVE','2026-09-01',1000,0,1000,0,'2026-10-01',500,100,'{\"aggressiveWarningPct\":0.1}','FAILED',now()) returning id",
            Integer.class,
            foreignGoal);

    assertThat(post(policyVersionId, request(foreignPlan)).statusCode()).isEqualTo(404);
  }

  @Test
  void malformedAndUnconfirmedAwardsAreBadRequestsWithoutStateChanges() throws Exception {
    String before = fingerprint();
    String unconfirmed =
        request(planId).replace("\"institutionConfirmed\":true", "\"institutionConfirmed\":false");
    String unknownAward =
        request(planId).replace("\"amountWon\":1", "\"amountWon\":1,\"userId\":7");
    String unknownRequest =
        request(planId).replace("\"answers\":[]", "\"answers\":[],\"unexpected\":true");
    String unknownAnswer =
        request(planId)
            .replace(
                "\"answers\":[]",
                "\"answers\":[{\"questionId\":\"q\",\"value\":\"v\",\"raw\":true}]");

    assertThat(post(policyVersionId, unconfirmed).statusCode()).isEqualTo(400);
    assertThat(post(policyVersionId, unknownAward).statusCode()).isEqualTo(400);
    assertThat(post(policyVersionId, unknownRequest).statusCode()).isEqualTo(400);
    assertThat(post(policyVersionId, unknownAnswer).statusCode()).isEqualTo(400);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void missingAmountIsBadRequestButNullAndOutOfRangeAmountsAreUnprocessable() throws Exception {
    String before = fingerprint();
    String missing = request(planId).replace("\"amountWon\":1,", "");
    String explicitNull = request(planId).replace("\"amountWon\":1", "\"amountWon\":null");

    assertThat(post(policyVersionId, missing).statusCode()).isEqualTo(400);
    assertThat(post(policyVersionId, explicitNull).statusCode()).isEqualTo(422);
    assertThat(
            post(policyVersionId, request(planId).replace("\"amountWon\":1", "\"amountWon\":0"))
                .statusCode())
        .isEqualTo(422);
    assertThat(
            post(policyVersionId, request(planId).replace("\"amountWon\":1", "\"amountWon\":-1"))
                .statusCode())
        .isEqualTo(422);
    assertThat(
            post(
                    policyVersionId,
                    request(planId).replace("\"amountWon\":1", "\"amountWon\":1000000000000001"))
                .statusCode())
        .isEqualTo(422);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void policyAndPlanStateConflictsAreRejectedWithoutStateChanges() throws Exception {
    long informational =
        jdbc.queryForObject(
            "select version.id from policy_versions version join policies policy on policy.id=version.policy_id where policy.policy_key='task3-policy-1'",
            Long.class);
    String before = fingerprint();
    OffsetDateTime selectedAt =
        jdbc.queryForObject(
            "select selected_at from plan_options where plan_version_id=?",
            OffsetDateTime.class,
            planId);
    assertThat(post(informational, request(planId)).statusCode()).isEqualTo(409);

    jdbc.update("update plan_options set selected_at=null where plan_version_id=?", planId);
    assertThat(post(policyVersionId, request(planId)).statusCode()).isEqualTo(409);
    jdbc.update(
        "update plan_options set selected_at=? where plan_version_id=?", selectedAt, planId);

    jdbc.update("update plan_versions set status='PROPOSED' where id=?", planId);
    assertThat(post(policyVersionId, request(planId)).statusCode()).isEqualTo(409);
    jdbc.update("update plan_versions set status='ACTIVE' where id=?", planId);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void awardValueModeAndHorizonErrorsAreUnprocessableWithoutStateChanges() throws Exception {
    String before = fingerprint();
    assertThat(
            post(policyVersionId, request(planId).replace("\"amountWon\":1", "\"amountWon\":0"))
                .statusCode())
        .isEqualTo(422);
    assertThat(
            post(
                    policyVersionId,
                    request(planId).replace("\"amountWon\":1", "\"amountWon\":1000001"))
                .statusCode())
        .isEqualTo(422);
    assertThat(
            post(
                    policyVersionId,
                    request(planId)
                        .replace(
                            "\"startYearMonth\":\"2026-09\"", "\"startYearMonth\":\"2027-01\""))
                .statusCode())
        .isEqualTo(422);
    String wrongMode =
        request(planId)
            .replace("ONE_TIME_FUNDING", "MONTHLY_EXPENSE_REDUCTION")
            .replace(
                "\"startYearMonth\":\"2026-09\"",
                "\"startYearMonth\":\"2026-09\",\"endYearMonth\":\"2026-10\"");
    assertThat(post(policyVersionId, wrongMode).statusCode()).isEqualTo(422);
    jdbc.update(
        "update simulation_runs set input_snapshot=jsonb_set(input_snapshot,'{availableVariableBudget}',to_jsonb(?::bigint)) where plan_version_id=?",
        Long.MAX_VALUE,
        planId);
    assertThat(post(policyVersionId, request(planId)).statusCode()).isEqualTo(422);
    jdbc.update(
        "update simulation_runs set input_snapshot=jsonb_set(input_snapshot,'{availableVariableBudget}',to_jsonb(?::bigint)) where plan_version_id=?",
        500L,
        planId);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void concurrentRealRequestsReturnIdenticalResponsesWithoutStateChanges() throws Exception {
    String before = fingerprint();
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        futures.add(executor.submit(() -> post(policyVersionId, request(planId))));
      }
      List<String> bodies = new ArrayList<>();
      for (Future<HttpResponse<String>> future : futures) {
        HttpResponse<String> response = future.get();
        assertThat(response.statusCode()).isEqualTo(200);
        bodies.add(response.body());
      }
      assertThat(bodies).allMatch(bodies.getFirst()::equals);
    }
    assertThat(fingerprint()).isEqualTo(before);
  }

  private HttpResponse<String> post(long versionId, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri("/api/v1/policy-versions/" + versionId + "/scenario"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String request(int requestedPlanId) {
    return "{\"currentPlanVersionId\":"
        + requestedPlanId
        + ",\"supportGoal\":\"PURCHASE\",\"answers\":[],\"confirmedAward\":{\"type\":\"ONE_TIME_FUNDING\",\"institutionConfirmed\":true,\"amountWon\":1,\"startYearMonth\":\"2026-09\"}}";
  }

  private String fingerprint() {
    String database =
        jdbc.queryForObject(
            "select jsonb_build_object('plan_versions',(select jsonb_agg(row_to_json(value) order by id) from plan_versions value),'plan_options',(select jsonb_agg(row_to_json(value) order by id) from plan_options value),'simulation_runs',(select jsonb_agg(row_to_json(value) order by id) from simulation_runs value),'transactions',(select jsonb_agg(row_to_json(value) order by id) from transactions value),'scheduled_expenses',(select jsonb_agg(row_to_json(value) order by id) from scheduled_expenses value),'replan_events',(select jsonb_agg(row_to_json(value) order by id) from replan_events value),'policy_retrieval_runs',(select jsonb_agg(row_to_json(value) order by id) from policy_retrieval_runs value))::text",
            String.class);
    Long redisKeys = redis.execute((RedisCallback<Long>) connection -> connection.dbSize());
    return database + "|redis=" + redisKeys;
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
