package com.dacon.core.demo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 최신 데모 시나리오 조회와 PostgreSQL seed 함수 호출을 제공한다. */
public interface DemoRepository extends JpaRepository<DemoScenario, DemoScenario.DemoScenarioId> {
  @Query(
      "select scenario from DemoScenario scenario where scenario.scenarioVersion ="
          + " (select max(latest.scenarioVersion) from DemoScenario latest where"
          + " latest.testerId = scenario.testerId) order by scenario.testerId")
  List<DemoScenario> findLatestTesters();

  @Query(
      value =
          "SELECT out_tester_id AS \"testerId\", out_scenario_version AS \"scenarioVersion\","
              + " out_seeded_at AS \"seededAt\" FROM seed_demo_user(:userId,:testerId)",
      nativeQuery = true)
  DemoSeedRow seed(@Param("userId") int userId, @Param("testerId") String testerId);

  @Query(
      value =
          "SELECT out_tester_id AS \"testerId\", out_scenario_version AS \"scenarioVersion\","
              + " out_inserted AS \"inserted\", out_complete_months AS \"completeMonths\""
              + " FROM seed_demo_transactions(:userId,:testerId)",
      nativeQuery = true)
  DemoTransactionsRow seedTransactions(
      @Param("userId") int userId, @Param("testerId") String testerId);

  interface DemoSeedRow {
    String getTesterId();

    int getScenarioVersion();

    Instant getSeededAt();
  }

  interface DemoTransactionsRow {
    String getTesterId();

    int getScenarioVersion();

    int getInserted();

    int getCompleteMonths();
  }
}
