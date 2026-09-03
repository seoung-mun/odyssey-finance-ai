package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.error.ApiException;
import com.dacon.core.policy.PolicyDtos.PolicyAnswer;
import com.dacon.core.policy.PolicyDtos.PolicyQuestion;
import com.dacon.core.policy.PolicyDtos.PolicyQuestionOption;
import com.dacon.core.policy.PolicyDtos.PolicySearchRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicySearchServiceTest {
  @Test
  void rejectsEveryMalformedAnswerInsteadOfTrustingItsPosition() throws Exception {
    PolicySearchService service =
        new PolicySearchService(
            new StubPolicyRepository(
                List.of(
                    new PolicyQuestion("housing", "주택 보유 여부", options("YES", "NO")),
                    new PolicyQuestion("married", "혼인 여부", options("YES", "NO")))));

    assertThatThrownBy(
            () ->
                service.search(
                    1,
                    new PolicySearchRequest(
                        "PURCHASE",
                        List.of(
                            new PolicyAnswer("housing", "YES"), new PolicyAnswer("wrong", "YES")))))
        .isInstanceOf(ApiException.class)
        .extracting(exception -> ((ApiException) exception).code())
        .isEqualTo("INVALID_POLICY_ANSWERS");
  }

  @Test
  void rejectsAQuestionProgramThatWouldAskAFourthQuestion() throws Exception {
    PolicySearchService service =
        new PolicySearchService(
            new StubPolicyRepository(
                List.of(
                    new PolicyQuestion("q1", "1", options("Y")),
                    new PolicyQuestion("q2", "2", options("Y")),
                    new PolicyQuestion("q3", "3", options("Y")),
                    new PolicyQuestion("q4", "4", options("Y")))));

    assertThatThrownBy(() -> service.search(1, new PolicySearchRequest("PURCHASE", List.of())))
        .isInstanceOf(ApiException.class)
        .extracting(exception -> ((ApiException) exception).code())
        .isEqualTo("POLICY_CATALOG_UNAVAILABLE");
  }

  @Test
  void exactCosineRejectsWrongDimensionAndNonFiniteNumbers() {
    double[] valid = new double[1024];
    valid[0] = 1;

    assertThatThrownBy(() -> PolicySearchService.cosine(valid, new double[1023]))
        .isInstanceOf(IllegalArgumentException.class);
    valid[4] = Double.NaN;
    assertThatThrownBy(() -> PolicySearchService.cosine(valid, valid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactCosineUsesAllDimensions() {
    double[] left = new double[1024];
    double[] right = new double[1024];
    left[0] = 1;
    left[1023] = 1;
    right[0] = 1;

    assertThat(PolicySearchService.cosine(left, right)).isEqualTo(1 / Math.sqrt(2));
  }

  @Test
  void exactCosineDoesNotOverflowForLargeFiniteParallelVectors() {
    double[] left = new double[1024];
    double[] right = new double[1024];
    java.util.Arrays.fill(left, 1e150);
    java.util.Arrays.fill(right, 1e150);

    assertThat(PolicySearchService.cosine(left, right)).isEqualTo(1.0);
  }

  @Test
  void hidesCalculationModeWithoutApprovedRuleAndUsesMetadataFallbacks() {
    double[] vector = new double[1024];
    vector[0] = 1;
    PolicyRepository.Candidate candidate =
        new PolicyRepository.Candidate(
            7,
            8,
            "정책",
            "기본 요약",
            "계획 연결",
            "2026-v1",
            Instant.parse("2026-08-31T00:00:00Z"),
            "ONE_TIME_FUNDING",
            vector,
            Map.of(),
            "기관",
            "https://example.invalid/policy",
            "section-1",
            false,
            "ALLOW",
            "NATIONAL",
            List.of(),
            null,
            null);
    StubPolicyRepository repository =
        new StubPolicyRepository(List.of(), List.of(candidate), vector);
    PolicySearchService service = new PolicySearchService(repository);

    PolicyDtos.PolicyResult result =
        ((PolicyDtos.PolicyResultsResponse)
                service.search(1, new PolicySearchRequest("PURCHASE", List.of())))
            .results()
            .getFirst();

    assertThat(result.calculationMode()).isEqualTo("INFORMATIONAL");
    assertThat(result.supportDetails()).isEqualTo("기본 요약");
    assertThat(result.additionalChecks()).isEmpty();
    assertThat(result.applicationPeriod()).isEqualTo("공식 페이지 확인");
  }

  private List<PolicyQuestionOption> options(String... values) {
    return java.util.Arrays.stream(values)
        .map(value -> new PolicyQuestionOption(value, value))
        .toList();
  }

  private static final class StubPolicyRepository extends PolicyRepository {
    private final List<PolicyQuestion> questions;
    private final List<PolicyRepository.Candidate> candidates;
    private final double[] query;

    StubPolicyRepository(List<PolicyQuestion> questions) {
      this(questions, List.of(), new double[1024]);
    }

    StubPolicyRepository(
        List<PolicyQuestion> questions,
        List<PolicyRepository.Candidate> candidates,
        double[] query) {
      super(null, new com.fasterxml.jackson.databind.ObjectMapper());
      this.questions = questions;
      this.candidates = candidates;
      this.query = query;
    }

    @Override
    PolicyRepository.SearchCatalog activeCatalog(int userId, String supportGoal) {
      return new PolicyRepository.SearchCatalog(
          1L,
          query,
          questions,
          new PolicyRepository.UserEligibilityProfile(null, null),
          candidates);
    }

    @Override
    void record(long snapshotId, String supportGoal, List<Long> versionIds, long latencyMs) {}
  }
}
