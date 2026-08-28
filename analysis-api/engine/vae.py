"""VAE 기반 이상탐지. 유저 본인의 과거 시퀀스만으로 학습·스코어링 (individual-level,
population 비교 없음 — 기획서 4-3/5-2 원칙).

피처는 절대금액이 아니라 정규화 값: 카테고리 비중 + 전월 대비 총지출 변화율.
"""

import numpy as np
import torch
import torch.nn as nn

from engine.categories import CATEGORIES

FEAT_DIM = len(CATEGORIES) + 1  # 카테고리 비중 + 변화율


def compute_features(user_hist: np.ndarray) -> np.ndarray:
    """user_hist: [T, n_cats] -> [T-1, FEAT_DIM]
    (0번째 달은 전월이 없어 change rate 못 구해 제외)."""
    totals = user_hist.sum(axis=1)  # [T]
    props = user_hist / totals[:, None]  # [T, n_cats]
    change = (totals[1:] - totals[:-1]) / totals[:-1]  # [T-1]
    return np.concatenate([props[1:], change[:, None]], axis=1).astype(np.float32)


class VAE(nn.Module):
    def __init__(self, feat_dim: int = FEAT_DIM, hidden: int = 16, latent: int = 8):
        """피처·은닉·잠재 차원을 받아 인코더와 디코더를 구성한다."""

        super().__init__()
        self.enc = nn.Sequential(nn.Linear(feat_dim, hidden), nn.ReLU())
        self.mu = nn.Linear(hidden, latent)
        self.logvar = nn.Linear(hidden, latent)
        self.dec = nn.Sequential(nn.Linear(latent, hidden), nn.ReLU(), nn.Linear(hidden, feat_dim))

    def forward(self, x):
        """입력 텐서에서 재구성값과 잠재분포 파라미터를 반환한다."""

        h = self.enc(x)
        mu, logvar = self.mu(h), self.logvar(h)
        std = torch.exp(0.5 * logvar)
        z = mu + std * torch.randn_like(std)
        recon = self.dec(z)
        return recon, mu, logvar


def train(feats: np.ndarray, epochs: int = 150, lr: float = 0.02) -> VAE:
    """유저 본인 피처만으로 VAE 학습."""
    x = torch.from_numpy(feats)
    model = VAE(feat_dim=feats.shape[1])
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    for _ in range(epochs):
        opt.zero_grad()
        recon, mu, logvar = model(x)
        recon_loss = ((recon - x) ** 2).mean()
        kl = -0.5 * (1 + logvar - mu.pow(2) - logvar.exp()).mean()
        loss = recon_loss + 0.01 * kl
        loss.backward()
        opt.step()
    model.eval()
    return model


def score(model: VAE, feats: np.ndarray) -> np.ndarray:
    """학습된 모델로 재구성오차(MSE) 산출. [T-1]"""
    x = torch.from_numpy(feats)
    with torch.no_grad():
        recon, _, _ = model(x)
        return ((recon - x) ** 2).mean(axis=1).numpy()


def train_and_score(feats: np.ndarray, epochs: int = 150, lr: float = 0.02) -> np.ndarray:
    """벤치용 편의 함수: 학습 + 같은 데이터 스코어링을 한 번에."""
    return score(train(feats, epochs, lr), feats)


if __name__ == "__main__":
    from engine.synth_mock import generate_users, inject_anomaly

    normal = generate_users(n_users=1, n_months=24, seed=7)
    anomalous = inject_anomaly(
        normal, user_idx=0, month_idx=20, category="shopping", multiplier=5.0
    )

    normal_feats = compute_features(normal[0])
    anomalous_feats = compute_features(anomalous[0])
    anomaly_row = 20 - 1  # compute_features가 0번째 달을 버리므로 한 칸 당김

    model = train(normal_feats, epochs=150)
    normal_errs = score(model, normal_feats)
    anomaly_err = score(model, anomalous_feats)[anomaly_row]

    assert anomaly_err > np.median(normal_errs), (
        f"이상 주입 달 재구성오차({anomaly_err:.4f})가 "
        f"평시 중앙값({np.median(normal_errs):.4f})보다 커야 함"
    )
    print(
        "vae.py self-check OK",
        f"anomaly_err={anomaly_err:.4f} normal_median={np.median(normal_errs):.4f}",
    )
