package com.dacon.core.chat;

import com.dacon.core.savings.SavingsDtos.RecommendationListResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class ChatDtos {
  private ChatDtos() {}

  public record ChatSelection(
      @Positive Long productId,
      @Positive Long optionId,
      @Size(max = 20) List<@Positive Long> conditionIds) {
    public ChatSelection {
      conditionIds = conditionIds == null ? List.of() : List.copyOf(conditionIds);
    }
  }

  public record ChatRequest(
      @Size(max = 36) String sessionId,
      @NotBlank @Size(max = 500) String message,
      @Valid ChatSelection selection) {}

  public record ChatResponse(
      String sessionId,
      ChatIntent intent,
      String sessionMode,
      String message,
      RecommendationListResponse savingsRecommendations) {}
}
