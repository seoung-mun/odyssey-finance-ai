package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dacon.core.policy.PolicyDtos.PolicyResultsResponse;
import com.dacon.core.policy.PolicyDtos.PolicySearchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyEligibilitySearchTest {
  private static final LocalDate EVALUATION_DATE = LocalDate.of(2026, 9, 3);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneId.of("Asia/Seoul"));

  @Test
  void appliesStatusRegionAndAgeBeforeCosineAndFailsClosed() {
    List<PolicyRepository.Candidate> candidates =
        List.of(
            candidate(1, "허용", vector(0.8), "ALLOW", "LOCAL", List.of("11110"), 19, 34),
            candidate(2, "제외", invalidVector(), "EXCLUDE", "LOCAL", List.of("11110"), 19, 34),
            candidate(3, "재확인", invalidVector(), "RECHECK", "LOCAL", List.of("11110"), 19, 34),
            candidate(4, "상태 누락", invalidVector(), null, "LOCAL", List.of("11110"), 19, 34),
            candidate(5, "지역 불일치", invalidVector(), "ALLOW", "LOCAL", List.of("26110"), 19, 34),
            candidate(6, "연령 불일치", invalidVector(), "ALLOW", "LOCAL", List.of("11110"), 20, 34),
            candidate(7, "지역 메타 누락", invalidVector(), "ALLOW", null, null, 19, 34),
            candidate(8, "연령 메타 불완전", invalidVector(), "ALLOW", "NATIONAL", List.of(), 19, null));

    assertThat(search(candidates, "11110", LocalDate.of(2007, 9, 3)).results())
        .extracting(PolicyDtos.PolicyResult::title)
        .containsExactly("허용");
  }

  @Test
  void nationalPassesWithoutRegionWhileLocalRequiresExactMembership() {
    List<PolicyRepository.Candidate> candidates =
        List.of(
            candidate(1, "전국", vector(0.9), "ALLOW", "NATIONAL", List.of(), null, null),
            candidate(
                2, "지역 일치", vector(0.8), "ALLOW", "LOCAL", List.of("11110", "29110"), null, null),
            candidate(3, "다른 지역", vector(0.7), "ALLOW", "LOCAL", List.of("26110"), null, null));

    assertThat(search(candidates, "11110", null).results())
        .extracting(PolicyDtos.PolicyResult::title)
        .containsExactly("전국", "지역 일치");
    assertThat(search(candidates, null, null).results())
        .extracting(PolicyDtos.PolicyResult::title)
        .containsExactly("전국");
  }

  @Test
  void unrestrictedAgePassesWithOrWithoutBirthDate() {
    PolicyRepository.Candidate unrestricted =
        candidate(1, "연령 무관", vector(1), "ALLOW", "NATIONAL", List.of(), null, null);

    assertThat(search(List.of(unrestricted), null, null).results()).hasSize(1);
    assertThat(search(List.of(unrestricted), null, LocalDate.of(1980, 1, 1)).results()).hasSize(1);
  }

  @Test
  void boundedAgeUsesInclusiveBirthdayBoundariesAndRequiresBirthDate() {
    PolicyRepository.Candidate bounded =
        candidate(1, "19~34세", vector(1), "ALLOW", "NATIONAL", List.of(), 19, 34);

    assertThat(search(List.of(bounded), null, LocalDate.of(2007, 9, 3)).results()).hasSize(1);
    assertThat(search(List.of(bounded), null, LocalDate.of(1992, 9, 3)).results()).hasSize(1);
    assertThat(search(List.of(bounded), null, LocalDate.of(2007, 9, 4)).results()).isEmpty();
    assertThat(search(List.of(bounded), null, LocalDate.of(1991, 9, 3)).results()).isEmpty();
    assertThat(search(List.of(bounded), null, null).results()).isEmpty();
    assertThat(search(List.of(bounded), null, EVALUATION_DATE.plusDays(1)).results()).isEmpty();
  }

  @Test
  void returnsNaturalZeroOneTwoOrTopThreeCounts() {
    for (int candidateCount = 0; candidateCount <= 4; candidateCount++) {
      List<PolicyRepository.Candidate> candidates = new ArrayList<>();
      for (int index = 0; index < candidateCount; index++) {
        candidates.add(
            candidate(
                index + 1,
                "정책 " + index,
                vector(1.0 - index * 0.1),
                "ALLOW",
                "NATIONAL",
                List.of(),
                null,
                null));
      }

      assertThat(search(candidates, null, null).results()).hasSize(Math.min(candidateCount, 3));
    }
  }

  @Test
  void preservesRelativeCosineRankingAmongEligibleCandidates() {
    List<PolicyRepository.Candidate> candidates =
        List.of(
            candidate(1, "낮음", vector(0.2), "ALLOW", "NATIONAL", List.of(), null, null),
            candidate(2, "높음", vector(0.9), "ALLOW", "NATIONAL", List.of(), null, null),
            candidate(3, "중간", vector(0.6), "ALLOW", "NATIONAL", List.of(), null, null));

    assertThat(search(candidates, null, null).results())
        .extracting(PolicyDtos.PolicyResult::title)
        .containsExactly("높음", "중간", "낮음");
  }

  private PolicyResultsResponse search(
      List<PolicyRepository.Candidate> candidates, String regionCode, LocalDate birthDate) {
    StubPolicyRepository repository =
        new StubPolicyRepository(
            candidates, new PolicyRepository.UserEligibilityProfile(regionCode, birthDate));
    return (PolicyResultsResponse)
        new PolicySearchService(repository, CLOCK)
            .search(42, new PolicySearchRequest("PURCHASE", List.of()));
  }

  private PolicyRepository.Candidate candidate(
      long id,
      String title,
      double[] embedding,
      String decision,
      String regionScope,
      List<String> regionCodes,
      Integer ageMin,
      Integer ageMax) {
    return new PolicyRepository.Candidate(
        id,
        id,
        title,
        "요약",
        "계획 연결",
        "2026-v1",
        Instant.parse("2026-09-01T00:00:00Z"),
        "INFORMATIONAL",
        embedding,
        Map.of(),
        "기관",
        "https://example.invalid/policy/" + id,
        "section-" + id,
        false,
        decision,
        regionScope,
        regionCodes,
        ageMin,
        ageMax);
  }

  private double[] vector(double cosine) {
    double[] vector = new double[1024];
    vector[0] = cosine;
    vector[1] = Math.sqrt(1 - cosine * cosine);
    return vector;
  }

  private double[] invalidVector() {
    return new double[] {1};
  }

  private static final class StubPolicyRepository extends PolicyRepository {
    private final List<PolicyRepository.Candidate> candidates;
    private final PolicyRepository.UserEligibilityProfile profile;

    StubPolicyRepository(
        List<PolicyRepository.Candidate> candidates,
        PolicyRepository.UserEligibilityProfile profile) {
      super(null, new ObjectMapper());
      this.candidates = candidates;
      this.profile = profile;
    }

    @Override
    PolicyRepository.SearchCatalog activeCatalog(int userId, String supportGoal) {
      double[] query = new double[1024];
      query[0] = 1;
      return new PolicyRepository.SearchCatalog(1L, query, List.of(), profile, candidates);
    }

    @Override
    void record(long snapshotId, String supportGoal, List<Long> versionIds, long latencyMs) {}
  }
}
