#!/usr/bin/env python3
"""후보 artifact의 안전·재현성 계약을 stdlib만으로 검사한다."""

import hashlib
import json
import math
from pathlib import Path
import re
from urllib.parse import urlparse
from build import CANDIDATES, normalized, visible_text

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "data/policy/policy-artifact-candidate.json"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def main():
    artifact = json.loads(PATH.read_text())
    assert artifact["reviewGate"] == {
        "importable": False,
        "reason": "Core importer requires separately human-approved APPROVED versions.",
        "status": "PENDING",
    }
    assert 20 <= len(artifact["policies"]) <= 30
    assert len(artifact["sources"]) == len(artifact["policies"])
    assert len(artifact["queryProfiles"]) == 8
    assert set(artifact["toolchain"]) == {
        "python", "sentenceTransformers", "torch", "numpy", "transformers", "tokenizers",
        "safetensors", "scipy", "backend", "machine", "system", "byteorder", "blas",
        "torchBuildSha256"
    }
    assert all(isinstance(value, str) and value for value in artifact["toolchain"].values())
    goals = {profile["supportGoal"] for profile in artifact["queryProfiles"]}
    assert goals == {policy["supportGoal"] for policy in artifact["policies"]}
    source_keys = [source["sourceKey"] for source in artifact["sources"]]
    policy_keys = [policy["policyKey"] for policy in artifact["policies"]]
    profile_goals = [profile["supportGoal"] for profile in artifact["queryProfiles"]]
    assert len(source_keys) == len(set(source_keys))
    assert len(policy_keys) == len(set(policy_keys))
    assert len(profile_goals) == len(set(profile_goals)) == 8
    expected = hashlib.sha256(canonical({**artifact, "manifestSha256": ""})).hexdigest()
    assert artifact["manifestSha256"] == expected
    for source in artifact["sources"]:
        assert set(source) == {"sourceKey", "organization", "officialUrl", "contentSha256",
                               "retrievedAt", "finalUrl", "httpStatus", "contentType",
                               "bodyMarkers", "textSha256"}
        assert urlparse(source["officialUrl"]).scheme == "https"
        assert source["httpStatus"] == 200 and source["contentType"] in {"text/html", "application/xhtml+xml"}
        assert urlparse(source["officialUrl"]).hostname == urlparse(source["finalUrl"]).hostname
        raw = ROOT / "data/policy" / next(
            p["version"]["chunks"][0]["metadata"]["rawPath"]
            for p in artifact["policies"] if p["policyKey"] == source["sourceKey"])
        assert hashlib.sha256(raw.read_bytes()).hexdigest() == source["contentSha256"]
        text = visible_text(raw.read_bytes())
        assert hashlib.sha256(normalized(text).encode()).hexdigest() == source["textSha256"]
        assert len(text) >= 300 and all(normalized(marker) in normalized(text)
                                        for marker in source["bodyMarkers"])
    for policy in artifact["policies"]:
        version = policy["version"]
        assert version["sourceKey"] in set(source_keys)
        assert version["reviewStatus"] == "PENDING"
        assert version["calculationMode"] in {"INFORMATIONAL", "ELIGIBILITY_ONLY"}
        assert version["calculationRule"] is None
        assert version["chunks"] and version["sourceLocator"]
        source = next(item for item in artifact["sources"] if item["sourceKey"] == version["sourceKey"])
        raw_path = ROOT / "data/policy" / version["chunks"][0]["metadata"]["rawPath"]
        assert normalized(version["sourceLocator"]) in normalized(visible_text(raw_path.read_bytes()))
        assert version["sourceLocator"] in source["bodyMarkers"]
        for chunk in version["chunks"]:
            assert len(chunk["embedding"]) == 1024
            assert all(isinstance(x, (int, float)) and math.isfinite(x) for x in chunk["embedding"])
    for profile in artifact["queryProfiles"]:
        assert len(profile["embedding"]) == 1024
        assert all(math.isfinite(x) for x in profile["embedding"])
        assert len(profile["questionFlow"]) <= 3
    serialized = canonical(artifact) + b"\n"
    assert PATH.read_bytes() == serialized
    assert not any(token in serialized.lower() for token in (b"api_key", b"password", b"secret"))
    assert not re.search(rb"\b(?:\d{1,3}\.){3}\d{1,3}\b", serialized)
    golden = json.loads((ROOT / "data/policy/golden-queries-candidate.json").read_text())
    assert golden["humanApproved"] is False and len(golden["queries"]) == 30
    assert {query["supportGoal"] for query in golden["queries"]} == goals
    researched_key_set = {candidate[0] for candidate in CANDIDATES}
    assert all(query["expectedPolicyKeys"] and set(query["expectedPolicyKeys"]) <= researched_key_set
               for query in golden["queries"])
    print(f"OK policies={len(artifact['policies'])} manifest={expected}")


if __name__ == "__main__":
    main()
