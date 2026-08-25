package com.dacon.core.plan;

/** 저장된 계획 식별자와 설명 queue 입력 hash다. */
public record SavedPlan(int planVersionId, String inputHash) {}
