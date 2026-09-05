#!/usr/bin/env python3
"""Human-approved ALLOW policies만 informational import artifact로 만든다."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from build_70 import importer_canonical, load_json


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "data/policy/odyssey_policy_51_runtime_metadata_handoff_v1.json"
DEFAULT_OUTPUT = ROOT / "data/policy/policy-artifact-informational-approved-23.json"
DEFAULT_APPROVAL = ROOT / "data/policy/policy-artifact-informational-approved-23-approval.json"
APPROVAL_SCOPE = "INFORMATIONAL_EXPOSURE_ONLY"
EXPECTED_COUNTS = {"ALLOW": 23, "EXCLUDE": 11, "RECHECK": 17}


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def write_canonical(path: Path, value) -> bytes:
    serialized = importer_canonical(value) + b"\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(serialized)
    return serialized


def build(source, reviewer: str, approved_at: str, provenance: str):
    decisions = {decision: [] for decision in EXPECTED_COUNTS}
    for policy in source["policies"]:
        decision = policy["version"]["applicationStatus"]["decision"]
        if decision not in decisions:
            raise ValueError(f"unknown application decision: {decision}")
        decisions[decision].append(policy)
    actual_counts = {decision: len(policies) for decision, policies in decisions.items()}
    if actual_counts != EXPECTED_COUNTS:
        raise ValueError(f"unexpected decision counts: {actual_counts}")

    approved_policies = decisions["ALLOW"]
    approved_keys = sorted(policy["policyKey"] for policy in approved_policies)
    approval = {
        "schemaVersion": "odyssey-informational-approval-v1",
        "scope": APPROVAL_SCOPE,
        "reviewer": reviewer,
        "humanApprovedAt": approved_at,
        "provenance": provenance,
        "sourceArtifactVersion": source["artifactVersion"],
        "sourceManifestSha256": source["manifestSha256"],
        "approvedDecision": "ALLOW",
        "approvedPolicyCount": len(approved_keys),
        "approvedPolicyKeys": approved_keys,
        "notApproved": {"EXCLUDE": 11, "RECHECK": 17, "CALCULABLE_ACTIVATION": 0},
    }
    approval_hash = sha256(importer_canonical(approval))

    source_keys = {policy["version"]["sourceKey"] for policy in approved_policies}
    policies = []
    for policy in approved_policies:
        version = dict(policy["version"])
        if version["calculationMode"] != "ELIGIBILITY_ONLY" or version["calculationRule"] is not None:
            raise ValueError(f"ALLOW policy is not informational-only: {policy['policyKey']}")
        version["reviewStatus"] = "APPROVED"
        policies.append({**policy, "version": version})

    artifact = {
        **source,
        "artifactVersion": f"informational-approved-23-{approved_at[:10]}",
        "manifestSha256": "",
        "sources": [item for item in source["sources"] if item["sourceKey"] in source_keys],
        "policies": policies,
        "reviewGate": {
            "status": "APPROVED",
            "importable": True,
            "reason": (
                f"{APPROVAL_SCOPE}; reviewer={reviewer}; humanApprovedAt={approved_at}; "
                f"approvalProvenanceSha256={approval_hash}"
            ),
        },
    }
    artifact["manifestSha256"] = sha256(importer_canonical(artifact))
    validate(artifact, approval, approval_hash)
    return artifact, approval


def validate(artifact, approval, approval_hash: str):
    if len(artifact["policies"]) != 23 or len(artifact["sources"]) != 23:
        raise ValueError("informational artifact must contain exactly 23 policies and sources")
    if approval["approvedPolicyKeys"] != sorted(
        policy["policyKey"] for policy in artifact["policies"]
    ):
        raise ValueError("approval policy keys differ from artifact")
    if approval_hash not in artifact["reviewGate"]["reason"]:
        raise ValueError("artifact does not reference approval provenance")
    for policy in artifact["policies"]:
        version = policy["version"]
        if version["applicationStatus"]["decision"] != "ALLOW":
            raise ValueError("non-ALLOW policy entered informational artifact")
        if version["reviewStatus"] != "APPROVED":
            raise ValueError("informational policy version is not approved")
        if version["calculationMode"] != "ELIGIBILITY_ONLY":
            raise ValueError("calculable policy entered informational artifact")
        if version["calculationRule"] is not None:
            raise ValueError("informational artifact contains a calculation rule")
    expected_manifest = sha256(importer_canonical({**artifact, "manifestSha256": ""}))
    if artifact["manifestSha256"] != expected_manifest:
        raise ValueError("manifestSha256 mismatch")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--approval", type=Path, default=DEFAULT_APPROVAL)
    parser.add_argument("--reviewer", required=True)
    parser.add_argument("--approved-at", required=True)
    parser.add_argument("--provenance", required=True)
    args = parser.parse_args()

    artifact, approval = build(
        load_json(args.input), args.reviewer, args.approved_at, args.provenance
    )
    approval_bytes = write_canonical(args.approval, approval)
    artifact_bytes = write_canonical(args.output, artifact)
    print(
        f"policies={len(artifact['policies'])} sources={len(artifact['sources'])} "
        f"artifactBytes={len(artifact_bytes)} approvalBytes={len(approval_bytes)} "
        f"manifestSha256={artifact['manifestSha256']}"
    )


if __name__ == "__main__":
    main()
