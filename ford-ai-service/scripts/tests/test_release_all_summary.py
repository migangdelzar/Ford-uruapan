import io
import json
import sys
import unittest
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from release_all import (  # noqa: E402
    ReleaseResult,
    TARGETS,
    refresh_release,
    release_manifest,
    summarize_releases,
)


class FakeGitHubApi:
    def __init__(self, run, release=None, *, result=None, jobs=None):
        self.run = run
        self.release = release
        self.result = result
        self.jobs = jobs or [
            {"name": "Deploy", "status": "completed", "conclusion": "success"}
        ]

    def request(self, method, path, *, query=None, body=None):
        if method == "GET" and path.endswith("/actions/workflows/release.yml/runs"):
            return {"workflow_runs": [self.run]}
        if method == "GET" and path.endswith("/actions/runs/77/jobs"):
            return {"jobs": self.jobs}
        if method == "GET" and path.endswith("/actions/runs/77/artifacts"):
            return {"artifacts": [{"id": 88, "name": "ford-release-result", "expired": False}]}
        raise AssertionError(f"Unexpected API request: {method} {path}")

    def download_artifact(self, repository, artifact_id):
        if artifact_id != 88 or self.release is None:
            raise AssertionError("Unexpected artifact download")
        result = self.result or {
            "repository": self.release.target.repository,
            "version": self.release.version,
            "tag": self.release.version,
            "sourceSha": self.release.source_sha,
            "imageDigest": None,
            "deploymentId": None,
            "deploymentUrl": None,
        }
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as bundle:
            bundle.writestr("release-result.json", json.dumps(result))
        return archive.getvalue()


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

    def test_manifest_records_version_sha_digest_and_run_without_secret_fields(self):
        release = ReleaseResult(
            target=next(target for target in TARGETS if target.key == "service"),
            source_sha="b" * 40,
            request_id="12345-1",
            status="success",
            run_id=77,
            run_url="https://github.com/migangdelzar/Ford-uruapan/actions/runs/77",
            version="v1.2.3",
            image_digest="sha256:" + "c" * 64,
            deployment_id="deploy-123",
            deployment_url="https://example.invalid/deployment",
        )

        manifest = release_manifest([release])

        self.assertEqual(manifest["releases"][0]["repository"], "migangdelzar/Ford-uruapan")
        self.assertEqual(manifest["releases"][0]["tag"], "v1.2.3")
        self.assertEqual(manifest["releases"][0]["sourceSha"], "b" * 40)
        self.assertEqual(manifest["releases"][0]["imageDigest"], "sha256:" + "c" * 64)
        self.assertEqual(manifest["releases"][0]["deploymentId"], "deploy-123")
        self.assertNotIn("token", str(manifest).lower())

    def test_cancelled_target_run_stays_cancelled(self):
        release = self.release("service", "running")
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Ford Service Release [12345-1] {'b' * 40}",
                "status": "completed",
                "conclusion": "cancelled",
                "html_url": "https://github.com/ignored",
            },
            release,
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
            },
            release,
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
            },
            release,
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
            },
            release,
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
            if path.endswith("/actions/runs/77/jobs")
            else {"artifacts": [{"id": 88, "name": "ford-release-result", "expired": False}]}
        )
        api.release = release

        updated = refresh_release(api, release)

        self.assertEqual(updated.status, "skipped")

    def test_coordinator_approval_job_skipped_does_not_hide_successful_deployment(self):
        release = self.release("scraping", "running", run_id=77)
        release = ReleaseResult(**{**release.__dict__, "version": "v1.2.3"})
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Release MCP to OCI [12345-1] v1.2.3 {'b' * 40}",
                "status": "completed",
                "conclusion": "success",
            },
            release,
            result={
                "repository": release.target.repository,
                "version": "v1.2.3",
                "tag": "v1.2.3",
                "sourceSha": "b" * 40,
                "imageDigest": None,
                "deploymentId": "77",
                "deploymentUrl": "https://ford-mcp.emme.business/mcp",
            },
            jobs=[
                {
                    "name": "Approve direct Ford MCP deployment",
                    "status": "completed",
                    "conclusion": "skipped",
                },
                {
                    "name": "Deploy or roll back Ford MCP",
                    "status": "completed",
                    "conclusion": "success",
                },
            ],
        )

        updated = refresh_release(api, release)

        self.assertEqual(updated.status, "success")

    def test_completed_release_imports_digest_and_deployment_metadata_from_target_artifact(self):
        release = self.release("scraping", "running", run_id=77)
        release = ReleaseResult(
            **{**release.__dict__, "version": "v2.4.1"}
        )
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Release MCP to OCI [12345-1] v2.4.1 {'b' * 40}",
                "status": "completed",
                "conclusion": "success",
            },
            release,
            result={
                "repository": release.target.repository,
                "version": "v2.4.1",
                "tag": "v2.4.1",
                "sourceSha": "b" * 40,
                "imageDigest": "sha256:" + "d" * 64,
                "deploymentId": "77",
                "deploymentUrl": "https://ford-mcp.emme.business/mcp",
            },
        )

        updated = refresh_release(api, release)

        self.assertEqual(updated.image_digest, "sha256:" + "d" * 64)
        self.assertEqual(updated.deployment_id, "77")
        self.assertEqual(updated.deployment_url, "https://ford-mcp.emme.business/mcp")

    def test_missing_result_artifact_fails_the_target_and_preserves_run_link(self):
        release = self.release("service", "running", run_id=77)
        api = FakeGitHubApi(
            {
                "id": 77,
                "display_title": f"Ford Service Release [12345-1] {'b' * 40}",
                "status": "completed",
                "conclusion": "success",
            },
            release,
        )
        original_request = api.request

        def without_result_artifact(method, path, *, query=None, body=None):
            if path.endswith("/actions/runs/77/artifacts"):
                return {"artifacts": []}
            return original_request(method, path, query=query, body=body)

        api.request = without_result_artifact

        updated = refresh_release(api, release)

        self.assertEqual(updated.status, "failure")
        self.assertEqual(updated.run_id, 77)
        self.assertEqual(updated.run_url, "https://github.com/migangdelzar/Ford-uruapan/actions/runs/77")


if __name__ == "__main__":
    unittest.main()
