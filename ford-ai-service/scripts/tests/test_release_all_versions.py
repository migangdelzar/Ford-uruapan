import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from release_all import (  # noqa: E402
    GitHubApiError,
    dispatch_versioned_selected,
    resolve_release_version,
    select_targets,
    select_versions,
)


SHA = "a" * 40
TAG_OBJECT_SHA = "b" * 40


class FakeGitHubApi:
    def __init__(self, *, compare_status="ahead", ci_sha=SHA, failed_dispatch_repo=None, lightweight_tag=False):
        self.requests = []
        self.compare_status = compare_status
        self.ci_sha = ci_sha
        self.failed_dispatch_repo = failed_dispatch_repo
        self.lightweight_tag = lightweight_tag

    def request(self, method, path, *, query=None, body=None):
        self.requests.append((method, path, query, body))
        if method == "POST" and path.endswith("/actions/workflows/release.yml/dispatches"):
            if self.failed_dispatch_repo and self.failed_dispatch_repo in path:
                raise GitHubApiError("dispatch rejected")
            return None
        if method != "GET":
            raise AssertionError("Version resolution must be read-only")
        if "/git/ref/tags/v" in path:
            version = path.rsplit("/git/ref/tags/", 1)[1]
            obj = {"type": "commit", "sha": SHA} if self.lightweight_tag else {"type": "tag", "sha": TAG_OBJECT_SHA}
            return {"ref": f"refs/tags/{version}", "object": obj}
        if path.endswith(f"/git/tags/{TAG_OBJECT_SHA}"):
            return {"object": {"type": "commit", "sha": SHA}}
        if path.endswith(f"/compare/{SHA}...main"):
            return {"status": self.compare_status}
        if path.endswith("/actions/workflows/ci.yml/runs"):
            return {
                "workflow_runs": [
                    {
                        "head_branch": query["branch"],
                        "event": "push",
                        "head_sha": self.ci_sha,
                        "status": "completed",
                        "conclusion": "success",
                    }
                ]
            }
        raise AssertionError(f"Unexpected API request: {method} {path}")


class ReleaseVersionResolutionTest(unittest.TestCase):
    def test_resolves_annotated_semver_tag_to_verified_main_commit(self):
        api = FakeGitHubApi(compare_status="ahead")
        target = select_targets(service=True, scraping=False, web=False)[0]

        resolved = resolve_release_version(api, target, "v1.2.3")

        self.assertEqual(resolved, SHA)
        self.assertTrue(all(method == "GET" for method, *_ in api.requests))
        self.assertTrue(any(f"/git/tags/{TAG_OBJECT_SHA}" in path for _, path, *_ in api.requests))
        self.assertTrue(any(f"/compare/{SHA}...main" in path for _, path, *_ in api.requests))
        ci_query = next(query for _, path, query, _ in api.requests if path.endswith("/actions/workflows/ci.yml/runs"))
        self.assertEqual(ci_query["branch"], "v1.2.3")

    def test_rejects_malformed_or_noncanonical_semver_before_api_access(self):
        api = FakeGitHubApi(compare_status="ahead")
        target = select_targets(service=False, scraping=False, web=True)[0]

        for version in ("1.2.3", "v01.2.3", "v1.2", "v1.2.3-rc.1"):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "vMAJOR.MINOR.PATCH"):
                resolve_release_version(api, target, version)

        self.assertEqual(api.requests, [])

    def test_rejects_tag_target_not_reachable_from_main(self):
        api = FakeGitHubApi(compare_status="diverged")
        target = select_targets(service=False, scraping=True, web=False)[0]

        with self.assertRaisesRegex(GitHubApiError, "not reachable from main"):
            resolve_release_version(api, target, "v1.2.3")

        self.assertFalse(any(path.endswith("/actions/workflows/ci.yml/runs") for _, path, *_ in api.requests))

    def test_rejects_lightweight_tag(self):
        api = FakeGitHubApi(lightweight_tag=True)
        target = select_targets(service=False, scraping=False, web=True)[0]

        with self.assertRaisesRegex(GitHubApiError, "annotated tag"):
            resolve_release_version(api, target, "v1.2.3")

        self.assertEqual(len(api.requests), 1)

    def test_rejects_tag_without_successful_ci_for_exact_tag_sha(self):
        api = FakeGitHubApi(compare_status="ahead", ci_sha="c" * 40)
        target = select_targets(service=False, scraping=False, web=True)[0]

        with self.assertRaisesRegex(GitHubApiError, "successful push CI run for the tagged SHA"):
            resolve_release_version(api, target, "v1.2.3")

    def test_rejects_successful_main_ci_when_tag_ci_is_not_successful(self):
        api = FakeGitHubApi(compare_status="ahead")
        api.request = lambda method, path, *, query=None, body=None: (
            {"ref": "refs/tags/v1.2.3", "object": {"type": "tag", "sha": TAG_OBJECT_SHA}}
            if "/git/ref/tags/" in path
            else {"object": {"type": "commit", "sha": SHA}}
            if path.endswith(f"/git/tags/{TAG_OBJECT_SHA}")
            else {"status": "ahead"}
            if path.endswith(f"/compare/{SHA}...main")
            else {"workflow_runs": [{"head_branch": "main", "event": "push", "head_sha": SHA, "status": "completed", "conclusion": "success"}]}
        )
        target = select_targets(service=False, scraping=False, web=True)[0]

        with self.assertRaisesRegex(GitHubApiError, "successful push CI run for the tagged SHA"):
            resolve_release_version(api, target, "v1.2.3")

    def test_dispatch_includes_each_selected_version_and_its_verified_sha(self):
        api = FakeGitHubApi(compare_status="ahead")
        targets = select_targets(service=True, scraping=False, web=True)
        versions = {"service": "v1.2.3", "web": "v2.0.1"}

        releases = dispatch_versioned_selected(api, targets, versions, request_id="321-1")

        self.assertEqual([release.version for release in releases], ["v1.2.3", "v2.0.1"])
        self.assertEqual([release.source_sha for release in releases], [SHA, SHA])
        dispatches = [request for request in api.requests if request[0] == "POST"]
        self.assertEqual(len(dispatches), 2)
        self.assertEqual(
            [request[3]["inputs"]["version"] for request in dispatches],
            ["v1.2.3", "v2.0.1"],
        )
        self.assertEqual(
            [request[3]["inputs"]["source_sha"] for request in dispatches],
            [SHA, SHA],
        )

    def test_rejects_missing_or_extra_version_selection_before_dispatch(self):
        api = FakeGitHubApi(compare_status="ahead")
        targets = select_targets(service=True, scraping=False, web=True)

        for versions in ({"service": "v1.2.3"}, {"service": "v1.2.3", "web": "v2.0.1", "scraping": "v3.0.0"}):
            with self.subTest(versions=versions), self.assertRaisesRegex(ValueError, "one version per selected repository"):
                dispatch_versioned_selected(api, targets, versions, request_id="321-1")

        self.assertEqual(api.requests, [])

    def test_requires_one_version_for_each_selected_repository(self):
        targets = select_targets(service=True, scraping=False, web=True)

        with self.assertRaisesRegex(ValueError, "one version per selected repository"):
            select_versions(targets, service="v1.2.3", scraping="", web="")

        with self.assertRaisesRegex(ValueError, "unselected repository"):
            select_versions(targets, service="v1.2.3", scraping="v3.4.5", web="v2.0.1")

        self.assertEqual(
            select_versions(targets, service="v1.2.3", scraping="", web="v2.0.1"),
            {"service": "v1.2.3", "web": "v2.0.1"},
        )

    def test_version_dispatch_retains_partial_results_and_source_identity(self):
        api = FakeGitHubApi(compare_status="ahead", failed_dispatch_repo="Ford-scraping")
        targets = select_targets(service=True, scraping=True, web=False)

        releases = dispatch_versioned_selected(
            api,
            targets,
            {"service": "v1.2.3", "scraping": "v3.4.5"},
            request_id="321-1",
        )

        self.assertEqual([release.status for release in releases], ["failure", "dispatched"])
        self.assertEqual([release.version for release in releases], ["v3.4.5", "v1.2.3"])
        self.assertEqual([release.source_sha for release in releases], [SHA, SHA])


if __name__ == "__main__":
    unittest.main()
