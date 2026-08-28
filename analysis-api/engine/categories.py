"""지출 카테고리 분류 체계 (기획서 5-1). synth_mock.py와 bootstrap_from_tabformer.py가 공유."""

CATEGORIES = [
    "food",
    "transport",
    "shopping",
    "health",
    "leisure",
    "other",
    "subscription",
    "fixed",
]
# 변동비 vs 고정비 (몬테카를로 절감 프리셋이 변동비에만 적용됨)
VARIABLE_CATS = {"food", "transport", "shopping", "health", "leisure", "other"}
FIXED_CATS = {"subscription", "fixed"}


if __name__ == "__main__":
    assert len(CATEGORIES) == 8
    assert VARIABLE_CATS.isdisjoint(FIXED_CATS) and VARIABLE_CATS | FIXED_CATS == set(CATEGORIES)
    print("categories.py self-check OK")
