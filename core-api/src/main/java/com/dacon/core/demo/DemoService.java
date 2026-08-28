package com.dacon.core.demo;

import com.dacon.core.error.ApiException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 데모 목록 조회와 사용자 seed 오류를 공개 API 상태로 변환한다. */
@Service
public class DemoService {
  private final DemoRepository repository;

  public DemoService(DemoRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public List<DemoDtos.DemoTester> testers() {
    return repository.findLatestTesters().stream().map(DemoScenario::response).toList();
  }

  @Transactional
  public DemoDtos.DemoSeedResponse seed(int userId, String testerId) {
    try {
      DemoRepository.DemoSeedRow seeded = repository.seed(userId, testerId);
      return new DemoDtos.DemoSeedResponse(
          seeded.getTesterId(), seeded.getScenarioVersion(), seeded.getSeededAt());
    } catch (DataAccessException exception) {
      String message = exception.getMostSpecificCause().getMessage();
      if (message != null && message.contains("DEMO_TESTER_NOT_FOUND")) {
        throw new ApiException(HttpStatus.NOT_FOUND, "DEMO_TESTER_NOT_FOUND", "데모 테스터가 없습니다.");
      }
      if (message != null && message.contains("DEMO_SEED_CONFLICT")) {
        throw new ApiException(
            HttpStatus.CONFLICT, "DEMO_SEED_CONFLICT", "기존 데이터가 있는 사용자는 데모 seed를 시작할 수 없습니다.");
      }
      throw exception;
    }
  }
}
