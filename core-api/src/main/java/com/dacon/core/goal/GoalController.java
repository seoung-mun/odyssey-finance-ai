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

/** JWT subject를 소유권 범위로 사용해 금융 목표를 조회·생성하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {
  private final GoalService service;

  /**
   * 목표 HTTP 요청을 유스케이스에 위임하도록 controller를 구성한다.
   *
   * @param service 사용자 소유 목표 유스케이스 서비스
   */
  public GoalController(GoalService service) {
    this.service = service;
  }

  /**
   * 인증 사용자가 소유한 목표 한 건을 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @param goalId 양의 목표 ID
   * @return 소유권이 확인된 목표
   */
  @GetMapping("/{goalId}")
  public GoalResponse goal(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @jakarta.validation.constraints.Positive int goalId) {
    return service.goal(userId(jwt), goalId);
  }

  /**
   * 인증 사용자의 목표를 선택적 상태 필터로 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @param status ACTIVE·ACHIEVED·CANCELLED 중 하나, 전체 조회면 {@code null}
   * @return 최신 생성 순 목표 목록
   */
  @GetMapping
  public List<GoalResponse> goals(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false)
          @Pattern(regexp = "ACTIVE|ACHIEVED|CANCELLED", message = "목표 상태를 확인해 주세요")
          String status) {
    return service.goals(userId(jwt), status);
  }

  /**
   * 인증 사용자에게 ACTIVE 목표를 생성한다.
   *
   * @param jwt 검증된 access JWT
   * @param input Bean Validation을 통과한 목표 입력
   * @return 생성된 목표를 담은 201 응답
   */
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
