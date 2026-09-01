package com.dacon.core.policy;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 정책 검색 HTTP 계약과 오프라인 artifact command 입력을 모은다. */
public final class PolicyDtos {
  private PolicyDtos() {}

  public record PolicySearchRequest(
      @NotNull
          @Pattern(
              regexp =
                  "PURCHASE|JEONSE|MONTHLY_RENT|PUBLIC_RENTAL|SUBSCRIPTION|MOVING_COST|GUARANTEE|DORMITORY")
          String supportGoal,
      @Valid @Size(max = 3) List<PolicyAnswer> answers) {
    public PolicySearchRequest {
      answers = answers == null ? List.of() : List.copyOf(answers);
    }
  }

  public record PolicyAnswer(
      @NotBlank @Size(max = 50) String questionId, @NotBlank @Size(max = 100) String value)
      implements RejectUnknownFields {}

  public sealed interface PolicySearchResponse
      permits PolicyQuestionResponse, PolicyResultsResponse {}

  public record PolicyQuestionResponse(String type, PolicyQuestion question)
      implements PolicySearchResponse {
    public PolicyQuestionResponse(PolicyQuestion question) {
      this("QUESTION", question);
    }
  }

  public record PolicyQuestion(
      String questionId, String label, List<PolicyQuestionOption> options) {}

  public record PolicyQuestionOption(String value, String label) {}

  public record PolicyResultsResponse(String type, List<PolicyResult> results)
      implements PolicySearchResponse {
    public PolicyResultsResponse(List<PolicyResult> results) {
      this("RESULTS", results);
    }
  }

  public record PolicyResult(
      long policyVersionId,
      String title,
      String summary,
      String planConnection,
      String supportDetails,
      List<String> confirmedConditions,
      List<String> additionalChecks,
      String applicationPeriod,
      LocalDate asOfDate,
      String eligibilityStatus,
      String calculationMode,
      PolicyOfficialSource source) {}

  public record PolicyOfficialSource(
      String organization,
      String officialUrl,
      String sourceVersion,
      Instant lastVerifiedAt,
      List<String> locators) {}

  public record PolicyScenarioRequest(
      @Positive int currentPlanVersionId,
      @NotNull
          @Pattern(
              regexp =
                  "PURCHASE|JEONSE|MONTHLY_RENT|PUBLIC_RENTAL|SUBSCRIPTION|MOVING_COST|GUARANTEE|DORMITORY")
          String supportGoal,
      @Size(max = 3) List<@Valid PolicyAnswer> answers,
      @NotNull @Valid ConfirmedAward confirmedAward)
      implements RejectUnknownFields {
    public PolicyScenarioRequest {
      answers = answers == null ? List.of() : List.copyOf(answers);
    }
  }

  private interface RejectUnknownFields {
    @JsonAnySetter
    default void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("unknown field");
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ConfirmedOneTimeAward.class, name = "ONE_TIME_FUNDING"),
    @JsonSubTypes.Type(value = ConfirmedMonthlyAward.class, name = "MONTHLY_EXPENSE_REDUCTION")
  })
  public sealed interface ConfirmedAward extends RejectUnknownFields
      permits ConfirmedOneTimeAward, ConfirmedMonthlyAward {
    String type();

    Boolean institutionConfirmed();

    JsonNode amountWon();

    String startYearMonth();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record ConfirmedOneTimeAward(
      @Pattern(regexp = "ONE_TIME_FUNDING") String type,
      @NotNull @AssertTrue Boolean institutionConfirmed,
      JsonNode amountWon,
      @NotNull @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String startYearMonth)
      implements ConfirmedAward {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record ConfirmedMonthlyAward(
      @Pattern(regexp = "MONTHLY_EXPENSE_REDUCTION") String type,
      @NotNull @AssertTrue Boolean institutionConfirmed,
      JsonNode amountWon,
      @NotNull @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String startYearMonth,
      @NotNull @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String endYearMonth)
      implements ConfirmedAward {}

  public record PolicyArtifact(
      String artifactVersion,
      String manifestSha256,
      String embeddingModel,
      int embeddingDimension,
      List<ArtifactSource> sources,
      List<ArtifactPolicy> policies,
      List<ArtifactQueryProfile> queryProfiles,
      ArtifactReviewGate reviewGate,
      ArtifactToolchain toolchain) {}

  public record ArtifactReviewGate(String status, boolean importable, String reason) {}

  public record ArtifactToolchain(
      String backend,
      String blas,
      String byteorder,
      String machine,
      String numpy,
      String python,
      String safetensors,
      String scipy,
      String sentenceTransformers,
      String system,
      String tokenizers,
      String torch,
      String torchBuildSha256,
      String transformers) {}

  public record ArtifactSource(
      String sourceKey,
      String organization,
      String officialUrl,
      String contentSha256,
      Instant retrievedAt,
      List<String> bodyMarkers,
      String contentType,
      String finalUrl,
      int httpStatus,
      String textSha256) {}

  public record ArtifactPolicy(
      String policyKey,
      String title,
      String supportGoal,
      String summary,
      String planConnection,
      ArtifactVersion version) {}

  public record ArtifactVersion(
      String sourceVersion,
      String reviewStatus,
      String calculationMode,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      Instant lastVerifiedAt,
      String sourceKey,
      String sourceLocator,
      String locatorSha256,
      List<ArtifactChunk> chunks,
      ArtifactCalculationRule calculationRule) {}

  public record ArtifactChunk(
      int chunkIndex, String content, List<Double> embedding, JsonNode metadata) {}

  public record ArtifactQueryProfile(
      String supportGoal,
      String queryText,
      List<Double> embedding,
      List<PolicyQuestion> questionFlow) {}

  public record ArtifactCalculationRule(
      String adjustmentType,
      long amountUpperBound,
      Short maxMonths,
      String sourceVersion,
      String approvedLocator,
      String approvedSha256,
      JsonNode goldenCase,
      Instant humanApprovedAt,
      String reviewer) {}
}
