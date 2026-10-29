import unittest
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).parents[2]
SECURITY_WORKFLOW = REPO_ROOT.parent / ".github/workflows/reusable-security.yml"


class SecurityWorkflowContractTest(unittest.TestCase):
    def test_dependency_review_requires_an_enabled_repository_dependency_graph(self):
        workflow = yaml.safe_load(SECURITY_WORKFLOW.read_text())
        dependency_review = workflow["jobs"]["dependency-review"]

        self.assertEqual(
            dependency_review["if"],
            "github.event_name == 'pull_request' && vars.DEPENDENCY_GRAPH_ENABLED == 'true'",
        )

    def test_filesystem_scan_applies_severity_filter_to_sarif_output(self):
        workflow = yaml.safe_load(SECURITY_WORKFLOW.read_text())
        steps = workflow["jobs"]["filesystem"]["steps"]
        scanner = next(step for step in steps if step.get("uses", "").startswith("aquasecurity/trivy-action@"))

        self.assertEqual(scanner["with"]["severity"], "HIGH,CRITICAL")
        self.assertEqual(scanner["with"]["exit-code"], "1")
        self.assertTrue(scanner["with"]["limit-severities-for-sarif"])


if __name__ == "__main__":
    unittest.main()
