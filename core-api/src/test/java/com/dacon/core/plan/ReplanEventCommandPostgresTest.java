package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class ReplanEventCommandPostgresTest extends com.dacon.core.PostgresIntegrationTestSupport {
  @Autowired private ReplanService replans;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private TransactionTemplate transactionTemplate;

  @BeforeEach
  void seed() {
    jdbc.execute(
        "TRUNCATE replan_events, plan_versions, financial_goals, transactions RESTART IDENTITY CASCADE");
    jdbc.update("INSERT INTO users(id) VALUES (7711) ON CONFLICT DO NOTHING");
    jdbc.update(
        "INSERT INTO financial_goals(id,user_id,name,target_amount,current_saved_amount,target_date,status) VALUES (7711,7711,'goal',1000000,0,'2027-01-01','ACTIVE')");
    jdbc.update(
        "INSERT INTO plan_versions(id,goal_id,version_no,generation_type,status,as_of_date,monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,current_saved_snapshot,target_date_snapshot,available_variable_budget,current_avg_variable_spending,policy_snapshot,activated_at) VALUES (7711,7711,1,'INITIAL','ACTIVE','2026-08-26',3000000,1000000,1000000,0,'2027-01-01',1000000,100000,'{\"p95SampleSize\":100}',now())");
    jdbc.update(
        "INSERT INTO transactions(id,user_id,transaction_at,amount,transaction_type,category,source_id,external_transaction_id) VALUES (7711,7711,'2026-08-26T12:01:00+09:00',9223372036854775807,'PAYMENT','식비','REAL','max')");
  }

  @Test
  void createsRetryableShockEventForMaxPayment() {
    transactionTemplate.executeWithoutResult(
        ignored ->
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void afterCommit() {
                    replans.trigger(
                        7711,
                        7711,
                        "LARGE_UNEXPECTED_TRANSACTION",
                        mapper
                            .createObjectNode()
                            .put("amount", Long.MAX_VALUE)
                            .put("p95", 100)
                            .put("threshold", 115)
                            .put("transactionId", 7711),
                        7711L,
                        "real-max-payment");
                  }
                }));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM replan_events", Integer.class))
        .isEqualTo(1);
  }
}
