import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path

from runner_support import (
    contract_operations,
    judge_period_active,
    import_command_args,
    match_operation,
    parse_browser_coverage,
    percentile,
    three_run_summary,
)


ROOT = Path(__file__).resolve().parents[2]
KST = timezone(timedelta(hours=9))


class RunnerSupportTest(unittest.TestCase):
    def test_contracts_map_all_operations_and_templated_paths(self):
        public = contract_operations(ROOT / "API/openapi-public.yaml")
        internal = contract_operations(ROOT / "API/openapi-internal.yaml")

        self.assertEqual(len(public), len({operation.operation_id for operation in public}))
        self.assertEqual(len(internal), len({operation.operation_id for operation in internal}))
        self.assertTrue(public)
        self.assertTrue(internal)
        self.assertEqual(
            "getGoal",
            match_operation(public, "GET", "/api/v1/goals/42").operation_id,
        )
        self.assertEqual(
            "simulatePlan",
            match_operation(internal, "POST", "/internal/simulate").operation_id,
        )

    def test_percentile_uses_nearest_rank(self):
        self.assertEqual(100.0, percentile([10, 20, 30, 40, 100], 0.95))
        self.assertEqual(40.0, percentile([10, 20, 30, 40, 100], 0.70))

    def test_judge_period_guard_is_kst_inclusive(self):
        self.assertFalse(judge_period_active(datetime(2026, 9, 7, 10, 59, tzinfo=KST)))
        self.assertTrue(judge_period_active(datetime(2026, 9, 7, 11, 0, tzinfo=KST)))
        self.assertTrue(judge_period_active(datetime(2026, 9, 11, 23, 59, tzinfo=KST)))
        self.assertFalse(judge_period_active(datetime(2026, 9, 12, 0, 0, tzinfo=KST)))

    def test_import_command_never_starts_a_second_web_server(self):
        args = import_command_args("/tmp/fixture.json")

        self.assertIn("--spring.main.web-application-type=none", args)

    def test_browser_coverage_requires_strict_33_and_one_bypass(self):
        output = (
            "PUBLIC_OPERATION_SUCCESS 33: " + ",".join(f"op{i}" for i in range(33))
            + "\nAPPROVED_BYPASS_CALLS 7\n"
        )

        expected = {f"op{i}" for i in range(33)}
        result = parse_browser_coverage(output, expected)

        self.assertEqual(33, result["publicSuccessCount"])
        self.assertEqual(1, result["approvedBypassOperationCount"])
        self.assertEqual(7, result["actualBypassCallCount"])
        with self.assertRaises(ValueError):
            parse_browser_coverage("PUBLIC_OPERATION_SUCCESS 32: x\n", expected)

    def test_three_run_summary_uses_median_and_rejects_any_error(self):
        runs = [
            {"throughput": 10, "p95Ms": 100, "p99Ms": 200, "errors": 0, "timeouts": 0},
            {"throughput": 20, "p95Ms": 300, "p99Ms": 400, "errors": 0, "timeouts": 0},
            {"throughput": 100, "p95Ms": 500, "p99Ms": 600, "errors": 0, "timeouts": 0},
        ]

        summary = three_run_summary(runs)

        self.assertEqual(20.0, summary["throughputMedian"])
        self.assertEqual(300.0, summary["p95MedianMs"])
        self.assertTrue(summary["passed"])
        runs[2]["errors"] = 1
        self.assertFalse(three_run_summary(runs)["passed"])


if __name__ == "__main__":
    unittest.main()
