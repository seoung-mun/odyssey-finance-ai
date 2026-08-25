# 소비 하한 계산 평가

## Baseline

- FastAPI v0.2.0은 소비 하한 입력·출력이 없다.
- Spring과 Web은 골격만 있어 저장·표시 경로가 없다.

## 합격 조건

- `OFF`, `AUTO`, `CUSTOM`이 계약대로 요청 하한을 결정한다.
- `AUTO`는 과거→최근 순서의 완전월 6~12개에서 선형 p20을 ties-to-even 원 단위로 반올림한다.
- `effectiveFloor=min(requestedFloor,currentAvgVariableSpending)`이며 평균 0이면 하한과 `r_max`가 0이다.
- 모든 PRESET의 추천액은 하한 이상이고 절감률·coverage·band가 같은 확정 추천액에서 파생된다.
- CUSTOM이 확정 하한보다 작으면 422 `INVALID_INPUT`이며 조용히 보정하지 않는다.
- 같은 seed와 input snapshot은 같은 결과·hash를 만든다.

## 집중·적대 테스트

- 세 모드, AUTO 5/6/12/13개월, p20 .5 tie, 평균 0, 하한이 평균 초과, int64 경계.
- 하한 미적용·적용·목표 coverage 미달, CUSTOM 하한 직전/동일/직후.
- 옵션과 band의 추천액 일치, 10,000×120 성능 회귀.

## 회귀

```bash
cd analysis-api
uv run ruff check .
uv run python -m unittest discover -s tests -v
uv run python -m engine.planning
```

