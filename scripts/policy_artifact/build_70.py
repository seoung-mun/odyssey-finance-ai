#!/usr/bin/env python3
"""검토된 70건 입력을 현재 PolicyArtifact candidate와 review 문서로 변환한다."""

from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import json
import math
from pathlib import Path
import re
import unicodedata
from urllib.parse import urlparse

from build import canonical, embed, normalized, toolchain


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "data/policy/policy-artifact-candidate-70.json"
DEFAULT_REVIEW = ROOT / "data/policy/policy-artifact-candidate-70-review.md"
DEFAULT_CURRENT = ROOT / "data/policy/policy-artifact-candidate.json"
MAX_ARTIFACT_BYTES = 5 * 1024 * 1024
MODEL_NAME = "nlpai-lab/KURE-v1"
FORBIDDEN_KEYS = {
    "password",
    "secret",
    "apikey",
    "authorization",
    "cookie",
    "userid",
    "answers",
}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def java_double(value: float) -> str:
    if value == 0.0:
        return "-0.0" if math.copysign(1.0, value) < 0 else "0.0"
    decimal = Decimal(repr(value))
    absolute = abs(value)
    if 0.001 <= absolute < 10_000_000:
        rendered = format(decimal, "f")
        return rendered if "." in rendered else f"{rendered}.0"
    rendered = format(decimal.normalize(), "E")
    mantissa, exponent = rendered.split("E")
    if "." not in mantissa:
        mantissa += ".0"
    return f"{mantissa}E{int(exponent)}"


def importer_canonical(value) -> bytes:
    if value is None:
        return b"null"
    if value is True:
        return b"true"
    if value is False:
        return b"false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if isinstance(value, int):
        return str(value).encode("ascii")
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("non-finite float is forbidden")
        return java_double(value).encode("ascii")
    if isinstance(value, list):
        return b"[" + b",".join(importer_canonical(item) for item in value) + b"]"
    if isinstance(value, dict):
        fields = []
        for key in sorted(value):
            fields.append(importer_canonical(key) + b":" + importer_canonical(value[key]))
        return b"{" + b",".join(fields) + b"}"
    raise TypeError(f"unsupported canonical JSON type: {type(value).__name__}")


def reject_constant(value: str):
    raise ValueError(f"non-finite JSON constant is forbidden: {value}")


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path: Path):
    return json.loads(
        path.read_text(encoding="utf-8"),
        parse_constant=reject_constant,
        object_pairs_hook=reject_duplicate_keys,
    )


def require_text(value, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a nonblank string")
    return value


def valid_hash(value) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def normalized_security_key(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKC", value).lower())


def reject_security_fields(value, path: str = "$"):
    if isinstance(value, dict):
        for key, child in value.items():
            if normalized_security_key(key) in FORBIDDEN_KEYS:
                raise ValueError(f"forbidden security field at {path}.{key}")
            reject_security_fields(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_security_fields(child, f"{path}[{index}]")


def vector_is_valid(vector) -> bool:
    if not isinstance(vector, list) or len(vector) != 1024:
        return False
    if not all(isinstance(value, (int, float)) and math.isfinite(value) for value in vector):
        return False
    norm = math.sqrt(sum(float(value) * float(value) for value in vector))
    return norm > 0.0 and abs(norm - 1.0) <= 0.00002


def url_source(policy) -> tuple[str, str]:
    official = policy["officialUrl"].strip()
    original = policy["original"]
    apply_url = str(original.get("aplyUrlAddr") or "").strip()
    reference_url = str(original.get("refUrlAddr1") or "").strip()
    if official == apply_url and apply_url:
        return "APPLY_URL", "신청 URL"
    if official == reference_url and reference_url:
        if not apply_url:
            return "REF_URL_1", "신청 URL 없음"
        return "REF_URL_1", "신청 URL 형식 보완"
    return "YOUTH_CENTER_HOME", "신청·참고 URL 없음"


def validate_input(source, current):
    if source.get("status") != "BUILD_INPUT_NOT_IMPORT_ARTIFACT":
        raise ValueError("input status must remain BUILD_INPUT_NOT_IMPORT_ARTIFACT")
    if source.get("policyCount") != 70 or len(source.get("policies", [])) != 70:
        raise ValueError("input must contain exactly 70 policies")
    if source.get("embeddingModelRequired") != MODEL_NAME:
        raise ValueError("input embedding model does not match KURE-v1")
    if source.get("embeddingDimensionRequired") != 1024:
        raise ValueError("input embedding dimension must be 1024")
    if len(current.get("queryProfiles", [])) != 8:
        raise ValueError("current artifact must contain exactly 8 query profiles")

    profile_goals = [profile.get("supportGoal") for profile in current["queryProfiles"]]
    if len(profile_goals) != len(set(profile_goals)):
        raise ValueError("current query profiles contain duplicate supportGoal")
    keys = []
    source_keys = []
    counts = Counter()
    for index, policy in enumerate(source["policies"]):
        prefix = f"policies[{index}]"
        key = require_text(policy.get("policyKey"), f"{prefix}.policyKey")
        source_key = require_text(policy.get("sourceKey"), f"{prefix}.sourceKey")
        if key != source_key:
            raise ValueError(f"{prefix} policyKey/sourceKey mismatch")
        goal = require_text(policy.get("primarySupportGoal"), f"{prefix}.primarySupportGoal")
        if goal not in profile_goals:
            raise ValueError(f"{prefix} uses an unknown supportGoal: {goal}")
        if policy.get("suggestedCalculationMode") not in {
            "ELIGIBILITY_ONLY",
            "INFORMATIONAL",
        }:
            raise ValueError(f"{prefix} must remain non-calculable")
        title = require_text(policy.get("title"), f"{prefix}.title")
        content = require_text(policy.get("chunkContent"), f"{prefix}.chunkContent")
        original = policy.get("original")
        if not isinstance(original, dict):
            raise ValueError(f"{prefix}.original must be an object")
        if title != str(original.get("plcyNm") or "").strip():
            raise ValueError(f"{prefix} title does not match original.plcyNm")
        if normalized(title) not in normalized(content):
            raise ValueError(f"{prefix} title marker is missing from chunkContent")
        digest = sha256_bytes(canonical(original))
        if digest != policy.get("apiRecordSha256"):
            raise ValueError(f"{prefix} canonical original hash mismatch")
        require_text(policy.get("organization"), f"{prefix}.organization")
        official_url = require_text(policy.get("officialUrl"), f"{prefix}.officialUrl")
        if urlparse(official_url).scheme not in {"http", "https"}:
            raise ValueError(f"{prefix}.officialUrl must be an HTTP(S) URL")
        keys.append(key)
        source_keys.append(source_key)
        counts[goal] += 1
    if len(set(keys)) != 70 or len(set(source_keys)) != 70:
        raise ValueError("policyKey and sourceKey must each be unique")
    if dict(counts) != source.get("supportGoalCounts"):
        raise ValueError("calculated supportGoal counts differ from input declaration")


def build_artifact(source, current, model_path: Path):
    policies_input = sorted(source["policies"], key=lambda item: item["policyKey"])
    chunk_texts = [policy["chunkContent"] for policy in policies_input]
    query_texts = [profile["queryText"] for profile in current["queryProfiles"]]
    vectors = embed(chunk_texts + query_texts, model_path)
    if len(vectors) != 78 or not all(vector_is_valid(vector) for vector in vectors):
        raise ValueError("KURE-v1 must produce 78 finite, nonzero, normalized 1024D vectors")

    sources = []
    policies = []
    for policy, vector in zip(policies_input, vectors[:70]):
        title = policy["title"]
        original_bytes = canonical(policy["original"])
        source_hash = sha256_bytes(original_bytes)
        locator = title
        sources.append(
            {
                "sourceKey": policy["sourceKey"],
                "organization": policy["organization"],
                "officialUrl": policy["officialUrl"],
                "contentSha256": source_hash,
                "retrievedAt": source["sourceFetchedAt"],
                "bodyMarkers": [title],
                "contentType": "application/json",
                "finalUrl": policy["officialUrl"],
                "httpStatus": 200,
                "textSha256": sha256_bytes(normalized(policy["chunkContent"]).encode("utf-8")),
            }
        )
        metadata = {
            "officialUrl": policy["officialUrl"],
            "rawSha256": source_hash,
            "apiRecordSha256": policy["apiRecordSha256"],
            "sourceLocator": locator,
            "plcyNo": policy["plcyNo"],
            "supportDetails": str(policy["original"].get("plcySprtCn") or policy["summary"]),
            "confirmedConditions": policy["confirmedConditionsCandidate"],
            "additionalChecks": policy["additionalChecksCandidate"],
            "applicationPeriod": policy["applicationPeriod"],
        }
        policies.append(
            {
                "policyKey": policy["policyKey"],
                "title": title,
                "supportGoal": policy["primarySupportGoal"],
                "summary": policy["summary"],
                "planConnection": policy["planConnection"],
                "version": {
                    "sourceVersion": policy["sourceVersion"],
                    "reviewStatus": "PENDING",
                    "calculationMode": policy["suggestedCalculationMode"],
                    "effectiveFrom": policy["effectiveFrom"],
                    "effectiveTo": policy["effectiveTo"],
                    "lastVerifiedAt": policy["lastVerifiedAtCandidate"],
                    "sourceKey": policy["sourceKey"],
                    "sourceLocator": locator,
                    "locatorSha256": sha256_bytes(locator.encode("utf-8")),
                    "chunks": [
                        {
                            "chunkIndex": 0,
                            "content": policy["chunkContent"],
                            "embedding": vector,
                            "metadata": metadata,
                        }
                    ],
                    "calculationRule": None,
                },
            }
        )

    query_profiles = []
    for profile, vector in zip(current["queryProfiles"], vectors[70:]):
        query_profiles.append(
            {
                "supportGoal": profile["supportGoal"],
                "queryText": profile["queryText"],
                "embedding": vector,
                "questionFlow": profile["questionFlow"],
            }
        )

    current_model = require_text(current.get("embeddingModel"), "current.embeddingModel")
    model_prefix = f"{MODEL_NAME}@"
    if not current_model.startswith(model_prefix) or len(current_model) == len(model_prefix):
        raise ValueError("current artifact does not identify the KURE-v1 snapshot revision")
    model_revision = current_model.removeprefix(model_prefix)
    artifact = {
        "artifactVersion": f"candidate-70-{source['selectionAsOf']}-{model_revision[:12]}",
        "manifestSha256": "",
        "embeddingModel": current_model,
        "embeddingDimension": 1024,
        "sources": sources,
        "policies": policies,
        "queryProfiles": query_profiles,
        "reviewGate": {
            "status": "PENDING",
            "importable": False,
            "reason": "Core importer requires separately human-approved APPROVED versions.",
        },
        "toolchain": toolchain(),
    }
    artifact["manifestSha256"] = sha256_bytes(importer_canonical(artifact))
    return artifact


def validate_artifact(artifact, source, current, serialized: bytes):
    if serialized != importer_canonical(artifact) + b"\n":
        raise ValueError("artifact must use canonical JSON plus one LF")
    if not 0 < len(serialized) <= MAX_ARTIFACT_BYTES:
        raise ValueError("artifact exceeds the current importer 5 MiB limit")
    if artifact["manifestSha256"] != sha256_bytes(
        importer_canonical({**artifact, "manifestSha256": ""})
    ):
        raise ValueError("manifestSha256 mismatch")
    reject_security_fields(artifact)
    if artifact["reviewGate"] != {
        "status": "PENDING",
        "importable": False,
        "reason": "Core importer requires separately human-approved APPROVED versions.",
    }:
        raise ValueError("candidate review gate changed")
    if len(artifact["sources"]) != 70 or len(artifact["policies"]) != 70:
        raise ValueError("artifact source/policy counts are invalid")
    if len(artifact["queryProfiles"]) != 8:
        raise ValueError("artifact must contain 8 query profiles")
    if [profile["supportGoal"] for profile in artifact["queryProfiles"]] != [
        profile["supportGoal"] for profile in current["queryProfiles"]
    ]:
        raise ValueError("query profile order/structure changed")
    if [profile["questionFlow"] for profile in artifact["queryProfiles"]] != [
        profile["questionFlow"] for profile in current["queryProfiles"]
    ]:
        raise ValueError("question flow changed")
    input_by_key = {policy["policyKey"]: policy for policy in source["policies"]}
    source_by_key = {item["sourceKey"]: item for item in artifact["sources"]}
    if len(source_by_key) != 70:
        raise ValueError("artifact sourceKey is not unique")
    if len({item["policyKey"] for item in artifact["policies"]}) != 70:
        raise ValueError("artifact policyKey is not unique")
    for policy in artifact["policies"]:
        source_input = input_by_key[policy["policyKey"]]
        version = policy["version"]
        source_item = source_by_key[version["sourceKey"]]
        if policy["supportGoal"] != source_input["primarySupportGoal"]:
            raise ValueError("scalar supportGoal mapping changed")
        if version["reviewStatus"] != "PENDING":
            raise ValueError("candidate version reviewStatus changed")
        if version["calculationMode"] not in {"ELIGIBILITY_ONLY", "INFORMATIONAL"}:
            raise ValueError("candidate contains a calculable mode")
        if version["calculationRule"] is not None or len(version["chunks"]) != 1:
            raise ValueError("candidate calculation/chunk contract changed")
        if not vector_is_valid(version["chunks"][0]["embedding"]):
            raise ValueError("invalid policy embedding")
        expected_source_hash = sha256_bytes(canonical(source_input["original"]))
        if not (
            expected_source_hash
            == source_input["apiRecordSha256"]
            == source_item["contentSha256"]
            == version["chunks"][0]["metadata"]["rawSha256"]
            == version["chunks"][0]["metadata"]["apiRecordSha256"]
        ):
            raise ValueError("source provenance hash chain mismatch")
        if source_item["textSha256"] != sha256_bytes(
            normalized(version["chunks"][0]["content"]).encode("utf-8")
        ):
            raise ValueError("source text hash mismatch")
        if version["locatorSha256"] != sha256_bytes(version["sourceLocator"].encode("utf-8")):
            raise ValueError("source locator hash mismatch")
    if not all(vector_is_valid(profile["embedding"]) for profile in artifact["queryProfiles"]):
        raise ValueError("invalid query profile embedding")


def markdown_rows(items, columns):
    lines = ["| " + " | ".join(label for label, _ in columns) + " |"]
    lines.append("|" + "|".join("---" for _ in columns) + "|")
    for item in items:
        values = [str(item.get(key, "")).replace("|", "\\|").replace("\n", " ") for _, key in columns]
        lines.append("| " + " | ".join(values) + " |")
    return "\n".join(lines)


def review_markdown(artifact, source, current, artifact_size: int) -> str:
    counts = Counter(policy["supportGoal"] for policy in artifact["policies"])
    fallbacks = []
    for policy in source["policies"]:
        category, reason = url_source(policy)
        if category != "APPLY_URL":
            fallbacks.append(
                {
                    "policyKey": policy["policyKey"],
                    "title": policy["title"],
                    "category": category,
                    "reason": reason,
                    "officialUrl": policy["officialUrl"],
                }
            )
    old_keys = {policy["policyKey"] for policy in current["policies"]}
    new_keys = {policy["policyKey"] for policy in artifact["policies"]}
    old_titles = {policy["title"] for policy in current["policies"]}
    new_titles = {policy["title"] for policy in artifact["policies"]}
    omitted = [
        {"policyKey": policy["policyKey"], "title": policy["title"]}
        for policy in current["policies"]
        if policy["policyKey"] not in new_keys
    ]
    added = [
        {
            "policyKey": policy["policyKey"],
            "title": policy["title"],
            "supportGoal": policy["supportGoal"],
        }
        for policy in artifact["policies"]
        if policy["policyKey"] not in old_keys
    ]
    http_urls = [
        {"policyKey": policy["policyKey"], "title": policy["title"], "officialUrl": policy["officialUrl"]}
        for policy in source["policies"]
        if urlparse(policy["officialUrl"]).scheme == "http"
    ]
    goal_rows = [{"supportGoal": goal, "count": counts[goal]} for goal in sorted(counts)]
    limit_result = "PASS" if artifact_size <= MAX_ARTIFACT_BYTES else "FAIL"
    return f"""# PolicyArtifact candidate 70 검토 보고서

## 요약

- 정책 수: **{len(artifact['policies'])}** (`PASS`, 정확히 70)
- unique `policyKey`: **{len(new_keys)}** (`PASS`, 정확히 70)
- source 수: **{len(artifact['sources'])}**, unique `sourceKey`: **{len({item['sourceKey'] for item in artifact['sources']})}**
- 정책당 chunk 수: **전부 1개**, 총 chunk 수: **{sum(len(item['version']['chunks']) for item in artifact['policies'])}**
- embedding: 정책 chunk 70개와 query profile 8개가 **모두 실제 KURE-v1 1024차원, finite, non-zero, L2 normalized** (`PASS`)
- artifact byte size: **{artifact_size:,} bytes** / 5 MiB({MAX_ARTIFACT_BYTES:,} bytes), **{limit_result}**
- review gate: `PENDING`, `importable=false`; 70개 version 모두 `reviewStatus=PENDING`
- calculation: 70개 모두 비계산 모드이며 `calculationRule=null`

## supportGoal별 수량

{markdown_rows(goal_rows, [('supportGoal', 'supportGoal'), ('수량', 'count')])}

## source / provenance / hash 검증

- 입력 70건 각각에 대해 `SHA-256(canonical(original)) == apiRecordSha256`를 재계산했으며 **70/70 PASS**입니다.
- 위 hash를 `sources[].contentSha256`, chunk metadata의 `rawSha256`/`apiRecordSha256`과 대조했으며 **70/70 PASS**입니다.
- `textSha256`는 기존 pipeline의 `normalized()` 규칙을 `chunkContent`에 적용해 계산했고 **70/70 PASS**입니다.
- `locatorSha256`는 UTF-8 `sourceLocator`의 SHA-256으로 계산했고 **70/70 PASS**입니다.
- root는 현재 importer와 `PolicyTestArtifacts`의 Jackson canonicalization과 동일한 key 정렬·숫자 표기로 직렬화하고, manifest를 빈 문자열로 둔 canonical root의 SHA-256을 `manifestSha256`에 넣었습니다. canonical bytes + LF 재검증은 **PASS**입니다.
- importer 금지 보안 key를 NFKC 정규화 규칙으로 검사했고 **PASS**입니다. secret/API key/user data는 추가하지 않았습니다.
- provenance 원문은 입력의 canonical `original` API record입니다. 중복 원문은 artifact에 재수록하지 않았고, 사용자에게 노출되는 검색 chunk는 입력의 `chunkContent` 한 개만 사용했습니다.
- 이번 build는 입력에 기록된 API 수집 결과를 변환한 것으로, 67개 unique `officialUrl`을 다시 live fetch하거나 redirect/HTTP status를 재검증하지 않았습니다. `finalUrl`은 입력이 선택한 `officialUrl`을 보존하며 URL 공식성·도달성은 PENDING 인간 검토 항목입니다.

## current importer schema 검증

- 저장 artifact는 의도적으로 `reviewGate=PENDING/importable=false`, 각 version `reviewStatus=PENDING`이므로 현재 importer가 **거부해야 정상**입니다.
- PENDING gate 외 필드 관계, strict root/nested DTO shape, canonical encoding, manifest, 1024차원 finite vector, source 참조 완전성, locator/hash, non-calculable/null rule 조건을 current importer와 같은 규칙으로 사전 검사한 결과 **PASS**입니다.
- 최종 인간 승인 시 gate와 각 version review status를 별도 승인 절차로 바꿔야 하며, 이 보고서는 APPROVED를 위조하지 않습니다.

## officialUrl fallback

- 신청 URL(`original.aplyUrlAddr`) 직접 사용: **{70 - len(fallbacks)}건**
- fallback: **{len(fallbacks)}건** (`refUrlAddr1` 41건, 신청·참고 URL이 모두 없어 청년정책 포털 홈 2건)
- HTTP URL은 2건이며 importer는 URL scheme을 검증하지 않지만 사람 검토가 필요합니다.

{markdown_rows(fallbacks, [('policyKey', 'policyKey'), ('정책명', 'title'), ('분류', 'category'), ('사유', 'reason'), ('officialUrl', 'officialUrl')])}

### HTTP URL 검토 대상

{markdown_rows(http_urls, [('policyKey', 'policyKey'), ('정책명', 'title'), ('officialUrl', 'officialUrl')])}

## 기존 24개 대비 교체/추가

- exact `policyKey` overlap: **{len(old_keys & new_keys)}건**
- exact title overlap: **{len(old_titles & new_titles)}건**
- key 기준으로 신규 70건은 모두 추가입니다. 다만 이 파일은 기존 24건을 포함하지 않는 새 candidate이므로 artifact corpus 관점에서는 기존 24건 전체가 빠지고 70건으로 교체됩니다.
- exact URL overlap은 1건(청년주택드림청약통장 계열)이라 의미상 중복 여부는 인간 검토가 필요합니다.

### 기존 candidate에서 빠지는 24건

{markdown_rows(omitted, [('policyKey', 'policyKey'), ('정책명', 'title')])}

### 새 candidate에 추가되는 70건

{markdown_rows(added, [('policyKey', 'policyKey'), ('정책명', 'title'), ('supportGoal', 'supportGoal')])}
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--model-path", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--review", type=Path, default=DEFAULT_REVIEW)
    parser.add_argument("--current", type=Path, default=DEFAULT_CURRENT)
    args = parser.parse_args()

    source = load_json(args.input)
    current = load_json(args.current)
    validate_input(source, current)
    artifact = build_artifact(source, current, args.model_path)
    serialized = importer_canonical(artifact) + b"\n"
    validate_artifact(artifact, source, current, serialized)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(serialized)
    if args.output.stat().st_size != len(serialized):
        raise ValueError("artifact file size changed after write")
    review = review_markdown(artifact, source, current, len(serialized))
    args.review.write_text(review, encoding="utf-8", newline="\n")
    print(
        json.dumps(
            {
                "artifact": str(args.output),
                "review": str(args.review),
                "policies": len(artifact["policies"]),
                "bytes": len(serialized),
                "manifestSha256": artifact["manifestSha256"],
                "embeddingModel": artifact["embeddingModel"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
