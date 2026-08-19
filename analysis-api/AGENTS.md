# analysis-api — Python 세부 규칙

계산 엔진, 몬테카를로, LLM 서빙 + 가드레일. 전체 규칙은 루트 `AGENTS.md` 참고.

## 구조

```
app/        FastAPI 라우트
engine/     순수 계산 로직 (프레임워크 의존 없음)
bench/      리소스 측정 실험
data/       원본 데이터셋 (gitignore, 별도 공유)
```

**`engine/`은 FastAPI를 import하지 않는다.** 순수 함수로 유지해야 셀프체크를
`python -m engine.xxx` 로 바로 돌릴 수 있고, 나중에 배치·테스트에서 재사용된다.

## 명령어

```bash
uv run uvicorn app.main:app --reload
```

```bash
uv run ruff check . --fix
```

```bash
uv run python -m engine.montecarlo
```

## 셀프체크 관례

모든 `engine/` 모듈은 파일 하단에 assert 셀프체크를 둔다. 프레임워크 없이,
**로직이 깨지면 실패하는 가장 작은 것**만:

```python
if __name__ == "__main__":
    band = simulate(data, income, reduction_pct=0.2, seed=0)
    assert band.shape == (3, 12)
    assert (band[0] <= band[1]).all(), "p10<=p50 깨짐"
    print("montecarlo.py self-check OK")
```

난수를 쓰는 함수는 `seed` 인자를 받아야 한다. 셀프체크가 재현 불가능하면 의미가 없다.

## 수치 규칙

- **금액은 정수(원 단위).** float 누적은 반올림 오차가 쌓인다.
- 시뮬레이션은 numpy 벡터 연산으로. `[n_paths, horizon]` 배열 하나로 끝내고
  경로마다 파이썬 루프를 돌지 않는다.
- 절감률을 바꿔가며 재시뮬레이션하지 않는다. 경로를 한 번 생성하고 분위수를
  읽어 폐형식으로 산출한다 (기획서 5-4). 모든 강도가 같은 경로 집합을 공유해야
  시뮬레이션 노이즈로 인한 비일관성이 안 생긴다.

## LLM 취급

`engine/`과 LLM 코드를 같은 모듈에 두지 않는다. 숫자와 설명의 분리(루트
`AGENTS.md` 절대 원칙)를 **파일 경계로도 강제**하는 게 이 구조의 목적이다.

LLM에 넘기는 입력은 이미 확정된 JSON이고, 출력은 반드시 숫자 검증을 통과해야 한다.
