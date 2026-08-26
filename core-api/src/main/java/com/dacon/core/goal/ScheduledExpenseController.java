package com.dacon.core.goal;

import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseInput;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpensePatch;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JWT subject로 소유권을 제한해 예정지출과 실제 매칭 거래 집계를 조회하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/scheduled-expenses")
public class ScheduledExpenseController {
  private final GoalService service;

  /**
   * 예정지출 조회 요청을 목표 유스케이스에 위임하도록 controller를 구성한다.
   *
   * @param service 예정지출 조회를 제공하는 목표 서비스
   */
  public ScheduledExpenseController(GoalService service) {
    this.service = service;
  }

  /**
   * 인증 사용자의 예정지출을 선택적 상태 필터로 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @param status PLANNED·COMPLETED·CANCELLED 중 하나, 전체 조회면 {@code null}
   * @return 예정일 순 예정지출과 매칭 거래 집계
   */
  @GetMapping
  public List<ScheduledExpenseResponse> scheduledExpenses(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false)
          @Pattern(regexp = "PLANNED|COMPLETED|CANCELLED", message = "예정지출 상태를 확인해 주세요")
          String status) {
    return service.scheduledExpenses(Integer.parseInt(jwt.getSubject()), status);
  }

  @PostMapping
  public ResponseEntity<ScheduledExpenseResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ScheduledExpenseInput input) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createScheduledExpense(Integer.parseInt(jwt.getSubject()), input));
  }

  @PatchMapping("/{scheduledExpenseId}")
  public ScheduledExpenseResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @jakarta.validation.constraints.Positive int scheduledExpenseId,
      @Valid @RequestBody ScheduledExpensePatch input) {
    return service.updateScheduledExpense(
        Integer.parseInt(jwt.getSubject()), scheduledExpenseId, input);
  }
}
