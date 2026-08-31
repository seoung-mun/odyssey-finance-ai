package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.flyway.enabled=false")
@EnabledIfEnvironmentVariable(named = "REAL_POSTGRES_URL", matches = ".+")
class PlanSelectionCommandPostgresTest {
  @Autowired private PlanningCommandService commands;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.execute(
        "TRUNCATE replan_events, plan_versions, financial_goals, plan_options RESTART IDENTITY CASCADE");
    jdbc.update("INSERT INTO users(id) VALUES (7721),(7722) ON CONFLICT DO NOTHING");
    jdbc.update(
        "INSERT INTO financial_goals(id,user_id,name,target_amount,current_saved_amount,target_date,status) VALUES (7721,7721,'goal',1000000,0,'2027-01-01','ACTIVE')");
    jdbc.update(
        "INSERT INTO plan_versions(id,goal_id,version_no,generation_type,status,as_of_date,monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,current_saved_snapshot,target_date_snapshot,available_variable_budget,current_avg_variable_spending,policy_snapshot) VALUES (7721,7721,1,'INITIAL','PROPOSED','2026-08-31',3000000,1000000,1000000,0,'2027-01-01',1000000,100000,'{\"p95SampleSize\":100}')");
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

  private void insertEvent(String decision) {
    if (decision == null) {
      jdbc.update(
          "INSERT INTO replan_events(user_id,goal_id,source_plan_version_id,proposed_plan_version_id,trigger_type,trigger_details) VALUES (7721,7721,7721,7721,'USER_REQUESTED','{}')");
      return;
    }
    jdbc.update(
        "INSERT INTO replan_events(user_id,goal_id,source_plan_version_id,proposed_plan_version_id,trigger_type,trigger_details,user_decision,decided_at) VALUES (7721,7721,7721,7721,'USER_REQUESTED','{}',?,now())",
        decision);
  }

  private String status() {
    return jdbc.queryForObject("SELECT status FROM plan_versions WHERE id=7721", String.class);
  }
}
