package com.dacon.core.auth;

/** 검증을 마친 Google 계정의 최소 식별 정보를 운반한다. */
record GoogleIdentity(String subject, String email, String displayName, String profileImageUrl) {}
