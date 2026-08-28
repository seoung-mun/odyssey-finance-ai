"""TabFormer 원본에서 재현 가능한 24개월 계산 fixture를 만든다."""

import argparse
import csv
import hashlib
import json
import math
import re
import statistics
import subprocess
from collections import defaultdict
from datetime import time
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

from engine.categories import CATEGORIES, VARIABLE_CATS

DATA_PATH = Path("data/credit_card/card_transaction.v1.csv")
FIXTURE_PATH = Path("engine/fixtures/tabformer_user_0_24m.json")
SOURCE = "IBM TabFormer card_transaction.v1.csv"
TRANSFORM_VERSION = 2
DEFAULT_SOURCE_USER = "0"
DEFAULT_SEED = 20260826
DEFAULT_KRW_PER_USD = 1_400
INT64_MAX = 2**63 - 1
REQUIRED_COLUMNS = {"User", "Year", "Month", "Amount", "MCC"}
TEMPLATE_REQUIRED_COLUMNS = REQUIRED_COLUMNS | {"Day"}

_MCC_CATEGORY = {
    5411: "food",
    5499: "food",
    5812: "food",
    5814: "food",
    5912: "health",
    5541: "transport",
    4121: "transport",
    4784: "transport",
    5300: "shopping",
    5310: "shopping",
    5311: "shopping",
    5921: "shopping",
    5813: "shopping",
    5942: "shopping",
    7832: "leisure",
    4900: "fixed",
    4814: "fixed",
    7538: "transport",
    4829: "other",
}

DEMO_TESTERS = (
    {"id": "youth", "name": "청년 변동 소비형", "age": "YOUTH", "years": 27,
     "ratio": 1.25, "cv": 0.30, "region": "11680"},
    {"id": "middle", "name": "중년 균형 소비형", "age": "MIDDLE_AGED", "years": 45,
     "ratio": 1.18, "cv": 0.22, "region": "41135"},
    {"id": "senior", "name": "장년 안정 소비형", "age": "SENIOR", "years": 64,
     "ratio": 1.10, "cv": 0.14, "region": "26110"},
)
DEMO_CV_TOLERANCE = 0.04
DEMO_AMOUNT_LIMIT = 10**15


class FixtureError(ValueError):
    """원본 또는 fixture가 계산 입력 계약을 만족하지 않을 때 발생한다."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_bytes(payload: dict[str, Any]) -> bytes:
    return (json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def _content_sha256(payload: dict[str, Any]) -> str:
    unsigned = {key: value for key, value in payload.items() if key != "contentSha256"}
    return hashlib.sha256(_canonical_bytes(unsigned)).hexdigest()


def _month_index(year: int, month: int) -> int:
    return year * 12 + month - 1


def _month_from_index(index: int) -> tuple[int, int]:
    return divmod(index, 12)[0], divmod(index, 12)[1] + 1


def _parse_month(value: Any) -> int:
    try:
        year, month = map(int, value.split("-"))
    except (AttributeError, TypeError, ValueError) as error:
        raise FixtureError("fixture months must be continuous YYYY-MM") from error
    if value != f"{year:04d}-{month:02d}" or not 1 <= month <= 12:
        raise FixtureError("fixture months must be continuous YYYY-MM")
    return _month_index(year, month)


def _parse_krw(amount: str, krw_per_usd: int, row_number: int) -> int:
    try:
        usd = Decimal(amount.strip().replace("$", "").replace(",", ""))
    except (InvalidOperation, AttributeError) as error:
        raise FixtureError(f"invalid amount at row {row_number}") from error
    if not usd.is_finite():
        raise FixtureError(f"invalid amount at row {row_number}")
    krw = usd * krw_per_usd
    if krw != krw.to_integral_value():
        raise FixtureError(f"amount does not scale to whole KRW at row {row_number}")
    value = int(krw)
    if abs(value) > INT64_MAX:
        raise FixtureError(f"amount exceeds int64 at row {row_number}")
    return value


def generate_fixture(
    source_path: str | Path,
    output_path: str | Path,
    *,
    source_user: str = DEFAULT_SOURCE_USER,
    seed: int = DEFAULT_SEED,
    krw_per_usd: int = DEFAULT_KRW_PER_USD,
) -> dict[str, Any]:
    """원본을 스트리밍해 고정 사용자의 최근 24개월 PAYMENT fixture를 쓴다."""

    source_path, output_path = Path(source_path), Path(output_path)
    if krw_per_usd <= 0:
        raise FixtureError("krw_per_usd must be positive")
    monthly: dict[tuple[int, int], dict[str, int]] = defaultdict(
        lambda: {category: 0 for category in CATEGORIES}
    )
    first_month = last_month = None
    with source_path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or not REQUIRED_COLUMNS.issubset(reader.fieldnames):
            raise FixtureError("source columns are missing")
        for row_number, row in enumerate(reader, 2):
            if row["User"] != source_user:
                continue
            try:
                year, month, mcc = int(row["Year"]), int(row["Month"]), int(row["MCC"])
            except (TypeError, ValueError) as error:
                raise FixtureError(f"invalid year/month/MCC at row {row_number}") from error
            if not 1 <= month <= 12:
                raise FixtureError(f"invalid month at row {row_number}")
            observed = _month_index(year, month)
            first_month = observed if first_month is None else min(first_month, observed)
            last_month = observed if last_month is None else max(last_month, observed)
            amount = _parse_krw(row["Amount"], krw_per_usd, row_number)
            if amount <= 0:
                continue
            category = _MCC_CATEGORY.get(mcc, "other")
            current = monthly[(year, month)][category]
            if current > INT64_MAX - amount:
                raise FixtureError(f"monthly amount exceeds int64 at row {row_number}")
            monthly[(year, month)][category] = current + amount

    if first_month is None or last_month is None or last_month - first_month + 1 < 24:
        raise FixtureError("source user must span at least 24 months")
    selected = [_month_from_index(index) for index in range(last_month - 23, last_month + 1)]
    months = [
        {"month": f"{year:04d}-{month:02d}", "categories": monthly[(year, month)]}
        for year, month in selected
    ]
    payload: dict[str, Any] = {
        "schemaVersion": 1,
        "provenance": {
            "source": SOURCE,
            "sourceFile": source_path.name,
            "sourceSha256": _sha256(source_path),
            "sourceUser": source_user,
            "selectionSeed": seed,
            "transformVersion": TRANSFORM_VERSION,
            "krwPerUsd": krw_per_usd,
            "mccMapping": {str(mcc): category for mcc, category in _MCC_CATEGORY.items()},
            "paymentRule": "positive amounts only; refunds excluded",
            "syntheticEvents": [],
        },
        "months": months,
    }
    payload["contentSha256"] = _content_sha256(payload)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(_canonical_bytes(payload))
    return payload


def load_fixture(path: str | Path, *, source_path: str | Path | None = None) -> dict[str, Any]:
    """fixture 구조와 선택적 원본 provenance를 검증해 반환한다."""

    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        provenance, months = payload["provenance"], payload["months"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise FixtureError("malformed fixture") from error
    if payload.get("contentSha256") != _content_sha256(payload):
        raise FixtureError("fixture content sha256 mismatch")
    if payload.get("schemaVersion") != 1 or len(months) != 24:
        raise FixtureError("fixture must contain exactly 24 months")
    required_provenance = {
        "source", "sourceFile", "sourceSha256", "sourceUser", "selectionSeed",
        "transformVersion", "krwPerUsd", "mccMapping", "paymentRule", "syntheticEvents",
    }
    if not required_provenance.issubset(provenance):
        raise FixtureError("malformed fixture provenance")
    previous = None
    for item in months:
        if not isinstance(item, dict):
            raise FixtureError("malformed fixture month")
        current = _parse_month(item.get("month"))
        if previous is not None and current != previous + 1:
            raise FixtureError("fixture months must be continuous YYYY-MM")
        previous = current
        categories = item.get("categories")
        if not isinstance(categories, dict) or set(categories) != set(CATEGORIES):
            raise FixtureError("malformed fixture categories")
        invalid_amount = any(
            type(value) is not int or not 0 <= value <= INT64_MAX
            for value in categories.values()
        )
        if invalid_amount:
            raise FixtureError("fixture amounts must be nonnegative int64")
    if source_path is not None and _sha256(Path(source_path)) != provenance["sourceSha256"]:
        raise FixtureError("source sha256 does not match fixture provenance")
    return payload


def planning_input_from_fixture(fixture: dict[str, Any]) -> list[int]:
    """검증된 fixture에서 payment-adjusted 비음수 월별 유동지출을 반환한다."""

    values = []
    for item in fixture["months"]:
        total = sum(item["categories"][category] for category in VARIABLE_CATS)
        if total > INT64_MAX:
            raise FixtureError("monthly variable spending exceeds int64")
        values.append(total)
    return values


def _sql_text(value: str) -> str:
    """PostgreSQL 문자열 리터럴 내용을 escape한다."""

    return value.replace("'", "''")


def _scenario_finances(monthly_totals: list[int], ratio: float) -> tuple[int, int, int, int]:
    """원본 금액을 바꾸지 않고 목표 비율을 만드는 소득·고정비·목표를 반환한다."""

    average = round(statistics.mean(monthly_totals))
    budget = round(average / ratio)
    income = max(4_000_000, math.ceil(average * 1.8 / 100_000) * 100_000)
    fixed = 1_000_000
    horizon = 18
    planned = 500_000
    goal = (income - fixed) * horizon - budget * horizon - planned
    if goal <= 0:
        raise FixtureError("selected source cannot produce a positive demo goal")
    return income, fixed, goal, horizon


def build_demo_sql(
    candidates: list[dict[str, Any]], *, source_sha256: str, source_commit: str
) -> str:
    """선택 완료된 세 후보를 V6 런타임 테이블 INSERT SQL로 직렬화한다."""

    if len(candidates) != len(DEMO_TESTERS):
        raise FixtureError("exactly three demo candidates are required")
    tester_ids = [candidate["tester"]["id"] for candidate in candidates]
    source_users = [candidate["source_user"] for candidate in candidates]
    if len(set(tester_ids)) != len(tester_ids) or len(set(source_users)) != len(source_users):
        raise FixtureError("demo candidates must have distinct testers and source users")
    if not re.fullmatch(r"[0-9a-f]{64}", source_sha256):
        raise FixtureError("source sha256 must be a lowercase SHA-256 digest")
    if not re.fullmatch(r"[0-9a-f]{7,40}", source_commit):
        raise FixtureError("source commit must be a lowercase git revision")
    lines = [
        "-- generated offline; do not edit",
        f"-- source sha256: {source_sha256}",
        f"-- KRW per USD: {DEFAULT_KRW_PER_USD}",
        f"-- transform version: {TRANSFORM_VERSION}",
        f"-- source commit: {source_commit}",
        "BEGIN;",
    ]
    row_index = 0
    for candidate in candidates:
        tester = candidate["tester"]
        income, fixed, goal, horizon = _scenario_finances(
            candidate["monthly_totals"], tester["ratio"]
        )
        scheduled = json.dumps(
            [
                {"key": "past-trip", "name": "지난 여행", "amount": 300_000,
                 "relativeMonth": -2, "day": 15, "status": "COMPLETED"},
                {"key": "future-event", "name": "예정 행사", "amount": 500_000,
                 "relativeMonth": 3, "day": 20, "status": "PLANNED"},
            ],
            ensure_ascii=False,
            separators=(",", ":"),
        )
        description = f"TabFormer 사용자 {candidate['source_user']}의 24개월 실측 소비 패턴"
        if len(description) > 300:
            raise FixtureError("demo scenario description exceeds 300 characters")
        lines.append(
            "INSERT INTO demo_scenarios (tester_id,scenario_version,display_name,description,"
            "age_group,birth_years_ago,region_code,monthly_income,monthly_fixed_cost,goal_name,"
            "goal_target_amount,goal_months,scheduled_expenses,"
            "target_spending_budget_ratio) VALUES "
            f"('{tester['id']}',1,'{_sql_text(tester['name'])}','{_sql_text(description)}',"
            f"'{tester['age']}',{tester['years']},'{tester['region']}',{income},{fixed},"
            f"'비상금 마련',{goal},{horizon},'{_sql_text(scheduled)}'::jsonb,{tester['ratio']});"
        )
        linked = False
        for transaction in candidate["transactions"]:
            row_index += 1
            scheduled_key = "NULL"
            if not linked and transaction["relative_month"] == -2:
                scheduled_key = "'past-trip'"
                linked = True
            merchant = (
                "NULL"
                if not transaction.get("merchant")
                else f"'{_sql_text(str(transaction['merchant']))}'"
            )
            if transaction.get("merchant") and len(str(transaction["merchant"])) > 200:
                raise FixtureError("merchant name exceeds 200 characters")
            lines.append(
                "INSERT INTO demo_transaction_templates (tester_id,scenario_version,row_index,"
                "relative_month,transaction_day,transaction_time,amount_krw,category,merchant_name,"
                "scheduled_expense_key) VALUES "
                f"('{tester['id']}',1,{row_index},{transaction['relative_month']},"
                f"{transaction['day']},'{_sql_text(str(transaction['time']))}',"
                f"{transaction['amount']},"
                f"'{transaction['category']}',{merchant},{scheduled_key});"
            )
    lines.extend(("COMMIT;", ""))
    return "\n".join(lines)


def _candidate_quality(monthly_totals: list[int], tester: dict[str, Any]) -> dict[str, Any]:
    """실제 계획 엔진으로 데모 옵션 품질과 목표 비율을 검증한다."""

    from engine.planning import compute_presets

    average = round(statistics.mean(monthly_totals))
    budget = round(average / tester["ratio"])
    result = compute_presets(
        {
            "random_seed": DEFAULT_SEED,
            "n_paths": 10_000,
            "horizon_months": 18,
            "period_ratios": [1.0] * 18,
            "available_variable_budget": budget * 18,
            "historical_monthly_variable_spending": monthly_totals,
            "current_avg_variable_spending": average,
            "remaining_scheduled_expenses": [],
            "preset_levels": [0.70, 0.80, 0.90],
            "policy_snapshot": {"aggressiveWarningPct": 0.20},
        }
    )
    options = result["options"]
    reductions = [option["requiredReductionRate"] for option in options]
    valid = (
        all(0.05 <= value <= 0.30 for value in reductions)
        and len(set(reductions)) == 3
        and not all(option["aggressiveWarning"] for option in options)
    )
    return {
        "valid": valid,
        "options": options,
        "actual_ratio": average / budget,
    }


def select_demo_candidates(source_path: str | Path) -> list[dict[str, Any]]:
    """원본에서 25개월 연속 사용자 중 세 CV 목표와 계획 품질에 가까운 후보를 고른다."""

    source_path = Path(source_path)
    users: list[dict[str, Any]] = []
    current_user = None
    finished_users: set[str] = set()
    monthly: dict[int, int] = defaultdict(int)

    def finish_user() -> None:
        if current_user is None or len(monthly) < 25:
            return
        last = max(monthly)
        window = list(range(last - 24, last + 1))
        if any(index not in monthly or monthly[index] <= 0 for index in window):
            return
        totals = [monthly[index] for index in window[:-1]]
        mean = statistics.mean(totals)
        cv = statistics.pstdev(totals) / mean
        if 0.12 <= cv <= 0.35:
            users.append(
                {
                    "source_user": current_user,
                    "first_index": window[0],
                    "last_index": window[-1],
                    "monthly_totals": totals,
                    "cv": cv,
                }
            )

    with source_path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or not TEMPLATE_REQUIRED_COLUMNS.issubset(reader.fieldnames):
            raise FixtureError("source columns are missing")
        for row_number, row in enumerate(reader, 2):
            user = row["User"]
            if not user:
                raise FixtureError(f"empty user at row {row_number}")
            if current_user is not None and user != current_user:
                finish_user()
                finished_users.add(current_user)
                monthly = defaultdict(int)
                if user in finished_users:
                    raise FixtureError("source must contain contiguous user blocks")
            current_user = user
            try:
                year, month, mcc = int(row["Year"]), int(row["Month"]), int(row["MCC"])
            except (TypeError, ValueError) as error:
                raise FixtureError(f"invalid year/month/MCC at row {row_number}") from error
            if not 1 <= month <= 12:
                raise FixtureError(f"invalid month at row {row_number}")
            amount = _parse_krw(row["Amount"], DEFAULT_KRW_PER_USD, row_number)
            category = _MCC_CATEGORY.get(mcc, "other")
            if amount <= 0 or category not in VARIABLE_CATS:
                continue
            key = _month_index(year, month)
            if monthly[key] >= DEMO_AMOUNT_LIMIT - amount:
                raise FixtureError(f"monthly amount exceeds demo limit at row {row_number}")
            monthly[key] += amount
        finish_user()

    selected: list[dict[str, Any]] = []
    used: set[str] = set()
    for tester in DEMO_TESTERS:
        found = False
        ranked = sorted(
            users,
            key=lambda item: (abs(item["cv"] - tester["cv"]), item["source_user"]),
        )
        for candidate in ranked:
            if candidate["source_user"] in used:
                continue
            if abs(candidate["cv"] - tester["cv"]) > DEMO_CV_TOLERANCE:
                break
            quality = _candidate_quality(candidate["monthly_totals"], tester)
            if quality["valid"] and abs(quality["actual_ratio"] - tester["ratio"]) <= 0.01:
                chosen = {**candidate, "tester": tester, "quality": quality, "transactions": []}
                selected.append(chosen)
                used.add(candidate["source_user"])
                found = True
                break
        if not found:
            raise FixtureError(f"no source candidate satisfies {tester['id']} quality gates")

    by_user = {item["source_user"]: item for item in selected}
    with source_path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        for row_number, row in enumerate(reader, 2):
            candidate = by_user.get(row["User"])
            if candidate is None:
                continue
            try:
                year, month, day, mcc = (
                    int(row["Year"]), int(row["Month"]), int(row["Day"]), int(row["MCC"])
                )
            except (TypeError, ValueError) as error:
                raise FixtureError(f"invalid template row at {row_number}") from error
            month_index = _month_index(year, month)
            if not candidate["first_index"] <= month_index <= candidate["last_index"]:
                continue
            if not 1 <= day <= 31:
                raise FixtureError(f"invalid template day at row {row_number}")
            transaction_time = row.get("Time") or "12:00:00"
            try:
                parsed_time = time.fromisoformat(transaction_time)
            except ValueError as error:
                raise FixtureError(f"invalid template time at row {row_number}") from error
            if parsed_time.tzinfo is not None:
                raise FixtureError(f"invalid template time at row {row_number}")
            amount = _parse_krw(row["Amount"], DEFAULT_KRW_PER_USD, row_number)
            category = _MCC_CATEGORY.get(mcc, "other")
            if amount <= 0 or category not in VARIABLE_CATS:
                continue
            if amount >= DEMO_AMOUNT_LIMIT:
                raise FixtureError(f"transaction exceeds demo limit at row {row_number}")
            candidate["transactions"].append(
                {
                    "relative_month": month_index - candidate["last_index"],
                    "day": day,
                    "time": transaction_time,
                    "amount": amount,
                    "category": category,
                    "merchant": row.get("Merchant Name") or None,
                }
            )
    return selected


def write_demo_sql(
    source_path: str | Path, output_path: str | Path, source_commit: str
) -> list[dict]:
    """세 후보를 선택하고 byte-stable V7 SQL 후보를 쓴다."""

    candidates = select_demo_candidates(source_path)
    sql = build_demo_sql(
        candidates, source_sha256=_sha256(Path(source_path)), source_commit=source_commit
    )
    Path(output_path).write_text(sql, encoding="utf-8")
    return candidates


def main() -> None:
    """CLI 인수로 fixture를 생성한다."""

    parser = argparse.ArgumentParser()
    parser.add_argument("--demo-sql", type=Path)
    parser.add_argument("--source-commit")
    parser.add_argument("source", type=Path, nargs="?", default=DATA_PATH)
    parser.add_argument("output", type=Path, nargs="?", default=FIXTURE_PATH)
    parser.add_argument("--source-user", default=DEFAULT_SOURCE_USER)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--krw-per-usd", type=int, default=DEFAULT_KRW_PER_USD)
    args = parser.parse_args()
    if args.demo_sql:
        commit = args.source_commit or subprocess.run(
            ["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True
        ).stdout.strip()
        candidates = write_demo_sql(args.source, args.demo_sql, commit)
        for candidate in candidates:
            print(
                candidate["tester"]["id"], candidate["source_user"],
                f"cv={candidate['cv']:.4f}",
                [
                    (option["requiredReductionRate"], option["historicalFeasibilityRatio"],
                     option["aggressiveWarning"])
                    for option in candidate["quality"]["options"]
                ],
            )
        print(args.demo_sql, _sha256(args.demo_sql))
        return
    fixture = generate_fixture(
        args.source, args.output, source_user=args.source_user, seed=args.seed,
        krw_per_usd=args.krw_per_usd,
    )
    assert len(planning_input_from_fixture(load_fixture(args.output))) == 24
    print(args.output, fixture["provenance"]["sourceSha256"])


if __name__ == "__main__":
    main()
