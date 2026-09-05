package com.dacon.core.policy;

import com.dacon.core.error.ApiException;
import com.dacon.core.policy.PolicyDtos.PolicyAnswer;
import com.dacon.core.policy.PolicyDtos.PolicyOfficialSource;
import com.dacon.core.policy.PolicyDtos.PolicyQuestion;
import com.dacon.core.policy.PolicyDtos.PolicyQuestionResponse;
import com.dacon.core.policy.PolicyDtos.PolicyResult;
import com.dacon.core.policy.PolicyDtos.PolicyResultsResponse;
import com.dacon.core.policy.PolicyDtos.PolicySearchRequest;
import com.dacon.core.policy.PolicyDtos.PolicySearchResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고정 질문을 검증하고 ACTIVE snapshot의 exact cosine Top 3를 반환한다. */
@Service
public class PolicySearchService {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private final PolicyRepository repository;
  private final Clock clock;

  @Autowired
  public PolicySearchService(PolicyRepository repository) {
    this(repository, Clock.system(SEOUL));
  }

  PolicySearchService(PolicyRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public PolicySearchResponse search(int userId, PolicySearchRequest request) {
    long started = System.nanoTime();
    PolicyRepository.SearchCatalog catalog =
        repository.activeCatalog(userId, request.supportGoal());
    if (catalog == null || catalog.questions().size() > 3) {
      throw unavailable();
    }
    validateAnswers(request.answers(), catalog.questions());
    if (request.answers().size() < catalog.questions().size()) {
      return new PolicyQuestionResponse(catalog.questions().get(request.answers().size()));
    }

    List<PolicyResult> results;
    try {
      Map<Long, Scored> best = new LinkedHashMap<>();
      LocalDate evaluationDate = LocalDate.now(clock);
      for (PolicyRepository.Candidate candidate : catalog.candidates()) {
        if (!isEligible(candidate, catalog.profile(), evaluationDate)) {
          continue;
        }
        double score = cosine(catalog.queryEmbedding(), candidate.embedding());
        Scored current = best.get(candidate.policyId());
        if (current == null || score > current.score()) {
          best.put(candidate.policyId(), new Scored(candidate, score));
        }
      }
      results =
          best.values().stream()
              .sorted(
                  Comparator.comparingDouble(Scored::score)
                      .reversed()
                      .thenComparingLong(value -> value.candidate().versionId()))
              .limit(3)
              .map(value -> result(value.candidate()))
              .toList();
    } catch (IllegalArgumentException exception) {
      throw unavailable();
    }
    long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
    repository.record(
        catalog.snapshotId(),
        request.supportGoal(),
        results.stream().map(PolicyResult::policyVersionId).toList(),
        latency);
    return new PolicyResultsResponse(results);
  }

  static boolean isEligible(
      PolicyRepository.Candidate candidate,
      PolicyRepository.UserEligibilityProfile profile,
      LocalDate evaluationDate) {
    if (profile == null) {
      return false;
    }
    return PolicyEligibilityEvaluator.isEligible(
        candidate.applicationDecision(),
        candidate.regionScope(),
        candidate.regionCodes(),
        candidate.ageMin(),
        candidate.ageMax(),
        profile.regionCode(),
        profile.birthDate(),
        evaluationDate);
  }

  static double cosine(double[] left, double[] right) {
    if (left.length != 1024 || right.length != 1024) {
      throw new IllegalArgumentException("policy embedding must be 1024 dimensional");
    }
    double leftScale = 0;
    double rightScale = 0;
    for (int index = 0; index < 1024; index++) {
      if (!Double.isFinite(left[index]) || !Double.isFinite(right[index])) {
        throw new IllegalArgumentException("policy embedding must be finite");
      }
      leftScale = Math.max(leftScale, Math.abs(left[index]));
      rightScale = Math.max(rightScale, Math.abs(right[index]));
    }
    if (leftScale == 0 || rightScale == 0) {
      throw new IllegalArgumentException("policy embedding has no finite cosine");
    }
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (int index = 0; index < 1024; index++) {
      double scaledLeft = left[index] / leftScale;
      double scaledRight = right[index] / rightScale;
      dot += scaledLeft * scaledRight;
      leftNorm += scaledLeft * scaledLeft;
      rightNorm += scaledRight * scaledRight;
    }
    return dot / Math.sqrt(leftNorm * rightNorm);
  }

  private void validateAnswers(List<PolicyAnswer> answers, List<PolicyQuestion> questions) {
    if (answers.size() > questions.size()) {
      throw invalidAnswers();
    }
    for (int index = 0; index < answers.size(); index++) {
      PolicyAnswer answer = answers.get(index);
      PolicyQuestion question = questions.get(index);
      boolean validValue =
          question.options().stream().anyMatch(option -> option.value().equals(answer.value()));
      if (!question.questionId().equals(answer.questionId()) || !validValue) {
        throw invalidAnswers();
      }
    }
  }

  private PolicyResult result(PolicyRepository.Candidate candidate) {
    Map<String, Object> metadata = candidate.metadata();
    String mode = candidate.approvedRule() ? candidate.calculationMode() : "INFORMATIONAL";
    return new PolicyResult(
        candidate.versionId(),
        candidate.title(),
        candidate.summary(),
        candidate.planConnection(),
        text(metadata, "supportDetails", candidate.summary()),
        texts(metadata, "confirmedConditions"),
        texts(metadata, "additionalChecks"),
        text(metadata, "applicationPeriod", "공식 페이지 확인"),
        candidate.lastVerifiedAt().atZone(SEOUL).toLocalDate(),
        "NEEDS_CONFIRMATION",
        mode,
        new PolicyOfficialSource(
            candidate.organization(),
            candidate.officialUrl(),
            candidate.sourceVersion(),
            candidate.lastVerifiedAt(),
            List.of(candidate.locator())));
  }

  private String text(Map<String, Object> metadata, String key, String fallback) {
    Object value = metadata.get(key);
    return value instanceof String text && !text.isBlank() ? text : fallback;
  }

  private List<String> texts(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object item : values) {
      if (item instanceof String text && !text.isBlank()) {
        result.add(text);
      }
    }
    return List.copyOf(result);
  }

  private ApiException invalidAnswers() {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POLICY_ANSWERS", "정책 질문 답변을 확인해 주세요.");
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "POLICY_CATALOG_UNAVAILABLE", "정책 정보를 불러오지 못했습니다.");
  }

  private record Scored(PolicyRepository.Candidate candidate, double score) {}
}
