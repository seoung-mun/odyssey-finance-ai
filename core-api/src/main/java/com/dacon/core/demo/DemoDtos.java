package com.dacon.core.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** 데모 테스터 조회와 seed 함수의 공개 계약이다. */
public final class DemoDtos {
  private DemoDtos() {}

  public record DemoTester(
      String testerId,
      String displayName,
      String description,
      String ageGroup,
      long monthlyIncome,
      long monthlyFixedCost,
      String goalName,
      long goalTargetAmount,
      int goalMonths) {}

  public record DemoSeedRequest(@NotBlank @Size(max = 50) String testerId) {}

  public record DemoSeedResponse(String testerId, int scenarioVersion, Instant seededAt) {}

  public record DemoTransactionsResponse(
      String testerId, int scenarioVersion, int inserted, int completeMonths) {}
}
