package com.dacon.core.chat;

import java.util.Locale;

public class DeterministicIntentClassifier implements IntentClassifierPort {
  @Override
  public ChatIntent classify(String message) {
    String normalized = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    if (containsAny(normalized, "적금추천", "상품추천", "저축상품")) {
      return ChatIntent.SAVINGS_RECOMMENDATION;
    }
    if (containsAny(normalized, "우대조건", "우대금리", "예상이자", "whatif", "왓이프")) {
      return ChatIntent.SAVINGS_WHAT_IF;
    }
    if (containsAny(normalized, "계획현황", "목표현황", "달성현황", "진행상황")) {
      return ChatIntent.PLAN_STATUS;
    }
    if (containsAny(normalized, "소비요약", "지출요약", "이번달소비", "이번달지출")) {
      return ChatIntent.SPENDING_SUMMARY;
    }
    if (containsAny(normalized, "재계획", "계획변경")) {
      return ChatIntent.REPLAN_GUIDE;
    }
    if (containsAny(normalized, "정책검색", "지원정책", "주거정책")) {
      return ChatIntent.POLICY_SEARCH;
    }
    if (containsAny(normalized, "도움말", "뭘할수", "기능안내")) {
      return ChatIntent.HELP;
    }
    return ChatIntent.UNKNOWN;
  }

  private boolean containsAny(String value, String... candidates) {
    for (String candidate : candidates) {
      if (value.contains(candidate)) {
        return true;
      }
    }
    return false;
  }
}
