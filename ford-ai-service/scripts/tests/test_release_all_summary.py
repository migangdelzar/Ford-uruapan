import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from release_all import (  # noqa: E402
    ReleaseResult,
    TARGETS,
    refresh_release,
    summarize_releases,
)


class FakeGitHubApi:
    def __init__(self, run):
        self.run = run

    def request(self, method, path, *, query=None, body=None):
        if method == "GET" and path.endswith("/actions/workflows/release.yml/runs"):
            return {"workflow_runs": [self.run]}
        if method == "GET" and path.endswith("/actions/runs/77/jobs"):
            return {"jobs": [{"name": "Deploy", "status": "completed", "conclusion": "success"}]}
        raise AssertionError(f"Unexpected API request: {method} {path}")


class ReleaseAllSummaryTest(unittest.TestCase):
    def release(self, key, status, *, run_id=None):
        target = next(target for target in TARGETS if target.key == key)
        return ReleaseResult(
            target=target,
            source_sha="b" * 40,
            request_id="12345-1",
            status=status,
            run_id=run_id,
            run_url=(f"https://github.com/{target.repository}/actions/runs/{run_id}" if run_id else None),
        )

    def test_summary_keeps_partial_outcomes_separate_and_links_target_runs(self):
        summary = summarize_releases(
            [
                self.release("service", "success", run_id=77),
                self.release("scraping", "failure", run_id=78),
                self.release("web", "approval-pending", run_id=79),
            ]
        )

        self.assertIn("Ford Service", summary)
        self.assertIn("12345-1", summary)
        self.assertIn("Ford-scraping", summary)
        self.assertIn("Ford-web", summary)
        self.assertIn("success", summary)
        self.assertIn("failure", summary)
        self.assertIn("approval-pending", summary)
        self.assertIn("https://github.com/migangdelzar/Ford-uruapan/actions/runs/77", summary)
        self.assertIn("b" * 40, summary)

    def test_cancelled_target_run_stays_cancelled(self):
        release = self.release("service", "running")
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Ford Service Release [12345-1] {'b' * 40}",
                "status": "completed",
                "conclusion": "cancelled",
                "html_url": "https://github.com/ignored",
            }
        )

        updated = refresh_release(api, release)

        self.assertEqual(updated.status, "cancelled")
        self.assertEqual(updated.run_url, "https://github.com/migangdelzar/Ford-uruapan/actions/runs/77")

    def test_environment_review_is_reported_as_approval_pending(self):
        release = self.release("web", "running")
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Release Ford Web [12345-1] {'b' * 40}",
                "status": "waiting",
                "conclusion": None,
            }
        )

        updated = refresh_release(api, release)

        self.assertEqual(updated.status, "approval-pending")

    def test_does_not_match_run_for_a_different_source_sha(self):
        release = self.release("web", "dispatched")
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Release Ford Web [12345-1] {'c' * 40}",
                "status": "completed",
                "conclusion": "success",
            }
        )

        updated = refresh_release(api, release)

        self.assertEqual(updated.status, "dispatched")
        self.assertIsNone(updated.run_id)

    def test_failed_target_run_stays_failed(self):
        release = self.release("scraping", "running")
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Release MCP to OCI [12345-1] {'b' * 40}",
                "status": "completed",
                "conclusion": "failure",
            }
        )

        updated = refresh_release(api, release)

        self.assertEqual(updated.status, "failure")

    def test_successful_run_with_skipped_deploy_is_not_reported_as_deployed(self):
        release = self.release("service", "running")
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Ford Service Release [12345-1] {'b' * 40}",
                "status": "completed",
                "conclusion": "success",
            }
        )
        api.request = lambda method, path, *, query=None, body=None: (
            {"workflow_runs": [api.run]}
            if path.endswith("/actions/workflows/release.yml/runs")
            else {"jobs": [{"name": "Deploy Ford Service", "status": "completed", "conclusion": "skipped"}]}
        )

        updated = refresh_release(api, release)

        self.assertEqual(updated.status, "skipped")


if __name__ == "__main__":
    unittest.main()
