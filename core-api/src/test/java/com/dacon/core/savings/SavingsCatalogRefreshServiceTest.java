package com.dacon.core.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 2026-09-06 실측: FINLIFE_API_KEY가 있어도 savingProductsSearch 실응답이 약 16.5초 걸려 기존 기본 timeout(5s)에서는 항상
 * {@link java.net.http.HttpTimeoutException}으로 끊겼고, {@code refreshSafely()}의 {@code catch
 * (Exception)}가 이를 삼켜 상품 0건으로 남았다. 이 테스트는 실제 네트워크 대신 로컬 stub 서버로 그 타이밍 경계와 재시도·페이징·키 처리 계약을 고정한다.
 */
class SavingsCatalogRefreshServiceTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void timeoutShorterThanServerResponseLeavesCatalogUntouched() throws IOException {
    server =
        startServer(exchange -> respondAfterDelay(exchange, Duration.ofMillis(300), page(1, 1)));
    SavingsCatalogPersistenceService persistence = mock(SavingsCatalogPersistenceService.class);
    SavingsCatalogRefreshService service = newService(persistence, Duration.ofMillis(50), 1);

    service.refreshSafely();

    verify(persistence, never()).replaceActiveSnapshot(any());
  }

  @Test
  void timeoutLongerThanServerResponsePersistsParsedCatalog() throws IOException {
    server =
        startServer(exchange -> respondAfterDelay(exchange, Duration.ofMillis(300), page(1, 1)));
    SavingsCatalogPersistenceService persistence = mock(SavingsCatalogPersistenceService.class);
    SavingsCatalogRefreshService service = newService(persistence, Duration.ofSeconds(2), 1);

    service.refreshSafely();

    ArgumentCaptor<SavingsCatalogSnapshot> captor =
        ArgumentCaptor.forClass(SavingsCatalogSnapshot.class);
    verify(persistence).replaceActiveSnapshot(captor.capture());
    assertThat(captor.getValue().products()).hasSize(1);
    assertThat(captor.getValue().options()).hasSize(1);
  }

  @Test
  void multiplePagesAreAggregatedUntilMaxPageNo() throws IOException {
    server =
        startServer(
            exchange -> {
              int requestedPage = pageParam(exchange);
              respond(exchange, page(requestedPage, 2));
            });
    SavingsCatalogPersistenceService persistence = mock(SavingsCatalogPersistenceService.class);
    SavingsCatalogRefreshService service = newService(persistence, Duration.ofSeconds(2), 1);

    service.refreshSafely();

    ArgumentCaptor<SavingsCatalogSnapshot> captor =
        ArgumentCaptor.forClass(SavingsCatalogSnapshot.class);
    verify(persistence).replaceActiveSnapshot(captor.capture());
    assertThat(captor.getValue().products())
        .extracting(product -> product.finPrdtCd())
        .containsExactlyInAnyOrder("P1", "P2");
  }

  @Test
  void nonZeroErrorCodeIsNotRetriedAndSkipsPersistence() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    server =
        startServer(
            exchange -> {
              calls.incrementAndGet();
              respond(exchange, errorPage("010", "미등록 인증키"));
            });
    SavingsCatalogPersistenceService persistence = mock(SavingsCatalogPersistenceService.class);
    SavingsCatalogRefreshService service = newService(persistence, Duration.ofSeconds(2), 3);

    service.refreshSafely();

    assertThat(calls.get()).isEqualTo(1);
    verify(persistence, never()).replaceActiveSnapshot(any());
  }

  @Test
  void transientTimeoutIsRetriedThenSucceeds() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    server =
        startServer(
            exchange -> {
              if (calls.getAndIncrement() == 0) {
                respondAfterDelay(exchange, Duration.ofMillis(300), page(1, 1));
              } else {
                respond(exchange, page(1, 1));
              }
            });
    SavingsCatalogPersistenceService persistence = mock(SavingsCatalogPersistenceService.class);
    SavingsCatalogRefreshService service = newService(persistence, Duration.ofMillis(100), 3);

    service.refreshSafely();

    assertThat(calls.get()).isEqualTo(2);
    verify(persistence, times(1)).replaceActiveSnapshot(any());
  }

  @Test
  void blankOrWhitespaceOnlyApiKeySkipsRefreshWithoutAnyNetworkCall() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    server =
        startServer(
            exchange -> {
              calls.incrementAndGet();
              respond(exchange, page(1, 1));
            });
    SavingsCatalogPersistenceService persistence = mock(SavingsCatalogPersistenceService.class);
    SavingsCatalogRefreshService service =
        new SavingsCatalogRefreshService(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            persistence,
            " \r\n\t ",
            baseUrl(),
            Duration.ofSeconds(2),
            3);

    service.refreshSafely();

    assertThat(calls.get()).isZero();
    verify(persistence, never()).replaceActiveSnapshot(any());
  }

  // ── stub 서버 ──────────────────────────────────────────────────

  private SavingsCatalogRefreshService newService(
      SavingsCatalogPersistenceService persistence, Duration timeout, int maxAttempts) {
    return new SavingsCatalogRefreshService(
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        persistence,
        "test-key",
        baseUrl(),
        timeout,
        maxAttempts);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/finlifeapi";
  }

  private HttpServer startServer(HttpHandler handler) throws IOException {
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/finlifeapi/savingProductsSearch.json", handler);
    stub.start();
    return stub;
  }

  private int pageParam(HttpExchange exchange) {
    String query = exchange.getRequestURI().getQuery();
    for (String part : query.split("&")) {
      if (part.startsWith("pageNo=")) {
        return Integer.parseInt(part.substring("pageNo=".length()));
      }
    }
    throw new IllegalStateException("pageNo가 요청에 없습니다: " + query);
  }

  private void respondAfterDelay(HttpExchange exchange, Duration delay, String body) {
    try {
      Thread.sleep(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    respond(exchange, body);
  }

  private void respond(HttpExchange exchange, String body) {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    } catch (IOException exception) {
      // 클라이언트가 timeout으로 먼저 연결을 끊은 경우; stub 정리는 @AfterEach가 담당한다.
    } finally {
      exchange.close();
    }
  }

  private String page(int pageNo, int maxPageNo) {
    String code = "P" + pageNo;
    return """
        {"result":{"err_cd":"000","max_page_no":%d,"total_count":"1","baseList":[
        {"fin_co_no":"0010927","fin_prdt_cd":"%s","dcls_month":"202609","kor_co_nm":"테스트은행",
        "fin_prdt_nm":"테스트적금%d","join_way":"인터넷","mtrt_int":"단리","spcl_cnd":"없음",
        "join_deny":"1","join_member":"제한없음","max_limit":null}],
        "optionList":[{"fin_co_no":"0010927","fin_prdt_cd":"%s","intr_rate_type":"S",
        "intr_rate_type_nm":"단리","rsrv_type":"S","rsrv_type_nm":"정액적립식","save_trm":"12",
        "intr_rate":3.0,"intr_rate2":3.5}]}}
        """
        .formatted(maxPageNo, code, pageNo, code);
  }

  private String errorPage(String errorCode, String message) {
    return """
        {"result":{"err_cd":"%s","err_msg":"%s","total_count":"0"}}
        """
        .formatted(errorCode, message);
  }
}
