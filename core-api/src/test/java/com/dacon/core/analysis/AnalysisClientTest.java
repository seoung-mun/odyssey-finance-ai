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
  void explanationForwardsRequestIdAndRequiresTerminalShape() throws Exception {
    AtomicReference<String> receivedRequestId = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/explanations",
        exchange -> {
          receivedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
          byte[] body =
              "{\"status\":\"READY\",\"text\":\"설명\",\"model\":\"qwen\",\"retryCount\":1,\"failedNumbers\":[],\"generatedAt\":\"2026-08-25T00:00:00Z\"}"
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
      Assertions.assertThat(
              client.generateExplanation("{}", "explanation-7").path("status").asText())
          .isEqualTo("READY");
      Assertions.assertThat(receivedRequestId.get()).isEqualTo("explanation-7");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void explanationRejectsFractionalRetryCount() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/explanations",
        exchange -> {
          byte[] body =
              "{\"status\":\"READY\",\"text\":\"설명\",\"model\":\"qwen\",\"retryCount\":2.9,\"failedNumbers\":[],\"generatedAt\":\"2026-08-25T00:00:00Z\"}"
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
      assertThatThrownBy(() -> client.generateExplanation("{}", "request-1"))
          .isInstanceOf(ApiException.class);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void explanationAcceptsOmittedOptionalFields() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/explanations",
        exchange -> {
          byte[] body =
              "{\"status\":\"READY\",\"text\":\"설명\"}"
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
      Assertions.assertThat(client.generateExplanation("{}", "request-1").path("status").asText())
          .isEqualTo("READY");
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
              "{\"simulation\":{},\"resolvedSpendingFloor\":{},\"options\":[],\"percentileBands\":[]}"
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
}
