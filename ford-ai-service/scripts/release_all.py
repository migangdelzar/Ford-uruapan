#!/usr/bin/env python3
"""Dispatch selected, CI-verified releases and report each target independently."""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable, Protocol


OWNER = "migangdelzar"
SOURCE_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SEMVER_TAG_PATTERN = re.compile(
    r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$"
)
REQUEST_ID_PATTERN = re.compile(r"^[0-9]+-[0-9]+$")


@dataclass(frozen=True)
class ReleaseTarget:
    key: str
    name: str
    repository: str
    release_workflow: str
    release_run_name: str

    def run_title(self, request_id: str, source_sha: str, version: str | None = None) -> str:
        identity = f"{version} {source_sha}" if version else source_sha
        return f"{self.release_run_name} [{request_id}] {identity}"


TARGETS = (
    ReleaseTarget(
        key="service",
        name="Ford Service",
        repository=f"{OWNER}/Ford-uruapan",
        release_workflow="release.yml",
        release_run_name="Ford Service Release",
    ),
    ReleaseTarget(
        key="scraping",
        name="Ford-scraping",
        repository=f"{OWNER}/Ford-scraping",
        release_workflow="release.yml",
        release_run_name="Release MCP to OCI",
    ),
    ReleaseTarget(
        key="web",
        name="Ford-web",
        repository=f"{OWNER}/Ford-web",
        release_workflow="release.yml",
        release_run_name="Release Ford Web",
    ),
)


class GitHubApiError(RuntimeError):
    """An API failure with a safe message that never includes request headers."""


class GitHubApi(Protocol):
    def request(
        self,
        method: str,
        path: str,
        *,
        query: dict[str, Any] | None = None,
        body: dict[str, Any] | None = None,
    ) -> Any: ...

    def download_artifact(self, repository: str, artifact_id: int) -> bytes: ...


class GitHubRestApi:
    def __init__(self, token: str = "", api_url: str = "https://api.github.com"):
        self._token = token
        self._api_url = api_url.rstrip("/")

    def request(
        self,
        method: str,
        path: str,
        *,
        query: dict[str, Any] | None = None,
        body: dict[str, Any] | None = None,
    ) -> Any:
        if method != "GET" and not self._token:
            raise ValueError("A GitHub App installation token is required for release dispatch")
        query_string = urllib.parse.urlencode(query or {})
        url = f"{self._api_url}{path}"
        if query_string:
            url = f"{url}?{query_string}"
        payload = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        }
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        request = urllib.request.Request(
            url,
            data=payload,
            method=method,
            headers=headers,
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status == 204:
                    return None
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raise GitHubApiError(
                f"GitHub API {method} {path} returned HTTP {error.code}"
            ) from None
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            raise GitHubApiError(f"GitHub API {method} {path} request failed") from None

    def download_artifact(self, repository: str, artifact_id: int) -> bytes:
        if not self._token:
            raise GitHubApiError("A GitHub App installation token is required to read release artifacts")
        path = f"/repos/{repository}/actions/artifacts/{artifact_id}/zip"
        request = urllib.request.Request(
            f"{self._api_url}{path}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self._token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        no_redirect = urllib.request.build_opener(_NoRedirectHandler())
        try:
            with no_redirect.open(request, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as redirect:
            if redirect.code not in {301, 302, 303, 307, 308}:
                raise GitHubApiError(f"GitHub artifact download returned HTTP {redirect.code}") from None
            location = redirect.headers.get("Location")
            if not location:
                raise GitHubApiError("GitHub artifact download did not provide a download location") from None
        except (urllib.error.URLError, TimeoutError):
            raise GitHubApiError("GitHub artifact download request failed") from None
        try:
            with urllib.request.urlopen(location, timeout=60) as response:
                return response.read()
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError):
            raise GitHubApiError("Release artifact archive could not be downloaded") from None


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


@dataclass(frozen=True)
class ReleaseResult:
    target: ReleaseTarget
    source_sha: str | None
    request_id: str
    status: str
    run_id: int | None = None
    run_url: str | None = None
    detail: str | None = None
    version: str | None = None
    image_digest: str | None = None
    deployment_id: str | None = None
    deployment_url: str | None = None


@dataclass(frozen=True)
class PreflightResult:
    target: ReleaseTarget
    source_sha: str | None
    status: str
    detail: str | None = None
    version: str | None = None


def select_targets(*, service: bool, scraping: bool, web: bool) -> tuple[ReleaseTarget, ...]:
    selected = {
        "service": service,
        "scraping": scraping,
        "web": web,
    }
    deployment_order = {"scraping": 0, "service": 1, "web": 2}
    targets = tuple(sorted(
        (target for target in TARGETS if selected[target.key]),
        key=lambda target: deployment_order[target.key],
    ))
    if not targets:
        raise ValueError("Select at least one repository")
    return targets


def select_versions(
    targets: tuple[ReleaseTarget, ...],
    *,
    service: str = "",
    scraping: str = "",
    web: str = "",
) -> dict[str, str]:
    supplied = {"service": service, "scraping": scraping, "web": web}
    selected = {target.key for target in targets}
    for key, version in supplied.items():
        if key in selected and SEMVER_TAG_PATTERN.fullmatch(version) is None:
            raise ValueError("Provide one version per selected repository using vMAJOR.MINOR.PATCH")
        if key not in selected and version:
            raise ValueError("A version was provided for an unselected repository")
    return {key: supplied[key] for key in selected}


def resolve_release_version(api: GitHubApi, target: ReleaseTarget, version: str) -> str:
    """Resolve an annotated SemVer tag to an exact CI-verified main commit."""
    if SEMVER_TAG_PATTERN.fullmatch(version) is None:
        raise ValueError("Release version must use vMAJOR.MINOR.PATCH")

    tag_ref = api.request(
        "GET",
        f"/repos/{target.repository}/git/ref/tags/{version}",
    )
    if not isinstance(tag_ref, dict) or tag_ref.get("ref") != f"refs/tags/{version}":
        raise GitHubApiError("Release tag ref was not found")

    tag_object = tag_ref.get("object")
    if not isinstance(tag_object, dict) or tag_object.get("type") != "tag":
        raise GitHubApiError("Release must be an annotated tag pointing to a commit")
    seen_tag_objects: set[str] = set()
    for _ in range(8):
        if not isinstance(tag_object, dict):
            raise GitHubApiError("Release tag object is malformed")
        object_type = tag_object.get("type")
        object_sha = tag_object.get("sha")
        if not isinstance(object_sha, str) or SOURCE_SHA_PATTERN.fullmatch(object_sha) is None:
            raise GitHubApiError("Release tag object SHA is malformed")
        if object_type == "commit":
            source_sha = object_sha
            break
        if object_type != "tag" or object_sha in seen_tag_objects:
            raise GitHubApiError("Release must be an annotated tag pointing to a commit")
        seen_tag_objects.add(object_sha)
        tag_object = api.request(
            "GET",
            f"/repos/{target.repository}/git/tags/{object_sha}",
        ).get("object")
    else:
        raise GitHubApiError("Release tag nesting exceeded the supported depth")

    comparison = api.request(
        "GET",
        f"/repos/{target.repository}/compare/{source_sha}...main",
    )
    if not isinstance(comparison, dict) or comparison.get("status") not in {"ahead", "identical"}:
        raise GitHubApiError("Release tag target is not reachable from main")

    response = api.request(
        "GET",
        f"/repos/{target.repository}/actions/workflows/ci.yml/runs",
        query={
            "branch": version,
            "event": "push",
            "status": "success",
            "head_sha": source_sha,
            "per_page": 100,
        },
    )
    runs = response.get("workflow_runs", []) if isinstance(response, dict) else []
    if not any(
        isinstance(run, dict)
        and run.get("head_branch") == version
        and run.get("event") == "push"
        and run.get("head_sha") == source_sha
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
        for run in runs
    ):
        raise GitHubApiError("No successful push CI run for the tagged SHA")
    return source_sha


def preflight_selected(
    api: GitHubApi,
    targets: tuple[ReleaseTarget, ...],
    versions: dict[str, str] | None = None,
) -> list[PreflightResult]:
    """Check public release readiness without dispatching workflows or using credentials."""
    if versions is None or set(versions) != {target.key for target in targets} or any(
        SEMVER_TAG_PATTERN.fullmatch(version) is None for version in versions.values()
    ):
        raise ValueError("Provide one version per selected repository using vMAJOR.MINOR.PATCH")
    results: list[PreflightResult] = []
    for target in targets:
        source_sha: str | None = None
        version = versions[target.key]
        try:
            source_sha = resolve_release_version(api, target, version)
            workflow = api.request(
                "GET",
                f"/repos/{target.repository}/actions/workflows/{target.release_workflow}",
            )
            if not isinstance(workflow, dict) or workflow.get("state") != "active":
                raise GitHubApiError("Target release workflow is not active")
            expected_path = f".github/workflows/{target.release_workflow}"
            if workflow.get("path") != expected_path:
                raise GitHubApiError("Target release workflow path did not match")
            results.append(PreflightResult(target, source_sha, "ready", version=version))
        except GitHubApiError as error:
            detail = str(error)
            if "tagged SHA" in detail:
                detail = "No successful CI run exists for the selected tag and exact source SHA"
            elif "release workflow" in detail.lower():
                detail = "Target release workflow is not active or has an unexpected path"
            else:
                detail = "Could not verify target release readiness"
            results.append(PreflightResult(target, source_sha, "failure", detail, version))
    return results


def summarize_preflight(results: list[PreflightResult]) -> str:
    lines = [
        "## Release-all preflight",
        "",
        "| Repository | Version | Verified source SHA | Status | Release workflow |",
        "| --- | --- | --- | --- | --- |",
    ]
    for result in results:
        source_sha = f"`{result.source_sha}`" if result.source_sha else "unavailable"
        workflow_url = f"https://github.com/{result.target.repository}/actions/workflows/{result.target.release_workflow}"
        detail = f" — {result.detail}" if result.detail else ""
        lines.append(
            f"| {result.target.name} | {result.version or 'latest verified main'} | {source_sha} | {result.status}{detail} | [Workflow]({workflow_url}) |"
        )
    lines.extend(
        [
            "",
            "This read-only preflight verified selected tags and CI identities. It did not dispatch releases or enter a production environment.",
        ]
    )
    return "\n".join(lines) + "\n"


def dispatch_versioned_selected(
    api: GitHubApi,
    targets: tuple[ReleaseTarget, ...],
    versions: dict[str, str],
    *,
    request_id: str,
) -> list[ReleaseResult]:
    """Dispatch independently versioned tags after read-only identity checks."""
    if REQUEST_ID_PATTERN.fullmatch(request_id) is None:
        raise ValueError("Release request ID must contain the run ID and attempt")
    selected_keys = {target.key for target in targets}
    if set(versions) != selected_keys or any(
        SEMVER_TAG_PATTERN.fullmatch(version) is None for version in versions.values()
    ):
        raise ValueError("Provide one version per selected repository using vMAJOR.MINOR.PATCH")

    results: list[ReleaseResult] = []
    for target in targets:
        version = versions[target.key]
        try:
            source_sha = resolve_release_version(api, target, version)
        except GitHubApiError:
            results.append(
                ReleaseResult(
                    target=target,
                    source_sha=None,
                    request_id=request_id,
                    status="failure",
                    detail="Could not verify the selected version tag and source",
                    version=version,
                )
            )
            continue

        body = {
            "ref": "main",
            "inputs": {
                "operation": "deploy",
                "version": version,
                "source_sha": source_sha,
                "release_request_id": request_id,
            },
        }
        try:
            api.request(
                "POST",
                f"/repos/{target.repository}/actions/workflows/{target.release_workflow}/dispatches",
                body=body,
            )
            status = "dispatched"
            detail = None
        except GitHubApiError:
            status = "failure"
            detail = "Target workflow dispatch failed"
        results.append(
            ReleaseResult(
                target=target,
                source_sha=source_sha,
                request_id=request_id,
                status=status,
                detail=detail,
                version=version,
            )
        )
    return results


def dispatch_sequentially_selected(
    api: GitHubApi,
    targets: tuple[ReleaseTarget, ...],
    versions: dict[str, str],
    *,
    request_id: str,
    poll_interval_seconds: int = 15,
    target_timeout_seconds: int = 3000,
    sleep: Callable[[float], None] = time.sleep,
    monotonic: Callable[[], float] = time.monotonic,
) -> list[ReleaseResult]:
    """Release selected tags in dependency order and stop after the first failure."""
    if REQUEST_ID_PATTERN.fullmatch(request_id) is None:
        raise ValueError("Release request ID must contain the run ID and attempt")
    selected_keys = {target.key for target in targets}
    if set(versions) != selected_keys or any(
        SEMVER_TAG_PATTERN.fullmatch(version) is None for version in versions.values()
    ):
        raise ValueError("Provide one version per selected repository using vMAJOR.MINOR.PATCH")

    identities: dict[str, str] = {}
    failed_identity: ReleaseTarget | None = None
    for target in targets:
        try:
            identities[target.key] = resolve_release_version(api, target, versions[target.key])
        except GitHubApiError:
            failed_identity = target
            break

    if failed_identity is not None:
        results = [
            ReleaseResult(
                target=target,
                source_sha=identities.get(target.key),
                request_id=request_id,
                status="failure" if target.key == failed_identity.key else "skipped",
                detail=(
                    "Could not verify the selected version tag and source"
                    if target.key == failed_identity.key
                    else "Not dispatched because a selected release identity could not be verified"
                ),
                version=versions[target.key],
            )
            for target in targets
        ]
        return results

    results: list[ReleaseResult] = []
    for index, target in enumerate(targets):
        source_sha = identities[target.key]
        result = ReleaseResult(
            target=target,
            source_sha=source_sha,
            request_id=request_id,
            status="dispatched",
            version=versions[target.key],
        )
        body = {
            "ref": "main",
            "inputs": {
                "operation": "deploy",
                "version": versions[target.key],
                "source_sha": source_sha,
                "release_request_id": request_id,
            },
        }
        try:
            api.request(
                "POST",
                f"/repos/{target.repository}/actions/workflows/{target.release_workflow}/dispatches",
                body=body,
            )
        except GitHubApiError:
            result = replace(result, status="failure", detail="Target workflow dispatch failed")
        else:
            result = wait_for_releases(
                api,
                [result],
                poll_interval_seconds=poll_interval_seconds,
                timeout_seconds=target_timeout_seconds,
                sleep=sleep,
                monotonic=monotonic,
            )[0]
        results.append(result)
        if result.status != "success":
            results.extend(
                ReleaseResult(
                    target=remaining,
                    source_sha=identities[remaining.key],
                    request_id=request_id,
                    status="skipped",
                    detail="Not dispatched because an earlier release did not succeed",
                    version=versions[remaining.key],
                )
                for remaining in targets[index + 1 :]
            )
            break
    return results


def _release_run(api: GitHubApi, release: ReleaseResult) -> dict[str, Any] | None:
    response = api.request(
        "GET",
        f"/repos/{release.target.repository}/actions/workflows/{release.target.release_workflow}/runs",
        query={"branch": "main", "event": "workflow_dispatch", "per_page": 100},
    )
    runs = response.get("workflow_runs", []) if isinstance(response, dict) else []
    matches = [
        run
        for run in runs
        if run.get("display_title")
        == release.target.run_title(
            release.request_id,
            release.source_sha or "",
            release.version,
        )
    ]
    if len(matches) > 1:
        raise GitHubApiError("Multiple target workflow runs matched one release request")
    return matches[0] if matches else None


def _release_artifact(api: GitHubApi, release: ReleaseResult, run_id: int) -> dict[str, Any] | None:
    response = api.request(
        "GET",
        f"/repos/{release.target.repository}/actions/runs/{run_id}/artifacts",
        query={"per_page": 100},
    )
    artifacts = response.get("artifacts", []) if isinstance(response, dict) else []
    artifact = next(
        (
            candidate
            for candidate in artifacts
            if candidate.get("name") == "ford-release-result"
            and candidate.get("expired") is False
            and isinstance(candidate.get("id"), int)
        ),
        None,
    )
    if artifact is None:
        raise GitHubApiError("Target release workflow did not provide its result artifact")
    archive = api.download_artifact(release.target.repository, artifact["id"])
    try:
        with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
            payload = json.loads(bundle.read("release-result.json").decode("utf-8"))
    except (KeyError, OSError, ValueError, zipfile.BadZipFile):
        raise GitHubApiError("Target release result artifact is invalid") from None
    if not isinstance(payload, dict):
        raise GitHubApiError("Target release result artifact is invalid")
    if payload.get("repository") != release.target.repository:
        raise GitHubApiError("Target release result artifact has an unexpected repository")
    if (
        payload.get("version") != release.version
        or payload.get("tag") != release.version
        or payload.get("sourceSha") != release.source_sha
    ):
        raise GitHubApiError("Target release result artifact has unexpected release identity")
    image_digest = payload.get("imageDigest")
    if image_digest is not None and not re.fullmatch(r"sha256:[0-9a-f]{64}", str(image_digest)):
        raise GitHubApiError("Target release result artifact has an invalid image digest")
    deployment_id = payload.get("deploymentId")
    deployment_url = payload.get("deploymentUrl")
    if deployment_id is not None and not isinstance(deployment_id, str):
        raise GitHubApiError("Target release result artifact has an invalid deployment ID")
    if deployment_url is not None and not isinstance(deployment_url, str):
        raise GitHubApiError("Target release result artifact has an invalid deployment URL")
    if deployment_url is not None and urllib.parse.urlparse(deployment_url).scheme != "https":
        raise GitHubApiError("Target release result artifact has an invalid deployment URL")
    return {
        "image_digest": image_digest,
        "deployment_id": deployment_id,
        "deployment_url": deployment_url,
    }


def refresh_release(api: GitHubApi, release: ReleaseResult) -> ReleaseResult:
    if release.status not in {"dispatched", "running", "approval-pending"}:
        return release
    base = release
    try:
        run = _release_run(api, release)
        if run is None:
            return release
        run_id = run.get("id")
        if not isinstance(run_id, int):
            raise GitHubApiError("Target workflow run has no valid ID")
        run_url = f"https://github.com/{release.target.repository}/actions/runs/{run_id}"
        base = replace(release, run_id=run_id, run_url=run_url)

        status = run.get("status")
        conclusion = run.get("conclusion")
        if status in {"waiting", "pending"}:
            return replace(base, status="approval-pending")
        if status == "completed" and conclusion == "cancelled":
            return replace(base, status="cancelled")
        if status == "completed" and conclusion != "success":
            return replace(base, status="failure", detail="Target workflow completed unsuccessfully")

        jobs_response = api.request(
            "GET",
            f"/repos/{release.target.repository}/actions/runs/{run_id}/jobs",
            query={"per_page": 100},
        )
        jobs = jobs_response.get("jobs", []) if isinstance(jobs_response, dict) else []
        waiting_for_review = any(
            job.get("status") == "waiting"
            and "deploy" in str(job.get("name", "")).lower()
            for job in jobs
        )
        if waiting_for_review:
            return replace(base, status="approval-pending")
        if status != "completed":
            return replace(base, status="running")

        artifact = _release_artifact(api, release, run_id)
        base = replace(base, **artifact)

        deploy_was_skipped = any(
            job.get("conclusion") == "skipped"
            and "deploy" in str(job.get("name", "")).lower()
            and "approve" not in str(job.get("name", "")).lower()
            for job in jobs
        )
        if deploy_was_skipped:
            return replace(base, status="skipped", detail="Target deployment job was gated off")
        return replace(base, status="success")
    except GitHubApiError:
        return replace(base, status="failure", detail="Could not verify target workflow result")


def wait_for_releases(
    api: GitHubApi,
    releases: list[ReleaseResult],
    *,
    poll_interval_seconds: int = 15,
    timeout_seconds: int = 3000,
    sleep: Callable[[float], None] = time.sleep,
    monotonic: Callable[[], float] = time.monotonic,
) -> list[ReleaseResult]:
    deadline = monotonic() + timeout_seconds
    current = releases
    while any(
        release.status in {"dispatched", "running", "approval-pending"}
        for release in current
    ):
        current = [refresh_release(api, release) for release in current]
        if not any(
            release.status in {"dispatched", "running", "approval-pending"}
            for release in current
        ):
            break
        if monotonic() >= deadline:
            current = [
                replace(release, status="timed-out", detail="Target workflow did not reach a reportable state")
                if release.status in {"dispatched", "running"}
                else release
                for release in current
            ]
            break
        sleep(poll_interval_seconds)
    return current


def summarize_releases(releases: list[ReleaseResult]) -> str:
    request_id = releases[0].request_id if releases else "unknown"
    lines = [
        f"## Release-all results ({request_id})",
        "",
        "| Repository | Version | Verified source SHA | Status | Target workflow |",
        "| --- | --- | --- | --- | --- |",
    ]
    for release in releases:
        source_sha = release.source_sha or "unavailable"
        target_link = f"[Run]({release.run_url})" if release.run_url else "not dispatched"
        detail = f" — {release.detail}" if release.detail else ""
        lines.append(
            f"| {release.target.name} | {release.version or 'unspecified'} | `{source_sha}` | {release.status}{detail} | {target_link} |"
        )
    lines.extend(
        [
            "",
            "The coordinator approval covers this selected release. Targets run in dependency order and later targets stop after a failure; successful targets are not rolled back automatically.",
        ]
    )
    return "\n".join(lines) + "\n"


def release_manifest(releases: list[ReleaseResult]) -> dict[str, Any]:
    """Return a credential-free JSON-serializable record of selected releases."""
    request_id = releases[0].request_id if releases else "unknown"
    return {
        "schemaVersion": 1,
        "requestId": request_id,
        "releases": [
            {
                "repository": release.target.repository,
                "version": release.version,
                "tag": release.version,
                "sourceSha": release.source_sha,
                "status": release.status,
                "workflowRunId": release.run_id,
                "workflowRunUrl": release.run_url,
                "imageDigest": release.image_digest,
                "deploymentId": release.deployment_id,
                "deploymentUrl": release.deployment_url,
                "detail": release.detail,
            }
            for release in releases
        ],
    }


def write_release_manifest(releases: list[ReleaseResult], path: Path) -> None:
    path.write_text(
        json.dumps(release_manifest(releases), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", action="store_true")
    parser.add_argument("--scraping", action="store_true")
    parser.add_argument("--web", action="store_true")
    parser.add_argument("--service-version", default="")
    parser.add_argument("--scraping-version", default="")
    parser.add_argument("--web-version", default="")
    parser.add_argument("--manifest-path", type=Path)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--preflight-only", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    try:
        targets = select_targets(service=args.service, scraping=args.scraping, web=args.web)
        versions = select_versions(
            targets,
            service=args.service_version,
            scraping=args.scraping_version,
            web=args.web_version,
        )
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    if args.validate_only:
        return 0

    if args.preflight_only:
        results = preflight_selected(GitHubRestApi(os.environ.get("GITHUB_TOKEN", "")), targets, versions)
        summary = summarize_preflight(results)
        summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary_path:
            with Path(summary_path).open("a", encoding="utf-8") as summary_file:
                summary_file.write(summary)
        print(summary)
        return 1 if any(result.status != "ready" for result in results) else 0

    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        print("A GitHub App installation token is required for release dispatch", file=sys.stderr)
        return 1
    request_id = f"{os.environ.get('GITHUB_RUN_ID', '')}-{os.environ.get('GITHUB_RUN_ATTEMPT', '')}"
    try:
        api = GitHubRestApi(token)
        releases = dispatch_sequentially_selected(api, targets, versions, request_id=request_id)
    except (ValueError, GitHubApiError) as error:
        print(str(error), file=sys.stderr)
        return 1

    summary = summarize_releases(releases)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with Path(summary_path).open("a", encoding="utf-8") as summary_file:
            summary_file.write(summary)
    if args.manifest_path:
        write_release_manifest(releases, args.manifest_path)
    print(summary)
    return 1 if any(release.status != "success" for release in releases) else 0


if __name__ == "__main__":
    raise SystemExit(main())
