import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SCRIPTS.parents[1]
sys.path.insert(0, str(SCRIPTS))

from release_all import (  # noqa: E402
    select_targets,
)


class ReleaseAllSelectionTest(unittest.TestCase):
    def test_scraping_target_dispatches_scheduler_release_workflow(self):
        target = next(item for item in select_targets(service=False, scraping=True, web=False) if item.key == "scraping")

        self.assertEqual(target.release_workflow, "release.yml")
        self.assertEqual(target.release_run_name, "Release Ford scraper scheduler")

    def test_coordinator_is_main_only_and_scopes_app_access_to_three_repositories(self):
        workflow = (REPOSITORY_ROOT / ".github/workflows/release-all.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("name: Require coordinator dispatch from main", workflow)
        self.assertIn("RELEASE_REF: ${{ github.ref }}", workflow)
        self.assertIn("[[ \"${RELEASE_REF}\" == 'refs/heads/main' ]]", workflow)
        self.assertIn("Ford-uruapan", workflow)
        self.assertIn("Ford-scraping", workflow)
        self.assertIn("Ford-web", workflow)
        self.assertIn("permission-actions: write", workflow)
        self.assertIn("permission-contents: read", workflow)
        self.assertIn("environment: release-all-approval", workflow)
        self.assertIn("timeout-minutes: 300", workflow)
        self.assertIn("permission-actions: read", workflow)

    def test_validation_preflights_before_approval_and_dispatch_token(self):
        workflow = (REPOSITORY_ROOT / ".github/workflows/release-all.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("default: validate", workflow)
        preflight = workflow.index("Run read-only selected release preflight")
        read_token = workflow.index("Create read-only release metadata token")
        approval_environment = workflow.index("environment: release-all-approval")
        app_token = workflow.index("Create narrowly scoped GitHub App token")
        dispatch = workflow.index("Dispatch selected releases sequentially and stop on failure")
        self.assertLess(read_token, preflight)
        self.assertLess(preflight, approval_environment)
        self.assertLess(approval_environment, app_token)
        self.assertLess(app_token, dispatch)
        self.assertIn("if: inputs.operation == 'validate' || inputs.operation == 'release'", workflow)
        self.assertIn("if: inputs.operation == 'release'", workflow)
        self.assertIn("--preflight-only", workflow)

    def test_coordinator_accepts_independent_versions_and_uploads_partial_manifest(self):
        workflow = (REPOSITORY_ROOT / ".github/workflows/release-all.yml").read_text(
            encoding="utf-8"
        )

        for name in ("service_version", "scraping_version", "web_version"):
            self.assertIn(f"{name}:\n        description:", workflow)
        self.assertIn("--service-version", workflow)
        self.assertIn("--scraping-version", workflow)
        self.assertIn("--web-version", workflow)
        self.assertIn("--manifest-path", workflow)
        self.assertIn("actions/upload-artifact@v7", workflow)
        self.assertIn("if: always() && inputs.operation == 'release'", workflow)

    def test_rejects_empty_repository_selection(self):
        with self.assertRaisesRegex(ValueError, "Select at least one repository"):
            select_targets(service=False, scraping=False, web=False)

    def test_returns_only_selected_repositories_in_stable_order(self):
        targets = select_targets(service=True, scraping=False, web=True)

        self.assertEqual([target.key for target in targets], ["service", "web"])

if __name__ == "__main__":
    unittest.main()
