#!/usr/bin/env python3
"""metadata-only와 KURE cosine의 후보 Recall@3/MRR을 계산한다."""

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def tokens(text):
    return set(text.lower().split())


def cosine(a, b):
    return sum(x * y for x, y in zip(a, b)) / (math.sqrt(sum(x*x for x in a)) * math.sqrt(sum(x*x for x in b)))


def metrics(rankings, golden):
    hits = reciprocal = 0.0
    for row, ranked in zip(golden, rankings):
        expected = set(row["expectedPolicyKeys"])
        top = ranked[:3]
        hits += bool(expected.intersection(top))
        reciprocal += next((1 / (i + 1) for i, key in enumerate(ranked) if key in expected), 0)
    return {"recallAt3": hits / len(golden), "mrr": reciprocal / len(golden)}


def main():
    artifact = json.loads((ROOT / "data/policy/policy-artifact-candidate.json").read_text())
    golden = json.loads((ROOT / "data/policy/golden-queries-candidate.json").read_text())["queries"]
    policies = artifact["policies"]
    profiles = {profile["supportGoal"]: profile for profile in artifact["queryProfiles"]}
    metadata_rankings, core_rankings = [], []
    for query in golden:
        candidates = [p for p in policies if p["supportGoal"] == query["supportGoal"]]
        qtokens = tokens(query["query"])
        metadata_rankings.append([p["policyKey"] for p in sorted(
            candidates,
            key=lambda p: (-len(qtokens & tokens(" ".join((p["title"], p["summary"], p["supportGoal"])))),
                           policies.index(p)))])
        profile = profiles[query["supportGoal"]]["embedding"]
        # Artifact policy order stands in for the importer-assigned versionId tie-break.
        core_rankings.append([p["policyKey"] for p in sorted(
            candidates,
            key=lambda p: (-cosine(profile, p["version"]["chunks"][0]["embedding"]),
                           policies.index(p)))])
    core_metrics = metrics(core_rankings, golden)
    result = {"candidateHumanApproved": False, "queryCount": len(golden),
              "evaluationMode": "CORE_FIXED_SUPPORT_GOAL_PROFILE",
              "metadataOnlyDiagnostic": metrics(metadata_rankings, golden),
              "coreEquivalent": core_metrics,
              "acceptance": {"requiredRecallAt3": 0.9, "requiredMrr": 0.8,
                             "passed": core_metrics["recallAt3"] >= 0.9 and core_metrics["mrr"] >= 0.8,
                             "blocker": "One fixed ranking per supportGoal cannot satisfy query-specific golden expectations."}}
    (ROOT / "data/policy/evaluation-candidate.json").write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
