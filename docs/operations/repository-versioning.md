# Repository Versioning and Releases

Ford-scraping, Ford Service, and Ford Web use independent annotated
`vMAJOR.MINOR.PATCH` tags. The full commit SHA remains the source identity.
Tags must point to a commit reachable from protected `main`, and CI must have a
successful push run for that exact tag and SHA. The workflows reject
lightweight tags, malformed versions, mismatched SHAs, and tags outside `main`.

## GitHub setup

Protect `main` with the required CI checks and review rules appropriate to each
repository. Add an organization or repository tag ruleset matching `v*` that
restricts tag updates and deletions. If the ruleset supports restricting tag
creation, limit it to release maintainers. These GitHub account settings must
be applied by an owner; workflow checks do not prevent a privileged user from
moving or deleting a remote tag.

## Create a version

Choose an unused version for that repository and an exact CI-verified `main`
commit. Create an annotated tag and push it:

```sh
git checkout main
git pull --ff-only
git tag -a v1.2.3 <verified-commit-sha> -m "Ford release v1.2.3"
git push origin refs/tags/v1.2.3
```

Never move an existing version tag. Correct a release with a new patch version.
The tag push runs repository CI. After it succeeds, release-all dispatches the
repository release workflow. The two OCI repositories build, smoke-test, scan,
publish SemVer aliases, produce an SBOM, and verify provenance. Ford Web does
not create a container image.

## Publish and deploy

Use **Release selected Ford repositories** in Ford Service with operation
`validate` first. Select each repository and enter its independent version.
Review the resolved source SHAs and readiness result. Select operation
`release` only when ready to dispatch. The coordinator records each target's
version, SHA, workflow URL, artifact digest, and deployment identity in the
summary and downloadable JSON manifest.

Ford Service and Ford-scraping use their tagged GHCR image digest for OCI
rollouts. Their existing deployment action performs the pre-release backup,
health checks, traffic switch, and automatic restoration path. Ford Web
deploys the selected commit to its existing Cloudflare Pages project. Production
environment approvals and repository deployment gates remain required; a
successful CI tag does not itself authorize production deployment.

## Roll back

Use the target repository's release workflow rollback operation. OCI rollback
restores the previously recorded image digest and source identity; it does not
move a SemVer tag. Ford Web rollback redeploys a commit that has already had a
successful production Pages deployment. Record the rollback run URL in the
incident or release manifest. A corrected forward release receives a new
version tag.

## Result manifest

Each target workflow uploads a `ford-release-result` JSON artifact after its
release work. The coordinator validates the repository, version, and SHA in
that artifact before copying its image digest and deployment identity into the
cross-repository manifest. A missing or mismatched result artifact is reported
as a target failure. Fields for deployments that were gated off remain null.
