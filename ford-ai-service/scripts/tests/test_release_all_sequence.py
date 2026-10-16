import io
import json
import sys
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from release_all import (  # noqa: E402
    TARGETS,
    dispatch_sequentially_selected,
    select_targets,
)


class SequentialReleaseApi:
    def __init__(self, failed_target=None):
        self.failed_target = failed_target
        self.dispatched = []
        self.runs = {}
        self.inputs = {}

    def request(self, method, path, *, query=None, body=None):
        if method == "POST" and path.endswith("/dispatches"):
            target = next(item for item in TARGETS if item.repository in path)
            self.dispatched.append(target.key)
            inputs = body["inputs"]
            self.inputs[target.key] = inputs
            run_id = len(self.dispatched) + 100
            conclusion = "failure" if target.key == self.failed_target else "success"
            self.runs[target.key] = {
                "id": run_id,
                "display_title": target.run_title(
                    inputs["release_request_id"], inputs["source_sha"], inputs["version"]
                ),
                "status": "completed",
                "conclusion": conclusion,
            }
            return None
        target = next(item for item in TARGETS if item.repository in path)
        if path.endswith("/actions/workflows/release.yml/runs"):
            run = self.runs.get(target.key)
            return {"workflow_runs": [run] if run else []}
        if path.endswith("/jobs"):
            return {"jobs": [{"name": "Deploy", "status": "completed", "conclusion": "success"}]}
        if path.endswith("/artifacts"):
            return {"artifacts": [{"id": 1, "name": "ford-release-result", "expired": False}]}
        raise AssertionError(f"Unexpected API request: {method} {path}")

    def download_artifact(self, repository, artifact_id):
        target = next(item for item in TARGETS if item.repository == repository)
        inputs = self.inputs[target.key]
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as bundle:
            bundle.writestr(
                "release-result.json",
                json.dumps({
                    "repository": repository,
                    "version": inputs["version"],
                    "tag": inputs["version"],
                    "sourceSha": inputs["source_sha"],
                    "imageDigest": None,
                    "deploymentId": "deployment-1",
                    "deploymentUrl": "https://example.invalid/deployment",
                }),
            )
        return archive.getvalue()


class ReleaseAllSequenceTest(unittest.TestCase):
    def setUp(self):
        self.targets = select_targets(service=True, scraping=True, web=True)
        self.versions = {target.key: "v1.0.0" for target in self.targets}
        self.request_id = "12345-1"

    def test_dispatches_mcp_service_web_one_at_a_time(self):
        api = SequentialReleaseApi()
        shas = {target.key: chr(ord("a") + index) * 40 for index, target in enumerate(self.targets)}

        with patch("release_all.resolve_release_version", side_effect=lambda _api, target, _version: shas[target.key]):
            releases = dispatch_sequentially_selected(
                api, self.targets, self.versions, request_id=self.request_id, poll_interval_seconds=0
            )

        self.assertEqual(api.dispatched, ["scraping", "service", "web"])
        self.assertEqual([release.status for release in releases], ["success", "success", "success"])

    def test_dispatches_only_selected_targets_in_dependency_order(self):
        targets = select_targets(service=False, scraping=True, web=True)
        versions = {target.key: "v1.0.0" for target in targets}
        api = SequentialReleaseApi()
        shas = {target.key: chr(ord("a") + index) * 40 for index, target in enumerate(targets)}

        with patch("release_all.resolve_release_version", side_effect=lambda _api, target, _version: shas[target.key]):
            releases = dispatch_sequentially_selected(
                api, targets, versions, request_id=self.request_id, poll_interval_seconds=0
            )

        self.assertEqual(api.dispatched, ["scraping", "web"])
        self.assertEqual([release.target.key for release in releases], ["scraping", "web"])

    def test_failure_stops_later_targets_and_marks_them_skipped(self):
        api = SequentialReleaseApi(failed_target="scraping")
        shas = {target.key: chr(ord("a") + index) * 40 for index, target in enumerate(self.targets)}

        with patch("release_all.resolve_release_version", side_effect=lambda _api, target, _version: shas[target.key]):
            releases = dispatch_sequentially_selected(
                api, self.targets, self.versions, request_id=self.request_id, poll_interval_seconds=0
            )

        self.assertEqual(api.dispatched, ["scraping"])
        self.assertEqual([release.status for release in releases], ["failure", "skipped", "skipped"])


if __name__ == "__main__":
    unittest.main()
