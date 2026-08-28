package com.dacon.core.auth;

/**
 * OIDC 검증을 마친 Google 계정 정보다. 인증 서비스는 검증 전 claim을 받지 않는다.
 *
 * @param subject Google 안에서 안정적인 사용자 식별자
 * @param email 검증된 이메일
 * @param displayName 선택적 표시 이름
 * @param profileImageUrl 선택적 프로필 이미지 URL
 */
record GoogleIdentity(String subject, String email, String displayName, String profileImageUrl) {}
