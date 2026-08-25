package com.dacon.core.auth;

record AuthResult(String accessToken, String refreshToken, boolean isNewUser) {}
