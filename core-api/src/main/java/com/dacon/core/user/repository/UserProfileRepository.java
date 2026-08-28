package com.dacon.core.user.repository;

import com.dacon.core.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

/** 인적 프로필 영속화를 Spring Data에 위임한다. */
public interface UserProfileRepository extends JpaRepository<UserProfile, Integer> {}
