package com.dacon.core.analysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.error.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AnalysisClientTest {
  @Test
  void malformedResponseBecomesServiceUnavailable() throws Exception {
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/simulate",
        exchange -> {
          var body = "null".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var client =
        new AnalysisClient(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1));

    try {
      assertThatThrownBy(() -> client.simulate("{}"))
          .isInstanceOf(ApiException.class)
          .extracting("code")
          .isEqualTo("CALCULATION_SERVICE_UNAVAILABLE");
    } finally {
      server.stop(0);
    }
  }
}
