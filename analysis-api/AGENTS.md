# analysis-api — FastAPI·엔진 규칙

루트 `AGENTS.md`를 상속한다. 내부 계약은 `../API/openapi-internal.yaml`, 계산 결정은
`../docs/기획서.md`, LLM 평가는 `bench/qwen35-2b-evaluation.md`가 기준이다.

## 경계

- `app/`: 요청 검증, 엔진·설명 조합
- `engine/`: FastAPI·DB·LLM 의존 없는 순수 계산
- `bench/`: 리소스·모델 실험
- `tests/`: 내부 API와 계산 계약

LLM 코드는 `engine/` 밖에 둔다. 모델 평가 완료와 운영 `/internal/explanations` 구현 완료를
혼동하지 않는다.

## 계산

- 금액은 원 단위 정수, 난수 함수는 `seed` 필수다.
- 몬테카를로는 `IID_BOOTSTRAP`, 10,000경로와 NumPy 벡터 연산을 사용한다.
- 같은 계획의 강도는 같은 경로를 공유하고 절감률마다 재시뮬레이션하지 않는다.
- 변경한 `engine/` 모듈은 `if __name__ == "__main__":` assert 셀프체크를 둔다.
- Python 함수·메서드는 입력과 반환 역할을 설명하는 짧은 docstring을 둔다.

경계 사례: 소득 0, 지출>소득, 잔여기간 1개월, coverage 미달, 최대 절감으로도 목표 미달,
정수 경계.

## LLM

- `qwen3.5:2b-q4_K_M`, context 2K, 활성 추론 1개
- 확정 JSON만 입력하고 모든 출력 숫자를 정규화해 원본과 대조
- 최대 2회 교정, 10~15초 timeout, 최종 숫자 없는 `200 FALLBACK`
- 실제 모델 검증 뒤에만 `llmReady=true`와 모델명 노출
- LangGraph 도입이나 AWS `t4g.large` 확정을 가정하지 않는다.

## 소유권과 검증

Analysis 에이전트는 `analysis-api/`만 수정한다. `API/`, `sql/`, `docs/`, `TODO.md`와 다른
서비스 변경은 메인에게 반환한다.

```bash
uv run ruff check .
uv run python -m unittest discover -s tests -v
uv run python -m engine.<변경한_모듈>
```

적대적 QA는 숫자·단위 변조, 외국어 혼입, malformed JSON, timeout, Ollama 단절, 재시도
상한과 fallback이 계산 결과를 바꾸지 않는지 확인한다.
