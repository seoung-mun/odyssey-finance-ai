"""TabFormer(IBM 신용카드 거래, card_transaction.v1.csv) 실측 데이터에서 유저별
월별 카테고리 지출을 뽑는다. **synth_mock.py와 반대로, 이 모듈이 만드는 배열이
실제 몬테카를로(5-5) 입력이다.**

기획서 4-1: 24M행 · 유저 2,000명 → 유저 100~500명 서브샘플링해서 사용.
MCC(가맹점 업종코드) → 카테고리 매핑은 상위 20개 MCC(전체 거래 대다수) 기준
(기획서 4-2). subscription은 이 데이터로 식별 불가 — 고정비는 애초에
데이터셋이 아니라 사용자 입력에서 온다(기획서 4-1 각주)는 전제와 일치하므로
0으로 남겨도 무방.
"""

import numpy as np
import pandas as pd

from engine.categories import CATEGORIES

DATA_PATH = "data/credit_card/card_transaction.v1.csv"
USECOLS = ["User", "Year", "Month", "Amount", "MCC"]
CHUNK = 500_000

_MCC_CATEGORY = {
    5411: "food",  # 마트/슈퍼마켓
    5499: "food",  # 편의점/기타 식료품
    5812: "food",  # 음식점
    5814: "food",  # 패스트푸드
    5912: "food",  # 약국(생필품 소비로 묶음)
    5541: "transport",  # 주유소
    4121: "transport",  # 택시
    4784: "transport",  # 통행료
    5300: "shopping",  # 창고형 매장
    5310: "shopping",  # 할인점
    5311: "shopping",  # 백화점
    5921: "shopping",  # 주류
    5813: "shopping",  # 술집
    5942: "shopping",  # 서점
    7832: "shopping",  # 영화관
    4900: "fixed",  # 유틸리티
    4814: "fixed",  # 통신
    7538: "other",  # 자동차 정비
    4829: "other",  # 송금
}


def _mcc_to_category(mcc: pd.Series) -> pd.Series:
    return mcc.map(_MCC_CATEGORY).fillna("other")


def load_user_monthly_spend(
    n_users: int = 200,
    min_months: int = 12,
    seed: int = 0,
    path: str = DATA_PATH,
) -> np.ndarray:
    """[n_users, n_months, n_categories] 유저별 월별 카테고리 지출 합계.

    min_months 이상 관측된 유저 중에서 무작위 서브샘플링. 유저마다 관측
    개월수가 달라, 선택된 유저들의 최소 공통 개월수(가장 최근 달 기준)로
    배열 형태를 맞춘다.

    ponytail: 매 호출마다 2.2GB 전체를 청크 스캔한다(수 분 소요). 실서비스에선
    한 번 돌려서 parquet/npz로 캐싱해두고 그 캐시를 로드해야 한다.
    """
    partials = []
    for chunk in pd.read_csv(path, usecols=USECOLS, chunksize=CHUNK):
        amount = chunk["Amount"].str.replace("$", "", regex=False).astype(float)
        chunk = chunk.assign(amount=amount, category=_mcc_to_category(chunk["MCC"]))
        chunk = chunk[chunk["amount"] > 0]  # 환불(음수) 제외
        partials.append(chunk.groupby(["User", "Year", "Month", "category"])["amount"].sum())

    # 유저의 같은 달 거래가 청크 경계에 걸쳐 나뉠 수 있어 부분합을 다시 합산
    monthly = pd.concat(partials).groupby(level=[0, 1, 2, 3]).sum()
    monthly = monthly.unstack("category", fill_value=0.0)
    monthly = monthly.reindex(columns=CATEGORIES, fill_value=0.0)

    counts = monthly.groupby(level="User").size()
    eligible = counts[counts >= min_months].index.to_numpy()

    rng = np.random.default_rng(seed)
    chosen = rng.choice(eligible, size=min(n_users, len(eligible)), replace=False)

    n_months = int(counts.loc[chosen].min())
    arrays = [monthly.loc[u].sort_index().tail(n_months).to_numpy() for u in chosen]
    return np.stack(arrays)


if __name__ == "__main__":
    data = load_user_monthly_spend(n_users=20, min_months=12, seed=0)
    assert data.ndim == 3
    assert data.shape[0] == 20
    assert data.shape[2] == len(CATEGORIES)
    assert data.shape[1] >= 12
    assert (data >= 0).all()
    print("bootstrap_from_tabformer.py self-check OK", data.shape)
