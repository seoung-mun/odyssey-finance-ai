package com.dacon.core.plan;

import com.dacon.core.error.ApiException;
import com.dacon.core.error.RequestIdFilter;
import com.dacon.core.plan.dto.PlanningDtos.ReplanDecision;
import com.dacon.core.plan.dto.PlanningDtos.ReplanEventResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReplanController {
  private final ReplanService service;
  private final ObjectMapper mapper;

  public ReplanController(ReplanService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @GetMapping("/goals/{goalId}/replan-events")
  public List<ReplanEventResponse> events(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive int goalId) {
    return service.events(userId(jwt), goalId);
  }

  @PostMapping("/goals/{goalId}/replan")
  public JsonNode request(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive int goalId,
      HttpServletRequest request) {
    ReplanService.ReplanRequestResult result =
        service.request(userId(jwt), goalId, requestId(request));
    if (result.plan().infeasible()) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_INFEASIBLE", result.plan().infeasibleReason());
    }
    ObjectNode response = mapper.valueToTree(result.plan().detail());
    response.put("replanEventId", result.eventId());
    return response;
  }

  @PostMapping("/replan-events/{replanEventId}/decision")
  public ReplanEventResponse decision(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive int replanEventId,
      @Valid @RequestBody ReplanDecision input) {
    return service.decide(userId(jwt), replanEventId, input.decision());
  }

  @PostMapping("/replan-events/{replanEventId}/retry")
  public com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse retry(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive int replanEventId,
      HttpServletRequest request) {
    return service.retry(userId(jwt), replanEventId, requestId(request));
  }

  private int userId(Jwt jwt) {
    return Integer.parseInt(jwt.getSubject());
  }

  private String requestId(HttpServletRequest request) {
    return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
  }
}
