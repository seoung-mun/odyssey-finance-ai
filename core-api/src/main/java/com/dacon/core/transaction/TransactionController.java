package com.dacon.core.transaction;

import com.dacon.core.transaction.dto.TransactionDtos.CategorySummary;
import com.dacon.core.transaction.dto.TransactionDtos.ImportRequest;
import com.dacon.core.transaction.dto.TransactionDtos.ImportResult;
import com.dacon.core.transaction.dto.TransactionDtos.MonthlySpending;
import com.dacon.core.transaction.dto.TransactionDtos.TransactionPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JWT subject로 소유권을 제한해 거래 적재·조회·집계를 제공하는 HTTP 경계다. */
@Validated
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
  private final TransactionService service;

  /**
   * 거래 HTTP 요청을 유스케이스에 위임하도록 controller를 구성한다.
   *
   * @param service 사용자 소유 거래 유스케이스 서비스
   */
  public TransactionController(TransactionService service) {
    this.service = service;
  }

  /**
   * 최대 천 건의 거래를 외부 거래 ID 기준으로 멱등 적재한다.
   *
   * @param jwt 검증된 access JWT
   * @param request Bean Validation을 통과한 거래 목록
   * @return 삽입 및 중복 건수
   */
  @PostMapping("/import")
  public ImportResult importTransactions(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ImportRequest request) {
    return service.importAll(Integer.parseInt(jwt.getSubject()), request.transactions());
  }

  /**
   * 인증 사용자의 거래를 선택적 시각·카테고리 조건과 ID 커서로 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @param from 포함할 시작 시각
   * @param to 제외할 종료 시각
   * @param category 정확히 일치시킬 카테고리
   * @param cursor 직전 응답의 다음 커서
   * @param limit 1 이상 200 이하 페이지 크기
   * @return ID 내림차순 거래 페이지
   */
  @GetMapping
  public TransactionPage transactions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") @Max(200) @Positive int limit) {
    return service.list(Integer.parseInt(jwt.getSubject()), from, to, category, cursor, limit);
  }

  /**
   * 최근 KST 달력 월별 순 소비와 부트스트랩 대상 소비를 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @param months 1 이상 60 이하 조회 개월 수
   * @return 월별 집계 목록
   */
  @GetMapping("/monthly-summary")
  public List<MonthlySpending> monthlySummary(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "24") @Max(60) @Positive int months) {
    return service.monthlySummary(Integer.parseInt(jwt.getSubject()), months);
  }

  /**
   * 최근 KST 달력 월 구간의 카테고리별 월평균 소비를 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @param months 1 이상 24 이하 평균 개월 수
   * @return 전체 및 카테고리별 월평균
   */
  @GetMapping("/category-summary")
  public CategorySummary categorySummary(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "3") @Positive @Max(24) int months) {
    return service.categorySummary(Integer.parseInt(jwt.getSubject()), months);
  }
}
