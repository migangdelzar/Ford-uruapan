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
    def test_coordinator_is_main_only_and_scopes_app_access_to_three_repositories(self):
        workflow = (REPOSITORY_ROOT / ".github/workflows/release-all.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("if: github.ref == 'refs/heads/main'", workflow)
        self.assertIn("Ford-uruapan", workflow)
        self.assertIn("Ford-scraping", workflow)
        self.assertIn("Ford-web", workflow)
        self.assertIn("permission-actions: write", workflow)
        self.assertIn("permission-contents: read", workflow)
        self.assertNotIn("environment: production", workflow)

    def test_validation_mode_defaults_to_read_only_preflight_before_app_token(self):
        workflow = (REPOSITORY_ROOT / ".github/workflows/release-all.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("default: validate", workflow)
        preflight = workflow.index("Run credential-free release preflight")
        app_token = workflow.index("Create narrowly scoped GitHub App token")
        dispatch = workflow.index("Dispatch exact verified versions and report each result")
        self.assertLess(preflight, app_token)
        self.assertLess(app_token, dispatch)
        self.assertIn("if: inputs.operation == 'validate'", workflow)
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
