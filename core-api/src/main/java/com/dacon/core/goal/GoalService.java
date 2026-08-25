package com.dacon.core.goal;

import com.dacon.core.goal.dto.GoalDtos.GoalRequest;
import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseResponse;
import java.util.List;

/** 목표 조회·생성 유스케이스 계약이다. */
public interface GoalService {
  GoalResponse goal(int userId, int goalId);

  List<GoalResponse> goals(int userId, String status);

  GoalResponse create(int userId, GoalRequest input);

  List<ScheduledExpenseResponse> scheduledExpenses(int userId, String status);
}
