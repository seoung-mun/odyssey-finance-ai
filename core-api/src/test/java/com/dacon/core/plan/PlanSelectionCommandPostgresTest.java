package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.error.ApiException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.flyway.enabled=false",
      "spring.datasource.hikari.connection-init-sql=SET lock_timeout='3s'"
    })
class PlanSelectionCommandPostgresTest extends com.dacon.core.PostgresIntegrationTestSupport {
  @Autowired private PlanningCommandService commands;
  @Autowired private PlanningQueryService queries;
  @Autowired private ReplanEventCommand eventCommands;
  @Autowired private ReplanService replans;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.execute(
        "TRUNCATE replan_events, plan_versions, financial_goals, plan_options RESTART IDENTITY CASCADE");
    jdbc.update("INSERT INTO users(id) VALUES (7721),(7722) ON CONFLICT DO NOTHING");
    jdbc.update(
        "INSERT INTO financial_goals(id,user_id,name,target_amount,current_saved_amount,target_date,status) VALUES (7721,7721,'goal',1000000,0,'2027-01-01','ACTIVE')");
    jdbc.update(
        "INSERT INTO financial_profiles(user_id,monthly_income,monthly_fixed_cost,updated_at) VALUES (7721,3000000,1000000,now()) ON CONFLICT (user_id) DO UPDATE SET monthly_income=excluded.monthly_income,monthly_fixed_cost=excluded.monthly_fixed_cost,updated_at=excluded.updated_at");
    jdbc.update(
        "INSERT INTO plan_versions(id,goal_id,version_no,generation_type,status,as_of_date,monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,current_saved_snapshot,target_date_snapshot,available_variable_budget,current_avg_variable_spending,policy_snapshot,activated_at) VALUES (7720,7721,1,'INITIAL','ACTIVE','2026-08-31',3000000,1000000,1000000,0,'2027-01-01',1000000,100000,'{\"p95SampleSize\":100}',now())");
    jdbc.update(
        "INSERT INTO plan_versions(id,goal_id,version_no,generation_type,status,as_of_date,monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,current_saved_snapshot,target_date_snapshot,available_variable_budget,current_avg_variable_spending,policy_snapshot) VALUES (7721,7721,2,'TRIGGERED_REPLAN','PROPOSED','2026-08-31',3000000,1000000,1000000,0,'2027-01-01',1000000,100000,'{\"p95SampleSize\":100}')");
    jdbc.update(
        "INSERT INTO plan_options(id,plan_version_id,option_type,nominal_level,recommended_monthly_spending,required_reduction_rate,simulation_coverage,historical_feasibility_ratio) VALUES (7721,7721,'PRESET',0.7,500000,0.1,0.8,0.9)");
  }

  @Test
  void directInitialProposalRemainsSelectable() {
    assertThat(commands.select(7721, 7721, 7721)).isEqualTo(7721);
    assertThat(status()).isEqualTo("ACTIVE");
  }

  @Test
  void replanProposalRequiresDecision() {
    insertEvent(null);

    assertThatThrownBy(() -> commands.select(7721, 7721, 7721))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("REPLAN_ACCEPT_REQUIRED");
    assertThat(status()).isEqualTo("PROPOSED");
  }

  @Test
  void keptReplanProposalCannotBeSelected() {
    int eventId = insertEvent(null);

    replans.decide(7721, eventId, "KEEP_CURRENT_PLAN");

    assertThatThrownBy(() -> commands.select(7721, 7721, 7721))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("PLAN_NOT_SELECTABLE");
    assertThat(status()).isEqualTo("REJECTED");
  }

  @Test
  void inconsistentKeptEventStillRequiresAccept() {
    insertEvent("KEEP_CURRENT_PLAN");

    assertThatThrownBy(() -> commands.select(7721, 7721, 7721))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("REPLAN_ACCEPT_REQUIRED");
    assertThat(status()).isEqualTo("PROPOSED");
  }

  @Test
  void acceptedReplanProposalCanBeSelected() {
    insertEvent("ACCEPT_NEW_PLAN");

    assertThat(commands.select(7721, 7721, 7721)).isEqualTo(7721);
    assertThat(status()).isEqualTo("ACTIVE");
  }

  @Test
  void otherUserStillSeesNotFound() {
    insertEvent("ACCEPT_NEW_PLAN");

    assertThatThrownBy(() -> commands.select(7722, 7721, 7721))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(status()).isEqualTo("PROPOSED");
  }

  @Test
  void concurrentSelectAndAcceptFinishWithoutBypassOrDeadlock() throws Exception {
    int eventId = insertEvent(null);

    RaceResult race =
        race(
            () -> commands.select(7721, 7721, 7721),
            () -> replans.decide(7721, eventId, "ACCEPT_NEW_PLAN"));

    assertThat(race.right()).isEqualTo("OK");
    assertThat(race.left()).isIn("OK", "REPLAN_ACCEPT_REQUIRED");
    assertThat(decision(eventId)).isEqualTo("ACCEPT_NEW_PLAN");
    if ("REPLAN_ACCEPT_REQUIRED".equals(race.left())) {
      assertThat(commands.select(7721, 7721, 7721)).isEqualTo(7721);
    }
    assertThat(status()).isEqualTo("ACTIVE");
  }

  @Test
  void concurrentSelectAndKeepFinishRejectedWithoutSelectedOption() throws Exception {
    int eventId = insertEvent(null);

    RaceResult race =
        race(
            () -> commands.select(7721, 7721, 7721),
            () -> replans.decide(7721, eventId, "KEEP_CURRENT_PLAN"));

    assertThat(race.right()).isEqualTo("OK");
    assertThat(race.left()).isIn("REPLAN_ACCEPT_REQUIRED", "PLAN_NOT_SELECTABLE");
    assertThat(decision(eventId)).isEqualTo("KEEP_CURRENT_PLAN");
    assertThat(status()).isEqualTo("REJECTED");
    assertThat(selectedAt()).isNull();
  }

  @Test
  void concurrentSelectAndSaveProposalFinishWithOneConsistentWinner() throws Exception {
    int eventId = insertEventWithoutProposal();
    PlanInput input = queries.readPlanInput(7721, 7721);
    PlanPreview preview = new PlanPreview(input, null, "test", 1L);

    RaceResult race =
        race(
            () -> commands.select(7721, 7721, 7721),
            () -> eventCommands.saveProposal(7721, 7721, eventId, input, preview));

    assertThat(Set.of(race.left(), race.right()))
        .isIn(Set.of("OK", "PLAN_INPUT_CHANGED"), Set.of("OK", "PLAN_NOT_SELECTABLE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM plan_versions WHERE status='ACTIVE'", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM plan_versions WHERE status='PROPOSED'", Integer.class))
        .isZero();
    if ("OK".equals(race.left())) {
      assertThat(status()).isEqualTo("ACTIVE");
      assertThat(proposedPlanId(eventId)).isNull();
    } else {
      assertThat(status()).isEqualTo("STALE");
      assertThat(proposedPlanId(eventId)).isNotNull();
      assertThat(selectedAt()).isNull();
    }
  }

  private int insertEvent(String decision) {
    if (decision == null) {
      return jdbc.queryForObject(
          "INSERT INTO replan_events(user_id,goal_id,source_plan_version_id,proposed_plan_version_id,trigger_type,trigger_details) VALUES (7721,7721,7720,7721,'USER_REQUESTED','{}') RETURNING id",
          Integer.class);
    }
    return jdbc.queryForObject(
        "INSERT INTO replan_events(user_id,goal_id,source_plan_version_id,proposed_plan_version_id,trigger_type,trigger_details,user_decision,decided_at) VALUES (7721,7721,7720,7721,'USER_REQUESTED','{}',?,now()) RETURNING id",
        Integer.class,
        decision);
  }

  private int insertEventWithoutProposal() {
    return jdbc.queryForObject(
        "INSERT INTO replan_events(user_id,goal_id,source_plan_version_id,trigger_type,trigger_details) VALUES (7721,7721,7720,'USER_REQUESTED','{}') RETURNING id",
        Integer.class);
  }

  private RaceResult race(Callable<?> left, Callable<?> right) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> leftFuture = executor.submit(() -> runAfterBarrier(barrier, left));
      Future<String> rightFuture = executor.submit(() -> runAfterBarrier(barrier, right));
      return new RaceResult(
          leftFuture.get(5, TimeUnit.SECONDS), rightFuture.get(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  private String runAfterBarrier(CyclicBarrier barrier, Callable<?> action) throws Exception {
    barrier.await(5, TimeUnit.SECONDS);
    try {
      action.call();
      return "OK";
    } catch (ApiException exception) {
      return exception.code();
    }
  }

  private String status() {
    return jdbc.queryForObject("SELECT status FROM plan_versions WHERE id=7721", String.class);
  }

  private String decision(int eventId) {
    return jdbc.queryForObject(
        "SELECT user_decision FROM replan_events WHERE id=?", String.class, eventId);
  }

  private java.time.OffsetDateTime selectedAt() {
    return jdbc.queryForObject(
        "SELECT selected_at FROM plan_options WHERE id=7721", java.time.OffsetDateTime.class);
  }

  private Integer proposedPlanId(int eventId) {
    return jdbc.queryForObject(
        "SELECT proposed_plan_version_id FROM replan_events WHERE id=?", Integer.class, eventId);
  }

  private record RaceResult(String left, String right) {}
}
