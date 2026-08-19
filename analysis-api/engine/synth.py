"""합성 거래 로그 생성. 벤치 입력이자 VAE/몬테카를로 개발용 픽스처.

카테고리는 기획서 4-3 리매핑 결과 스키마: 식비/교통/쇼핑/구독/고정비/기타.
"""
import numpy as np

CATEGORIES = ["food", "transport", "shopping", "subscription", "fixed", "other"]
# 변동비 vs 고정비 (몬테카를로 절감 프리셋이 변동비에만 적용됨)
VARIABLE_CATS = {"food", "transport", "shopping", "other"}
FIXED_CATS = {"subscription", "fixed"}

# 카테고리별 대략적인 월 지출 baseline (원)
_BASE_MEAN = {
    "food": 500_000,
    "transport": 150_000,
    "shopping": 300_000,
    "subscription": 50_000,
    "fixed": 700_000,
    "other": 100_000,
}


def generate_users(n_users: int, n_months: int = 24, seed: int = 42) -> np.ndarray:
    """[n_users, n_months, n_categories] 지출 배열. 유저별 baseline + 계절성 + 노이즈."""
    rng = np.random.default_rng(seed)
    n_cats = len(CATEGORIES)
    base = np.array([_BASE_MEAN[c] for c in CATEGORIES])  # [n_cats]

    # 유저별 개인 성향 배율 (0.6~1.6배)
    user_scale = rng.uniform(0.6, 1.6, size=(n_users, 1, n_cats))
    # 계절성: 12개월 주기 sin 변동 (변동비만 적용, ±15%)
    months = np.arange(n_months)
    season = 1.0 + 0.15 * np.sin(2 * np.pi * months / 12.0)[None, :, None]
    is_variable = np.array([c in VARIABLE_CATS for c in CATEGORIES])
    season_factor = np.where(is_variable[None, None, :], season, 1.0)

    mean = base[None, None, :] * user_scale * season_factor
    noise = rng.normal(1.0, 0.12, size=(n_users, n_months, n_cats))
    data = np.clip(mean * noise, 0, None)
    return data


def inject_anomaly(data: np.ndarray, user_idx: int, month_idx: int,
                    category: str = "shopping", multiplier: float = 4.0) -> np.ndarray:
    """특정 유저·월·카테고리에 이상 지출 주입한 복사본 반환."""
    out = data.copy()
    cat_idx = CATEGORIES.index(category)
    out[user_idx, month_idx, cat_idx] *= multiplier
    return out


if __name__ == "__main__":
    d = generate_users(n_users=5, n_months=24)
    assert d.shape == (5, 24, len(CATEGORIES))
    assert (d >= 0).all()
    d2 = inject_anomaly(d, user_idx=0, month_idx=10, category="shopping", multiplier=5.0)
    cat_idx = CATEGORIES.index("shopping")
    assert d2[0, 10, cat_idx] > d[0, 10, cat_idx] * 4
    assert np.array_equal(d2[1:], d[1:])  # 다른 유저는 안 건드림
    print("synth.py self-check OK", d.shape)
