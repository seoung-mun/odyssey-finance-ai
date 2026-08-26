import csv
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from engine.bootstrap_from_tabformer import (
    FixtureError,
    generate_fixture,
    load_fixture,
    planning_input_from_fixture,
)
from engine.planning import compute_presets


class TabFormerFixtureTest(unittest.TestCase):
    @staticmethod
    def _rewrite_fixture(path, payload):
        unsigned = {key: value for key, value in payload.items() if key != "contentSha256"}
        canonical = json.dumps(unsigned, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        payload["contentSha256"] = hashlib.sha256(canonical.encode()).hexdigest()
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)
        self.raw = self.root / "raw.csv"
        with self.raw.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream, lineterminator="\n")
            writer.writerow(["User", "Year", "Month", "Amount", "MCC"])
            for month_index in range(1, 26):
                year, month = 2022 + (month_index - 1) // 12, (month_index - 1) % 12 + 1
                writer.writerow(["7", year, month, f"${month_index}.00", "5411"])
                writer.writerow(["7", year, month, "$2.00", "5541"])
            writer.writerow(["7", 2024, 1, "-$1.00", "5411"])
            writer.writerow(["8", 2024, 1, "$999.00", "5411"])

    def test_generation_is_byte_stable_and_keeps_latest_24_payment_months(self):
        first = self.root / "first.json"
        second = self.root / "second.json"

        generate_fixture(self.raw, first, source_user="7", seed=19, krw_per_usd=1_000)
        generate_fixture(self.raw, second, source_user="7", seed=19, krw_per_usd=1_000)

        self.assertEqual(first.read_bytes(), second.read_bytes())
        fixture = load_fixture(first)
        self.assertEqual(len(fixture["months"]), 24)
        self.assertEqual(fixture["months"][0]["month"], "2022-02")
        self.assertEqual(fixture["months"][-1]["categories"]["food"], 25_000)
        self.assertEqual(fixture["months"][-1]["categories"]["transport"], 2_000)
        self.assertEqual(fixture["provenance"]["sourceUser"], "7")
        self.assertEqual(fixture["provenance"]["selectionSeed"], 19)
        self.assertEqual(fixture["provenance"]["mccMapping"]["5411"], "food")
        self.assertNotEqual(fixture["months"][-1]["categories"]["food"], 999_000)

    def test_integrity_detects_changed_source_amount_and_provenance(self):
        output = self.root / "fixture.json"
        generate_fixture(self.raw, output, source_user="7")
        load_fixture(output, source_path=self.raw)

        with self.raw.open("a", encoding="utf-8") as stream:
            stream.write("7,2025,1,$1.00,5411\n")
        with self.assertRaisesRegex(FixtureError, "source sha256"):
            load_fixture(output, source_path=self.raw)

        for mutate in (
            lambda payload: payload["months"][0]["categories"].__setitem__("food", 1),
            lambda payload: payload["provenance"].__setitem__("sourceUser", "attacker"),
        ):
            with self.subTest(mutate=mutate):
                generate_fixture(self.raw, output, source_user="7")
                payload = json.loads(output.read_text())
                mutate(payload)
                output.write_text(json.dumps(payload), encoding="utf-8")
                with self.assertRaisesRegex(FixtureError, "content sha256"):
                    load_fixture(output)

    def test_refund_only_and_missing_months_are_zero_inside_latest_calendar_window(self):
        output = self.root / "fixture.json"
        with self.raw.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream, lineterminator="\n")
            writer.writerow(["User", "Year", "Month", "Amount", "MCC"])
            writer.writerow(["7", 2022, 1, "$1.00", 5411])
            writer.writerow(["7", 2023, 1, "-$1.00", 5411])
            writer.writerow(["7", 2024, 1, "$2.00", 5411])

        fixture = generate_fixture(self.raw, output, source_user="7")

        self.assertEqual(fixture["months"][0]["month"], "2022-02")
        self.assertEqual(fixture["months"][-1]["month"], "2024-01")
        january_2023 = fixture["months"][11]
        self.assertEqual(january_2023["month"], "2023-01")
        self.assertEqual(sum(january_2023["categories"].values()), 0)
        self.assertEqual(sum(fixture["months"][12]["categories"].values()), 0)

    def test_rejects_invalid_or_noncontinuous_fixture_months(self):
        output = self.root / "fixture.json"
        for replacement in ("2023-13", "2023-03"):
            with self.subTest(replacement=replacement):
                generate_fixture(self.raw, output, source_user="7")
                payload = json.loads(output.read_text())
                payload["months"][1]["month"] = replacement
                self._rewrite_fixture(output, payload)
                with self.assertRaisesRegex(FixtureError, "continuous YYYY-MM"):
                    load_fixture(output)

    def test_fixture_drives_the_existing_planning_engine_contract(self):
        output = self.root / "fixture.json"
        generate_fixture(self.raw, output, source_user="7")

        planning_input = planning_input_from_fixture(load_fixture(output))
        result = compute_presets(
            {
                "random_seed": 19,
                "n_paths": 10_000,
                "horizon_months": 3,
                "period_ratios": [1.0, 1.0, 1.0],
                "available_variable_budget": 100_000,
                "historical_monthly_variable_spending": planning_input,
                "current_avg_variable_spending": sum(planning_input[-12:]) // 12,
                "spending_floor": {"mode": "OFF", "custom_monthly_amount": None},
                "remaining_scheduled_expenses": [],
                "preset_levels": [0.70, 0.80, 0.90],
                "policy_snapshot": {"aggressiveWarningPct": 0.10},
            }
        )

        self.assertEqual(result["simulation"]["method"], "IID_BOOTSTRAP")
        self.assertEqual(result["simulation"]["nPaths"], 10_000)
        self.assertEqual([option["nominalLevel"] for option in result["options"]], [0.7, 0.8, 0.9])

    def test_rejects_missing_source_bad_rows_empty_user_and_overflow(self):
        output = self.root / "fixture.json"
        with self.assertRaises(FileNotFoundError):
            generate_fixture(self.root / "missing.csv", output, source_user="7")

        for rows, message in (
            (["User,Year,Month,Amount\n", "7,2024,1,$1.00\n"], "columns"),
            (["User,Year,Month,Amount,MCC\n", "7,2024,1,nope,5411\n"], "amount"),
            (["User,Year,Month,Amount,MCC\n", "7,2024,1,$1.00,nope\n"], "MCC"),
            (["User,Year,Month,Amount,MCC\n", "8,2024,1,$1.00,5411\n"], "24 months"),
            (["User,Year,Month,Amount,MCC\n", f"7,2024,1,${2**63}.00,5411\n"], "int64"),
        ):
            with self.subTest(message=message):
                self.raw.write_text("".join(rows), encoding="utf-8")
                with self.assertRaisesRegex(FixtureError, message):
                    generate_fixture(self.raw, output, source_user="7")


if __name__ == "__main__":
    unittest.main()
