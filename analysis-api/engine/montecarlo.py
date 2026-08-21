"""부트스트랩 몬테카를로: 과거 소비 분포에서 12개월 잔고 경로를 대량 샘플링.

MCTS 아님 — 행동 선택/가지치기 없는 순수 rollout. [n_paths, horizon] 벡터 연산만.
파라메트릭 가정 대신 과거 월별 지출 벡터를 그대로 리샘플링(부트스트랩).
"""

import numpy as np

from engine.categories import CATEGORIES, VARIABLE_CATS

_IS_VARIABLE = np.array([c in VARIABLE_CATS for c in CATEGORIES])

# 강도 프리셋: 변동비 절감률
INTENSITY_PRESETS = {"loose": 0.10, "medium": 0.20, "hard": 0.30}


def simulate(
    user_hist: np.ndarray,
    income: float,
    reduction_pct: float,
    n_paths: int = 5000,
    horizon: int = 12,
    seed: int | None = None,
) -> np.ndarray:
    """user_hist: [T, n_cats] 과거 월별 카테고리 지출.
    반환: [3, horizon] 의 (p10, p50, p90) 누적잔고 band.
    """
    rng = np.random.default_rng(seed)
    T = user_hist.shape[0]
    idx = rng.integers(0, T, size=(n_paths, horizon))  # 월 벡터 부트스트랩 리샘플링
    sampled = user_hist[idx]  # [n_paths, horizon, n_cats]

    reduced = np.where(_IS_VARIABLE[None, None, :], sampled * (1 - reduction_pct), sampled)
    total_spend = reduced.sum(axis=2)  # [n_paths, horizon]
    net = income - total_spend
    balance = np.cumsum(net, axis=1)  # [n_paths, horizon]

    band = np.percentile(balance, [10, 50, 90], axis=0)  # [3, horizon]
    return band


def simulate_all_intensities(
    user_hist: np.ndarray,
    income: float,
    n_paths: int = 5000,
    horizon: int = 12,
    seed: int | None = None,
) -> dict:
    return {
        name: simulate(user_hist, income, pct, n_paths, horizon, seed)
        for name, pct in INTENSITY_PRESETS.items()
    }


if __name__ == "__main__":
    from engine.synth_mock import generate_users

    data = generate_users(n_users=1, n_months=24, seed=1)[0]  # [24, n_cats]
    income = data.sum(axis=1).mean() * 1.25

    band = simulate(data, income, reduction_pct=0.2, seed=0)
    assert band.shape == (3, 12)
    assert (band[0] <= band[1]).all() and (band[1] <= band[2]).all(), "p10<=p50<=p90 깨짐"

    bands = simulate_all_intensities(data, income, seed=0)
    assert bands["hard"][1, -1] > bands["loose"][1, -1], "절감률 높을수록 잔고 p50 높아야 함"

    print("montecarlo.py self-check OK", {k: v[1, -1].round(0) for k, v in bands.items()})
