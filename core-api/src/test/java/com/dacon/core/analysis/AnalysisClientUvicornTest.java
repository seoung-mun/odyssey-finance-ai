package com.dacon.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "REAL_ANALYSIS_URL", matches = ".+")
class AnalysisClientUvicornTest {
  @Test
  void simulateDeliversJsonBodyToRealUvicornOverHttp11() {
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
            System.getenv("REAL_ANALYSIS_URL"),
            System.getenv("REAL_ANALYSIS_TOKEN"),
            Duration.ofSeconds(3));

    assertThat(
            client
                .simulate(
                    """
                    {"randomSeed":3,"nPaths":10000,"horizonMonths":2,"periodRatios":[1.0,1.0],"availableVariableBudget":100,"historicalMonthlyVariableSpending":[100,100,100],"currentAvgVariableSpending":100,"spendingFloor":{"mode":"OFF"}}
                    """,
                    "real-uvicorn-http11")
                .path("options")
                .size())
        .isEqualTo(3);
  }
}
