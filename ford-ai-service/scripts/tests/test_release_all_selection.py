import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SCRIPTS.parents[1]
sys.path.insert(0, str(SCRIPTS))

from release_all import (  # noqa: E402
    GitHubApiError,
    dispatch_selected,
    select_targets,
)


class FakeGitHubApi:
    def __init__(self, failed_dispatch_repo=None):
        self.requests = []
        self.failed_dispatch_repo = failed_dispatch_repo

    def request(self, method, path, *, query=None, body=None):
        self.requests.append((method, path, query, body))
        if method == "GET" and path.endswith("/actions/workflows/ci.yml/runs"):
            return {
                "workflow_runs": [
                    {
                        "head_branch": "main",
                        "event": "push",
                        "head_sha": "a" * 40,
                        "status": "completed",
                        "conclusion": "success",
                    }
                ]
            }
        if method == "POST":
            if self.failed_dispatch_repo and self.failed_dispatch_repo in path:
                raise GitHubApiError("dispatch rejected")
            return None
        raise AssertionError(f"Unexpected API request: {method} {path}")


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

    def test_rejects_empty_repository_selection(self):
        with self.assertRaisesRegex(ValueError, "Select at least one repository"):
            select_targets(service=False, scraping=False, web=False)

    def test_returns_only_selected_repositories_in_stable_order(self):
        targets = select_targets(service=True, scraping=False, web=True)

        self.assertEqual([target.key for target in targets], ["service", "web"])

    def test_dispatches_each_selected_release_with_verified_sha_and_request_id(self):
        api = FakeGitHubApi()
        targets = select_targets(service=True, scraping=False, web=True)

        releases = dispatch_selected(api, targets, request_id="12345-1")

        self.assertEqual([release.source_sha for release in releases], ["a" * 40, "a" * 40])
        dispatches = [request for request in api.requests if request[0] == "POST"]
        ci_reads = [request for request in api.requests if request[0] == "GET"]
        self.assertTrue(all(request[2]["branch"] == "main" for request in ci_reads))
        self.assertTrue(all(request[2]["event"] == "push" for request in ci_reads))
        self.assertTrue(all(request[2]["status"] == "success" for request in ci_reads))
        self.assertEqual(len(dispatches), 2)
        for method, path, _, body in dispatches:
            self.assertEqual(method, "POST")
            self.assertIn("/actions/workflows/release.yml/dispatches", path)
            self.assertEqual(body["ref"], "main")
            self.assertEqual(body["inputs"]["operation"], "deploy")
            self.assertEqual(body["inputs"]["source_sha"], "a" * 40)
            self.assertEqual(body["inputs"]["release_request_id"], "12345-1")

    def test_does_not_dispatch_when_latest_success_is_not_a_main_verification(self):
        api = FakeGitHubApi()

        def wrong_branch(method, path, *, query=None, body=None):
            if method == "GET":
                return {
                    "workflow_runs": [
                        {
                            "head_branch": "feature",
                            "event": "push",
                            "head_sha": "a" * 40,
                            "status": "completed",
                            "conclusion": "success",
                        }
                    ]
                }
            api.requests.append((method, path, query, body))
            return None

        api.request = wrong_branch
        releases = dispatch_selected(
            api, select_targets(service=True, scraping=False, web=False), "12345-1"
        )

        self.assertFalse(any(request[0] == "POST" for request in api.requests))
        self.assertEqual(releases[0].status, "failure")

    def test_manual_ci_run_is_not_selected_as_a_publishable_source(self):
        api = FakeGitHubApi()

        def manual_run(method, path, *, query=None, body=None):
            if method == "GET":
                return {
                    "workflow_runs": [
                        {
                            "head_branch": "main",
                            "event": "workflow_dispatch",
                            "head_sha": "a" * 40,
                            "status": "completed",
                            "conclusion": "success",
                        }
                    ]
                }
            api.requests.append((method, path, query, body))
            return None

        api.request = manual_run
        releases = dispatch_selected(
            api, select_targets(service=True, scraping=False, web=False), "12345-1"
        )

        self.assertFalse(any(request[0] == "POST" for request in api.requests))
        self.assertEqual(releases[0].status, "failure")

    def test_dispatch_failure_is_reported_without_skipping_other_repositories(self):
        api = FakeGitHubApi(failed_dispatch_repo="Ford-scraping")
        targets = select_targets(service=True, scraping=True, web=True)

        releases = dispatch_selected(api, targets, "12345-1")

        self.assertEqual([release.status for release in releases], ["dispatched", "failure", "dispatched"])
        self.assertEqual(
            [request[1].split("/")[3] for request in api.requests if request[0] == "POST"],
            ["Ford-uruapan", "Ford-scraping", "Ford-web"],
        )


if __name__ == "__main__":
    unittest.main()
