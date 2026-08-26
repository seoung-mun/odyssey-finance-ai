"""TabFormer 원본에서 재현 가능한 24개월 계산 fixture를 만든다."""

import argparse
import csv
import hashlib
import json
from collections import defaultdict
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

from engine.categories import CATEGORIES, VARIABLE_CATS

DATA_PATH = Path("data/credit_card/card_transaction.v1.csv")
FIXTURE_PATH = Path("engine/fixtures/tabformer_user_0_24m.json")
SOURCE = "IBM TabFormer card_transaction.v1.csv"
TRANSFORM_VERSION = 1
DEFAULT_SOURCE_USER = "0"
DEFAULT_SEED = 20260826
DEFAULT_KRW_PER_USD = 1_000
INT64_MAX = 2**63 - 1
REQUIRED_COLUMNS = {"User", "Year", "Month", "Amount", "MCC"}

_MCC_CATEGORY = {
    5411: "food",
    5499: "food",
    5812: "food",
    5814: "food",
    5912: "food",
    5541: "transport",
    4121: "transport",
    4784: "transport",
    5300: "shopping",
    5310: "shopping",
    5311: "shopping",
    5921: "shopping",
    5813: "shopping",
    5942: "shopping",
    7832: "shopping",
    4900: "fixed",
    4814: "fixed",
    7538: "other",
    4829: "other",
}


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


def main() -> None:
    """CLI 인수로 fixture를 생성한다."""

    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, nargs="?", default=DATA_PATH)
    parser.add_argument("output", type=Path, nargs="?", default=FIXTURE_PATH)
    parser.add_argument("--source-user", default=DEFAULT_SOURCE_USER)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--krw-per-usd", type=int, default=DEFAULT_KRW_PER_USD)
    args = parser.parse_args()
    fixture = generate_fixture(
        args.source, args.output, source_user=args.source_user, seed=args.seed,
        krw_per_usd=args.krw_per_usd,
    )
    assert len(planning_input_from_fixture(load_fixture(args.output))) == 24
    print(args.output, fixture["provenance"]["sourceSha256"])


if __name__ == "__main__":
    main()
