package com.dacon.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeterministicIntentClassifierTest {
  private final DeterministicIntentClassifier classifier = new DeterministicIntentClassifier();

  @ParameterizedTest
  @CsvSource({
    "내 계획에 맞는 적금 추천해 줘,SAVINGS_RECOMMENDATION",
    "이번 달 소비 요약을 보고 싶어,SPENDING_SUMMARY",
    "청년 주거 정책 검색할래,POLICY_SEARCH",
    "오늘 날씨가 어때,UNKNOWN"
  })
  void classifiesKoreanRequestsWithoutA_model(String message, ChatIntent expected) {
    assertThat(classifier.classify(message)).isEqualTo(expected);
  }
}
