package com.dacon.core.auth;

/** access·refresh token과 신규 사용자 여부를 서비스 내부에서 운반한다. */
record AuthResult(String accessToken, String refreshToken, boolean isNewUser) {}
