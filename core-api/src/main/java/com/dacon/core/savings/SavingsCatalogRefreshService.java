package com.dacon.core.savings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SavingsCatalogRefreshService {
  private static final Logger log = LoggerFactory.getLogger(SavingsCatalogRefreshService.class);

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final SavingsCatalogPersistenceService persistence;
  private final String apiKey;
  private final String baseUrl;
  private final Duration timeout;
  private final int maxAttempts;
  // ponytail: startup refresh는 FSS 실응답이 초 단위로 걸릴 수 있어 기동 스레드를
  // 막지 않도록 별도 virtual thread에서 fire-and-forget으로 실행한다.
  private final ExecutorService startupExecutor =
      Executors.newSingleThreadExecutor(
          runnable -> Thread.ofVirtual().name("finlife-startup-refresh").unstarted(runnable));

  public SavingsCatalogRefreshService(
      HttpClient http,
      ObjectMapper mapper,
      SavingsCatalogPersistenceService persistence,
      @Value("${app.finlife-api-key:}") String apiKey,
      @Value("${app.finlife-base-url:https://finlife.fss.or.kr/finlifeapi}") String baseUrl,
      @Value("${app.finlife-timeout:45s}") Duration timeout,
      @Value("${app.finlife-max-attempts:3}") int maxAttempts) {
    this.http = http;
    this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    this.persistence = persistence;
    this.apiKey = apiKey == null ? "" : apiKey.strip();
    this.baseUrl = baseUrl;
    this.timeout = timeout;
    this.maxAttempts = maxAttempts;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void refreshOnStartup() {
    startupExecutor.execute(this::refreshSafely);
  }

  @PreDestroy
  void shutdown() {
    startupExecutor.shutdownNow();
  }

  @Scheduled(cron = "${app.finlife-refresh-cron:0 15 4 * * *}", zone = "Asia/Seoul")
  public void refreshScheduled() {
    refreshSafely();
  }

  void refreshSafely() {
    if (apiKey.isBlank()) {
      log.info("Finlife API key가 없어 적금 카탈로그 갱신을 건너뜁니다.");
      return;
    }
    try {
      SavingsCatalogSnapshot snapshot = fetchAllPages();
      persistence.replaceActiveSnapshot(snapshot);
      log.info(
          "Finlife 적금 카탈로그 갱신 완료 products={} options={}",
          snapshot.products().size(),
          snapshot.options().size());
    } catch (Exception exception) {
      log.error("Finlife 적금 카탈로그 갱신 실패; 기존 캐시를 유지합니다.", exception);
    }
  }

  SavingsCatalogSnapshot fetchAllPages() throws Exception {
    List<SavingsCatalogSnapshot.Product> products = new ArrayList<>();
    List<SavingsCatalogSnapshot.Option> options = new ArrayList<>();
    int page = 1;
    int maximumPage = 1;
    do {
      JsonNode result = fetchPageWithRetry(page);
      String errorCode = result.path("err_cd").asText();
      if (!"000".equals(errorCode)) {
        throw new IllegalStateException("Finlife 오류 코드 " + errorCode);
      }
      result.path("baseList").forEach(value -> addProduct(products, value));
      result.path("optionList").forEach(value -> addOption(options, value));
      maximumPage = Math.max(page, result.path("max_page_no").asInt(page));
      page++;
    } while (page <= maximumPage);
    if (products.isEmpty()) {
      throw new IllegalStateException("Finlife 전체 응답에 상품이 없습니다.");
    }
    return new SavingsCatalogSnapshot(List.copyOf(products), List.copyOf(options));
  }

  // ponytail: err_cd 등 응답 자체의 거부는 재시도해도 결과가 같으므로 전송 계층
  // 예외(timeout·connect 실패)에만 재시도를 적용한다.
  private JsonNode fetchPageWithRetry(int page) throws Exception {
    long delayMillis = 1000L;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      long started = System.nanoTime();
      try {
        JsonNode result = fetchPage(page);
        log.info("Finlife page={} 응답 ms={}", page, (System.nanoTime() - started) / 1_000_000);
        return result;
      } catch (IOException exception) {
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        if (attempt == maxAttempts) {
          log.error(
              "Finlife page={} attempt={}/{} ms={} 실패, 재시도 중단",
              page,
              attempt,
              maxAttempts,
              elapsedMillis,
              exception);
          throw exception;
        }
        log.warn(
            "Finlife page={} attempt={}/{} ms={} 실패, {}ms 후 재시도",
            page,
            attempt,
            maxAttempts,
            elapsedMillis,
            delayMillis,
            exception);
        Thread.sleep(delayMillis);
        delayMillis *= 2;
      }
    }
    throw new IllegalStateException("도달할 수 없는 재시도 상태");
  }

  private JsonNode fetchPage(int page) throws Exception {
    String url =
        baseUrl
            + "/savingProductsSearch.json?auth="
            + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
            + "&topFinGrpNo=020000&pageNo="
            + page;
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Finlife HTTP " + response.statusCode());
    }
    return mapper.readTree(response.body()).path("result");
  }

  private void addProduct(List<SavingsCatalogSnapshot.Product> target, JsonNode value) {
    String company = text(value, "fin_co_no");
    String code = text(value, "fin_prdt_cd");
    String month = text(value, "dcls_month");
    String bank = text(value, "kor_co_nm");
    String name = text(value, "fin_prdt_nm");
    String joinDeny = text(value, "join_deny");
    if (company != null
        && code != null
        && month != null
        && bank != null
        && name != null
        && joinDeny != null) {
      target.add(
          new SavingsCatalogSnapshot.Product(
              company,
              code,
              month,
              bank,
              name,
              text(value, "join_way"),
              text(value, "mtrt_int"),
              text(value, "spcl_cnd"),
              joinDeny,
              text(value, "join_member"),
              longValue(value, "max_limit")));
    }
  }

  private void addOption(List<SavingsCatalogSnapshot.Option> target, JsonNode value) {
    String company = text(value, "fin_co_no");
    String product = text(value, "fin_prdt_cd");
    String rateType = text(value, "intr_rate_type");
    String reserveType = text(value, "rsrv_type");
    BigDecimal base = decimal(value, "intr_rate");
    BigDecimal maximum = decimal(value, "intr_rate2");
    Integer term = integer(value, "save_trm");
    if (company != null
        && product != null
        && rateType != null
        && reserveType != null
        && base != null
        && maximum != null
        && term != null
        && term >= 1
        && term <= 120) {
      target.add(
          new SavingsCatalogSnapshot.Option(
              company,
              product,
              rateType,
              text(value, "intr_rate_type_nm"),
              reserveType,
              text(value, "rsrv_type_nm"),
              term,
              base,
              maximum));
    }
  }

  private String text(JsonNode value, String field) {
    JsonNode node = value.path(field);
    return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
  }

  private BigDecimal decimal(JsonNode value, String field) {
    JsonNode node = value.path(field);
    return node.isNumber() ? node.decimalValue() : null;
  }

  private Long longValue(JsonNode value, String field) {
    JsonNode node = value.path(field);
    if (node.isIntegralNumber() && node.canConvertToLong()) {
      return node.longValue();
    }
    if (node.isTextual()) {
      try {
        return Long.valueOf(node.asText());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private Integer integer(JsonNode value, String field) {
    Long result = longValue(value, field);
    return result != null && result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE
        ? result.intValue()
        : null;
  }
}
