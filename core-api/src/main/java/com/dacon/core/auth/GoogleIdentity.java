package com.dacon.core.auth;

record GoogleIdentity(String subject, String email, String displayName, String profileImageUrl) {}
