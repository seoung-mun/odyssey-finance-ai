"""분석 엔진(VAE/몬테카를로) + sLLM 3종의 로컬 리소스 벤치마크.

산출: bench/results.md
"""

import json
import re
import resource
import statistics
import time
from pathlib import Path

import requests

from engine import montecarlo, vae
from engine.synth import generate_users, inject_anomaly

OLLAMA_URL = "http://localhost:11434/api/generate"
MODELS = [
    "qwen2.5:1.5b-instruct-q4_K_M",
    "exaone3.5:2.4b",
    "qwen2.5:7b-instruct-q4_K_M",
]
CONDITIONS = [
    ("Metal", {}),
    ("CPU 전체", {"num_gpu": 0}),
    ("CPU 2스레드", {"num_gpu": 0, "num_thread": 2}),
]
N_REPS = 3

PROMPT_INPUT = {
    "목표_저축액": 3000000,
    "이번달_절감액": 450000,
    "목표_도달_개월": 8,
    "이상_소비": {"카테고리": "배달음식", "증가율_퍼센트": 32},
}
PROMPT = (
    "다음은 한 사용자의 이번 달 재무 분석 결과다. 이 JSON 값만 사용해서 "
    "한국어로 3~4줄짜리 짧은 설명을 써라. 숫자를 새로 만들지 말고 주어진 값만 그대로 언급해라.\n\n"
    f"{json.dumps(PROMPT_INPUT, ensure_ascii=False)}"
)
_ALLOWED_NUMBERS = {"3000000", "450000", "8", "32"}


def rss_mb() -> float:
    # macOS: ru_maxrss는 바이트 단위 (리눅스는 KB)
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024 / 1024


def bench_engine(n_users: int) -> dict:
    data = generate_users(n_users=n_users, n_months=24, seed=1)

    t0 = time.perf_counter()
    incomes = data.sum(axis=2).mean(axis=1) * 1.25
    for i in range(n_users):
        # 재무 계획 계산 엔진 스텁: 카테고리 집계 + 목표 도달 역산 (사칙연산 수준)
        totals = data[i].sum(axis=1)
        variable_total = data[i][:, [0, 1, 2, 5]].sum()  # food/transport/shopping/other
        _ = totals.mean(), variable_total / max(len(totals), 1)
    calc_time = time.perf_counter() - t0

    t0 = time.perf_counter()
    for i in range(n_users):
        montecarlo.simulate_all_intensities(data[i], incomes[i], n_paths=5000, seed=0)
    mc_time = time.perf_counter() - t0

    t0 = time.perf_counter()
    for i in range(n_users):
        feats = vae.compute_features(data[i])
        vae.train_and_score(feats, epochs=150)
    vae_time = time.perf_counter() - t0

    return {
        "n_users": n_users,
        "calc_sec": calc_time,
        "mc_sec": mc_time,
        "vae_sec": vae_time,
        "rss_mb": rss_mb(),
    }


def vae_auroc_check() -> float:
    """synthetic anomaly injection -> 재구성오차가 이상치를 상위로 올리는지
    AUROC로 확인 (기획서 6절)."""
    n_users, n_months = 30, 24
    normal = generate_users(n_users=n_users, n_months=n_months, seed=99)
    labels, scores = [], []
    for i in range(n_users):
        anomaly_month = 20
        anomalous = inject_anomaly(
            normal, user_idx=i, month_idx=anomaly_month, category="shopping", multiplier=4.0
        )
        feats_n = vae.compute_features(normal[i])
        feats_a = vae.compute_features(anomalous[i])
        model = vae.train(feats_n, epochs=150)
        errs = vae.score(model, feats_a)
        row = anomaly_month - 1
        for j, e in enumerate(errs):
            labels.append(1 if j == row else 0)
            scores.append(e)
    # 단순 rank-based AUROC (sklearn 안 씀 — 두 클래스 rank 비교로 충분)
    pos = [s for label, s in zip(labels, scores) if label == 1]
    neg = [s for label, s in zip(labels, scores) if label == 0]
    wins = sum(1 for p in pos for n in neg if p > n) + 0.5 * sum(
        1 for p in pos for n in neg if p == n
    )
    return wins / (len(pos) * len(neg))


def ollama_generate(model: str, options: dict) -> dict:
    r = requests.post(
        OLLAMA_URL,
        json={
            "model": model,
            "prompt": PROMPT,
            "stream": False,
            "options": {**options, "num_predict": 150, "temperature": 0.3},
        },
        timeout=180,
    )
    r.raise_for_status()
    return r.json()


def unload(model: str):
    try:
        requests.post(OLLAMA_URL, json={"model": model, "prompt": "", "keep_alive": 0}, timeout=30)
    except requests.RequestException:
        pass


def extract_numbers(text: str) -> set[str]:
    return {n.replace(",", "") for n in re.findall(r"\d[\d,]*", text)}


def bench_llm() -> list[dict]:
    results = []
    for model in MODELS:
        for cond_name, opts in CONDITIONS:
            reps = []
            last_text = None
            try:
                for _ in range(N_REPS):
                    resp = ollama_generate(model, opts)
                    last_text = resp.get("response", "")
                    eval_count = resp.get("eval_count", 0)
                    eval_ns = resp.get("eval_duration", 1)
                    reps.append(
                        {
                            "total_sec": resp.get("total_duration", 0) / 1e9,
                            "load_sec": resp.get("load_duration", 0) / 1e9,
                            "toks_per_sec": eval_count / (eval_ns / 1e9) if eval_ns else 0,
                        }
                    )
            except requests.RequestException as e:
                results.append({"model": model, "condition": cond_name, "error": str(e)})
                continue

            nums = extract_numbers(last_text or "")
            hallucinated = nums - _ALLOWED_NUMBERS
            results.append(
                {
                    "model": model,
                    "condition": cond_name,
                    "total_sec_median": statistics.median(r["total_sec"] for r in reps),
                    "load_sec_first": reps[0]["load_sec"],
                    "toks_per_sec_median": statistics.median(r["toks_per_sec"] for r in reps),
                    "hallucinated_numbers": sorted(hallucinated),
                    "sample_output": last_text,
                }
            )
        unload(model)
    return results


def write_report(engine_results: list[dict], auroc: float, llm_results: list[dict]):
    lines = ["# 벤치마크 결과", "", "측정 환경: Apple M5 / 10코어 / 16GB", ""]

    lines += [
        "## 1. VAE / 몬테카를로 / 계산엔진",
        "",
        "| 유저 수 | 계산엔진(초) | 몬테카를로(초) | VAE(초) | RSS(MB) |",
        "|---|---|---|---|---|",
    ]
    for r in engine_results:
        lines.append(
            f"| {r['n_users']} | {r['calc_sec']:.3f} | {r['mc_sec']:.3f} | "
            f"{r['vae_sec']:.3f} | {r['rss_mb']:.0f} |"
        )
    lines += [
        "",
        f"VAE 이상탐지 AUROC (synthetic anomaly injection, n=30 users): **{auroc:.3f}**",
        "",
    ]

    lines += [
        "## 2. LLM (sLLM 3종 × 3조건)",
        "",
        "| 모델 | 조건 | 총소요(초, 중앙값) | 첫호출 로드(초) | tok/s(중앙값) | 숫자환각 |",
        "|---|---|---|---|---|---|",
    ]
    for r in llm_results:
        if "error" in r:
            lines.append(f"| {r['model']} | {r['condition']} | ERROR: {r['error']} | | | |")
            continue
        hall = ", ".join(r["hallucinated_numbers"]) if r["hallucinated_numbers"] else "없음"
        lines.append(
            f"| {r['model']} | {r['condition']} | {r['total_sec_median']:.2f} | "
            f"{r['load_sec_first']:.2f} | {r['toks_per_sec_median']:.1f} | {hall} |"
        )

    lines += ["", "## 3. LLM 응답 샘플", ""]
    seen_models = set()
    for r in llm_results:
        if "error" in r or r["model"] in seen_models:
            continue
        seen_models.add(r["model"])
        lines += [
            f"### {r['model']} ({r['condition']})",
            "",
            "```",
            r["sample_output"].strip(),
            "```",
            "",
        ]

    lines += [
        "## 4. 결론",
        "",
        "- GPU 필요 여부: (숫자 보고 채울 것)",
        "- 모델 선택: (숫자 보고 채울 것)",
        "- 후보 인스턴스 타입: (숫자 보고 채울 것)",
    ]

    Path(__file__).parent.joinpath("results.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    print("== 분석 엔진 벤치 ==")
    engine_results = [bench_engine(n) for n in (100, 500)]
    for r in engine_results:
        print(r)

    print("== VAE AUROC 체크 ==")
    auroc = vae_auroc_check()
    print("AUROC:", auroc)

    print("== LLM 벤치 (모델 3종 다운로드 완료 필요) ==")
    llm_results = bench_llm()
    for r in llm_results:
        print(r.get("model"), r.get("condition"), r.get("toks_per_sec_median", r.get("error")))

    write_report(engine_results, auroc, llm_results)
    print("결과 저장: bench/results.md")
