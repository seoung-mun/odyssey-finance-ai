package com.dacon.core.auth;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserAccountRepository extends JpaRepository<UserAccount, Integer> {}
