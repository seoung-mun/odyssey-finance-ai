package com.dacon.core.analysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.error.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AnalysisClientTest {
  @Test
  void simulateInputErrorPreservesStableClientCode() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/simulate",
        exchange -> {
          byte[] body =
              "{\"code\":\"INVALID_INPUT\",\"message\":\"bad\",\"detail\":null}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(422, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      assertThatThrownBy(() -> client.simulate("{}", "request-1"))
          .isInstanceOf(ApiException.class)
          .extracting("code")
          .isEqualTo("INVALID_INPUT");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void customOptionPreservesInvalidBaselineAsUnprocessableEntity() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/custom-option",
        exchange -> {
          byte[] body =
              "{\"code\":\"INVALID_INPUT\",\"message\":\"below floor\"}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(422, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      assertThatThrownBy(() -> client.customOption("{}", "request-1"))
          .isInstanceOf(ApiException.class)
          .extracting("status")
          .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void malformedResponseBecomesServiceUnavailable() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/simulate",
        exchange -> {
          byte[] body = "null".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      assertThatThrownBy(() -> client.simulate("{}", "request-1"))
          .isInstanceOf(ApiException.class)
          .extracting("code")
          .isEqualTo("CALCULATION_SERVICE_UNAVAILABLE");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void validResponseRequiresShapesAndForwardsRequestId() throws Exception {
    AtomicReference<String> receivedRequestId = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/simulate",
        exchange -> {
          receivedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
          byte[] body =
              "{\"simulation\":{},\"options\":[],\"percentileBands\":[]}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      org.assertj.core.api.Assertions.assertThat(client.simulate("{}", "request-2").isObject())
          .isTrue();
      org.assertj.core.api.Assertions.assertThat(receivedRequestId.get()).isEqualTo("request-2");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void policyScenarioForwardsInternalHeadersAndRequiresComparisonShapes() throws Exception {
    AtomicReference<String> receivedRequestId = new AtomicReference<>();
    AtomicReference<String> receivedToken = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/policy-scenarios",
        exchange -> {
          receivedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
          receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
          byte[] body =
              "{\"currentPlanSummary\":{\"optionType\":\"CUSTOM\",\"recommendedMonthlySpending\":50,\"requiredReductionRate\":0.5,\"simulationCoverage\":0.7,\"historicalFeasibilityRatio\":0.3,\"aggressiveWarning\":false,\"targetCoverageMet\":true},\"assumedPlanSummary\":{\"optionType\":\"CUSTOM\",\"recommendedMonthlySpending\":40,\"requiredReductionRate\":0.6,\"simulationCoverage\":0.8,\"historicalFeasibilityRatio\":0.4,\"aggressiveWarning\":false,\"targetCoverageMet\":true},\"currentBands\":[],\"assumedBands\":[]}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      Assertions.assertThat(client.policyScenario("{}", "policy-scenario-1").isObject()).isTrue();
      Assertions.assertThat(receivedRequestId.get()).isEqualTo("policy-scenario-1");
      Assertions.assertThat(receivedToken.get()).isEqualTo("secret");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void policyScenarioPreservesInternalValidationAsUnprocessableEntity() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/policy-scenarios",
        exchange -> {
          byte[] body =
              "{\"code\":\"INVALID_INPUT\",\"message\":\"invalid adjustment\"}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(422, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      assertThatThrownBy(() -> client.policyScenario("{}", "policy-scenario-2"))
          .isInstanceOf(ApiException.class)
          .extracting("status")
          .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void policyScenarioRejectsWrongComparisonShapeAsServiceUnavailable() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/policy-scenarios",
        exchange -> {
          byte[] body =
              "{\"currentPlanSummary\":[],\"assumedPlanSummary\":{},\"currentBands\":{},\"assumedBands\":[]}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      assertThatThrownBy(() -> client.policyScenario("{}", "policy-scenario-3"))
          .isInstanceOf(ApiException.class)
          .extracting("code")
          .isEqualTo("CALCULATION_SERVICE_UNAVAILABLE");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void policyScenarioRejectsSummaryMissingRequiredFieldsAsServiceUnavailable() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/policy-scenarios",
        exchange -> {
          byte[] body =
              "{\"currentPlanSummary\":{},\"assumedPlanSummary\":{},\"currentBands\":[],\"assumedBands\":[]}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      assertThatThrownBy(() -> client.policyScenario("{}", "policy-scenario-summary"))
          .isInstanceOf(ApiException.class)
          .extracting("code")
          .isEqualTo("CALCULATION_SERVICE_UNAVAILABLE");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void policyScenarioNormalizesInternalServerErrorAsServiceUnavailable() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/policy-scenarios",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      assertThatThrownBy(() -> client.policyScenario("{}", "policy-scenario-5xx"))
          .isInstanceOf(ApiException.class)
          .extracting("status")
          .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void policyScenarioNormalizesTimeoutAsServiceUnavailable() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/policy-scenarios",
        exchange -> {
          try {
            Thread.sleep(200);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    AnalysisClient client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofMillis(50));

    try {
      assertThatThrownBy(() -> client.policyScenario("{}", "policy-scenario-timeout"))
          .isInstanceOf(ApiException.class)
          .extracting("status")
          .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    } finally {
      server.stop(0);
    }
  }
}
