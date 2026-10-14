#!/usr/bin/env python3
"""Dispatch selected, CI-verified releases and report each target independently."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable, Protocol


OWNER = "migangdelzar"
SOURCE_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
REQUEST_ID_PATTERN = re.compile(r"^[0-9]+-[0-9]+$")


@dataclass(frozen=True)
class ReleaseTarget:
    key: str
    name: str
    repository: str
    release_workflow: str
    release_run_name: str

    def run_title(self, request_id: str, source_sha: str) -> str:
        return f"{self.release_run_name} [{request_id}] {source_sha}"


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


@dataclass(frozen=True)
class ReleaseResult:
    target: ReleaseTarget
    source_sha: str | None
    request_id: str
    status: str
    run_id: int | None = None
    run_url: str | None = None
    detail: str | None = None


@dataclass(frozen=True)
class PreflightResult:
    target: ReleaseTarget
    source_sha: str | None
    status: str
    detail: str | None = None


def select_targets(*, service: bool, scraping: bool, web: bool) -> tuple[ReleaseTarget, ...]:
    selected = {
        "service": service,
        "scraping": scraping,
        "web": web,
    }
    targets = tuple(target for target in TARGETS if selected[target.key])
    if not targets:
        raise ValueError("Select at least one repository")
    return targets


def resolve_verified_sha(api: GitHubApi, target: ReleaseTarget) -> str:
    response = api.request(
        "GET",
        f"/repos/{target.repository}/actions/workflows/ci.yml/runs",
        query={"branch": "main", "event": "push", "status": "success", "per_page": 1},
    )
    runs = response.get("workflow_runs", []) if isinstance(response, dict) else []
    if not runs:
        raise GitHubApiError("No successful main CI run was found")
    run = runs[0]
    source_sha = run.get("head_sha")
    if (
        run.get("head_branch") != "main"
        or run.get("event") != "push"
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or not isinstance(source_sha, str)
        or SOURCE_SHA_PATTERN.fullmatch(source_sha) is None
    ):
        raise GitHubApiError("Latest successful CI response was not a verified main commit")
    return source_sha


def preflight_selected(
    api: GitHubApi,
    targets: tuple[ReleaseTarget, ...],
) -> list[PreflightResult]:
    """Check public release readiness without dispatching workflows or using credentials."""
    results: list[PreflightResult] = []
    for target in targets:
        source_sha: str | None = None
        try:
            source_sha = resolve_verified_sha(api, target)
            workflow = api.request(
                "GET",
                f"/repos/{target.repository}/actions/workflows/{target.release_workflow}",
            )
            if not isinstance(workflow, dict) or workflow.get("state") != "active":
                raise GitHubApiError("Target release workflow is not active")
            expected_path = f".github/workflows/{target.release_workflow}"
            if workflow.get("path") != expected_path:
                raise GitHubApiError("Target release workflow path did not match")
            results.append(PreflightResult(target, source_sha, "ready"))
        except GitHubApiError as error:
            detail = str(error)
            if "No successful main CI" in detail:
                detail = "No successful main CI source is available"
            elif "release workflow" in detail.lower():
                detail = "Target release workflow is not active or has an unexpected path"
            else:
                detail = "Could not verify target release readiness"
            results.append(PreflightResult(target, source_sha, "failure", detail))
    return results


def summarize_preflight(results: list[PreflightResult]) -> str:
    lines = [
        "## Release-all preflight",
        "",
        "| Repository | Verified main CI SHA | Status | Release workflow |",
        "| --- | --- | --- | --- |",
    ]
    for result in results:
        source_sha = f"`{result.source_sha}`" if result.source_sha else "unavailable"
        workflow_url = f"https://github.com/{result.target.repository}/actions/workflows/{result.target.release_workflow}"
        detail = f" — {result.detail}" if result.detail else ""
        lines.append(
            f"| {result.target.name} | {source_sha} | {result.status}{detail} | [Workflow]({workflow_url}) |"
        )
    lines.extend(
        [
            "",
            "This read-only preflight used public workflow metadata. It did not create a GitHub App token, dispatch releases, or enter a production environment.",
        ]
    )
    return "\n".join(lines) + "\n"


def dispatch_selected(
    api: GitHubApi,
    targets: tuple[ReleaseTarget, ...],
    request_id: str,
) -> list[ReleaseResult]:
    if REQUEST_ID_PATTERN.fullmatch(request_id) is None:
        raise ValueError("Release request ID must contain the run ID and attempt")

    verified: list[tuple[ReleaseTarget, str]] = []
    results: list[ReleaseResult] = []
    for target in targets:
        try:
            verified.append((target, resolve_verified_sha(api, target)))
        except GitHubApiError:
            results.append(
                ReleaseResult(
                    target=target,
                    source_sha=None,
                    request_id=request_id,
                    status="failure",
                    detail="Could not resolve a successful main CI source",
                )
            )

    for target, source_sha in verified:
        body = {
            "ref": "main",
            "inputs": {
                "operation": "deploy",
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
            results.append(
                ReleaseResult(
                    target=target,
                    source_sha=source_sha,
                    request_id=request_id,
                    status="dispatched",
                )
            )
        except GitHubApiError:
            results.append(
                ReleaseResult(
                    target=target,
                    source_sha=source_sha,
                    request_id=request_id,
                    status="failure",
                    detail="Target workflow dispatch failed",
                )
            )
    return [next(result for result in results if result.target == target) for target in targets]


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
        == release.target.run_title(release.request_id, release.source_sha or "")
    ]
    if len(matches) > 1:
        raise GitHubApiError("Multiple target workflow runs matched one release request")
    return matches[0] if matches else None


def refresh_release(api: GitHubApi, release: ReleaseResult) -> ReleaseResult:
    if release.status not in {"dispatched", "running", "approval-pending"}:
        return release
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

        deploy_was_skipped = any(
            job.get("conclusion") == "skipped"
            and "deploy" in str(job.get("name", "")).lower()
            for job in jobs
        )
        if deploy_was_skipped:
            return replace(base, status="skipped", detail="Target deployment job was gated off")
        return replace(base, status="success")
    except GitHubApiError:
        return replace(release, status="failure", detail="Could not verify target workflow result")


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
        "| Repository | Verified source SHA | Status | Target workflow |",
        "| --- | --- | --- | --- |",
    ]
    for release in releases:
        source_sha = release.source_sha or "unavailable"
        target_link = f"[Run]({release.run_url})" if release.run_url else "not dispatched"
        detail = f" — {release.detail}" if release.detail else ""
        lines.append(
            f"| {release.target.name} | `{source_sha}` | {release.status}{detail} | {target_link} |"
        )
    lines.extend(
        [
            "",
            "Each target workflow keeps its own production approval. Successful targets are not rolled back when another target fails.",
        ]
    )
    return "\n".join(lines) + "\n"


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", action="store_true")
    parser.add_argument("--scraping", action="store_true")
    parser.add_argument("--web", action="store_true")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--preflight-only", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    try:
        targets = select_targets(service=args.service, scraping=args.scraping, web=args.web)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    if args.validate_only:
        return 0

    if args.preflight_only:
        results = preflight_selected(GitHubRestApi(), targets)
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
        releases = dispatch_selected(api, targets, request_id)
        releases = wait_for_releases(api, releases)
    except (ValueError, GitHubApiError) as error:
        print(str(error), file=sys.stderr)
        return 1

    summary = summarize_releases(releases)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with Path(summary_path).open("a", encoding="utf-8") as summary_file:
            summary_file.write(summary)
    print(summary)
    return 1 if any(release.status in {"failure", "cancelled", "timed-out"} for release in releases) else 0


if __name__ == "__main__":
    raise SystemExit(main())
