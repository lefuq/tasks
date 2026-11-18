import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class TraceabilityTest(unittest.TestCase):
    def test_registered_checks_have_existing_sources_and_plan_tasks(self):
        registry = json.loads((ROOT / "testing/traceability.json").read_text())
        self.assertTrue(registry)
        for item in registry:
            with self.subTest(test=item["test"], source=item["source"]):
                component = {"B": "backend", "E": "emulator", "F": "frontend"}[
                    item["test"][0]
                ]
                source = ROOT / item["source"]
                self.assertTrue(source.is_file())
                if item["method"]:
                    self.assertIn(item["method"], source.read_text())
                plan = (ROOT / "testing" / component / "TEST_PLAN.md").read_text()
                self.assertIn(item["requirement"], plan)
                self.assertIn(item["task"], plan)
                stage = int(item["task"].split("-P")[1])
                development_plan = (
                    ROOT / "agent-tasks" / f"{component}-plan.md"
                ).read_text()
                self.assertIn(f"\n{stage}. ", development_plan)


if __name__ == "__main__":
    unittest.main()
