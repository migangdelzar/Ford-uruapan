import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from release_all import (  # noqa: E402
    GitHubApiError,
    GitHubRestApi,
    preflight_selected,
    select_targets,
)


class FakeGitHubApi:
    def __init__(self, inactive_workflows=()):
        self.requests = []
        self.inactive_workflows = set(inactive_workflows)

    def request(self, method, path, *, query=None, body=None):
        self.requests.append((method, path, query, body))
        if method != "GET":
            raise AssertionError("A release preflight must never dispatch a workflow")
        repository = path.split("/repos/", 1)[1].split("/", 1)[0] + "/" + path.split("/repos/", 1)[1].split("/", 2)[1]
        if "/git/ref/tags/" in path:
            version = path.rsplit("/git/ref/tags/", 1)[1]
            object_sha = "d" * 40
            self.tag_sha = "a" * 40 if repository.endswith("Ford-uruapan") else "b" * 40
            return {"ref": f"refs/tags/{version}", "object": {"type": "tag", "sha": object_sha}}
        if "/git/tags/" in path:
            return {"object": {"type": "commit", "sha": self.tag_sha}}
        if "/compare/" in path:
            return {"status": "ahead"}
        if path.endswith("/actions/workflows/ci.yml/runs"):
            sha = query.get("head_sha", "a" * 40 if repository.endswith("Ford-uruapan") else "b" * 40)
            return {
                "workflow_runs": [
                    {
                        "head_branch": query["branch"],
                        "event": "push",
                        "head_sha": sha,
                        "status": "completed",
                        "conclusion": "success",
                        "html_url": f"https://github.com/{repository}/actions/runs/123",
                    }
                ]
            }
        if "/actions/workflows/release.yml" in path:
            state = "disabled_manually" if path in self.inactive_workflows else "active"
            return {"state": state, "path": ".github/workflows/release.yml"}
        raise AssertionError(f"Unexpected API request: {method} {path}")


class ReleaseAllPreflightTest(unittest.TestCase):
    def test_preflight_requires_a_version_for_every_selected_repository(self):
        api = FakeGitHubApi()
        target = select_targets(service=False, scraping=False, web=True)

        with self.assertRaisesRegex(ValueError, "one version per selected repository"):
            preflight_selected(api, target)

        self.assertEqual(api.requests, [])

    def test_unauthenticated_api_client_cannot_dispatch(self):
        with self.assertRaisesRegex(ValueError, "installation token is required"):
            GitHubRestApi().request(
                "POST",
                "/repos/migangdelzar/Ford-web/actions/workflows/release.yml/dispatches",
                body={"ref": "main"},
            )

    def test_reports_active_release_workflows_and_verified_tagged_shas_without_dispatch(self):
        api = FakeGitHubApi()
        targets = select_targets(service=True, scraping=False, web=True)

        results = preflight_selected(
            api,
            targets,
            {"service": "v1.2.3", "web": "v2.0.1"},
        )

        self.assertEqual([result.source_sha for result in results], ["a" * 40, "b" * 40])
        self.assertTrue(all(result.status == "ready" for result in results))
        self.assertTrue(all(method == "GET" for method, *_ in api.requests))
        self.assertEqual(sum("/actions/workflows/ci.yml/runs" in path for _, path, *_ in api.requests), 2)
        self.assertEqual(sum("/actions/workflows/release.yml" in path for _, path, *_ in api.requests), 2)

    def test_reports_inactive_release_workflow_as_not_ready(self):
        inactive = "/repos/migangdelzar/Ford-web/actions/workflows/release.yml"
        api = FakeGitHubApi(inactive_workflows={inactive})
        target = select_targets(service=False, scraping=False, web=True)

        results = preflight_selected(api, target, {"web": "v2.0.1"})

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0].status, "failure")
        self.assertEqual(results[0].source_sha, "b" * 40)
        self.assertIn("release workflow is not active", results[0].detail)

    def test_reports_missing_successful_tag_ci_without_claiming_ready(self):
        class MissingCiApi(FakeGitHubApi):
            def request(self, method, path, *, query=None, body=None):
                if path.endswith("/actions/workflows/ci.yml/runs"):
                    return {"workflow_runs": []}
                return super().request(method, path, query=query, body=body)

        target = select_targets(service=False, scraping=False, web=True)
        results = preflight_selected(MissingCiApi(), target, {"web": "v2.0.1"})

        self.assertEqual(results[0].status, "failure")
        self.assertIsNone(results[0].source_sha)
        self.assertIn("successful CI run exists for the selected tag", results[0].detail)


if __name__ == "__main__":
    unittest.main()
