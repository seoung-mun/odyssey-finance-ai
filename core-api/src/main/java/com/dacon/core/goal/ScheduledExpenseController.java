package com.dacon.core.goal;

import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseResponse;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증 사용자의 예정지출 조회 HTTP 경계를 제공한다. */
@RestController
@RequestMapping("/api/v1/scheduled-expenses")
public class ScheduledExpenseController {
  private final GoalService service;

  public ScheduledExpenseController(GoalService service) {
    this.service = service;
  }

  @GetMapping
  public List<ScheduledExpenseResponse> scheduledExpenses(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false)
          @Pattern(regexp = "PLANNED|COMPLETED|CANCELLED", message = "예정지출 상태를 확인해 주세요")
          String status) {
    return service.scheduledExpenses(Integer.parseInt(jwt.getSubject()), status);
  }
}
