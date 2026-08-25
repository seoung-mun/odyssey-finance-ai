package com.dacon.core.transaction;

import com.dacon.core.transaction.dto.TransactionDtos.*;
import com.dacon.core.transaction.dto.TransactionDtos.ImportRequest;
import com.dacon.core.transaction.dto.TransactionDtos.ImportResult;
import jakarta.validation.Valid;
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

/** 인증 사용자의 거래 import HTTP 경계를 제공한다. */
@Validated
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
  private final TransactionService service;

  /** 거래 서비스를 받는다. */
  public TransactionController(TransactionService service) {
    this.service = service;
  }

  /** 최대 천 건의 거래를 멱등 적재한다. */
  @PostMapping("/import")
  public ImportResult importTransactions(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ImportRequest request) {
    return service.importAll(Integer.parseInt(jwt.getSubject()), request.transactions());
  }

  @GetMapping
  public TransactionPage transactions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50")
          @jakarta.validation.constraints.Max(200)
          @jakarta.validation.constraints.Positive
          int limit) {
    return service.list(Integer.parseInt(jwt.getSubject()), from, to, category, cursor, limit);
  }

  @GetMapping("/monthly-summary")
  public List<MonthlySpending> monthlySummary(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "24")
          @jakarta.validation.constraints.Max(60)
          @jakarta.validation.constraints.Positive
          int months) {
    return service.monthlySummary(Integer.parseInt(jwt.getSubject()), months);
  }

  @GetMapping("/category-summary")
  public CategorySummary categorySummary(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "3")
          @jakarta.validation.constraints.Max(24)
          @jakarta.validation.constraints.Positive
          int months) {
    return service.categorySummary(Integer.parseInt(jwt.getSubject()), months);
  }
}
