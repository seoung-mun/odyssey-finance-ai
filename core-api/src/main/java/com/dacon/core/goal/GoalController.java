package com.dacon.core.goal;

import com.dacon.core.goal.dto.GoalDtos.GoalRequest;
import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 목표 목록·생성 HTTP API를 제공한다. */
@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {
  private final GoalService service;

  /** 목표 유스케이스 서비스를 주입한다. */
  public GoalController(GoalService service) {
    this.service = service;
  }

  @GetMapping("/{goalId}")
  public GoalResponse goal(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @jakarta.validation.constraints.Positive int goalId) {
    return service.goal(userId(jwt), goalId);
  }

  /** JWT 사용자의 목표 목록을 조회한다. */
  @GetMapping
  public List<GoalResponse> goals(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false)
          @Pattern(regexp = "ACTIVE|ACHIEVED|CANCELLED", message = "목표 상태를 확인해 주세요")
          String status) {
    return service.goals(userId(jwt), status);
  }

  /** JWT 사용자에게 ACTIVE 목표를 생성한다. */
  @PostMapping
  public ResponseEntity<GoalResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GoalRequest input) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId(jwt), input));
  }

  /** 검증된 JWT subject를 사용자 ID로 변환한다. */
  private int userId(Jwt jwt) {
    return Integer.parseInt(jwt.getSubject());
  }
}
