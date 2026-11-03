#!/usr/bin/env python3
"""Safely launch the synthetic empty-reset playbook runner in a local container."""

import json
import os
import re
import shutil
import stat
import subprocess


class HarnessError(RuntimeError):
    pass


_IMAGE_REF = re.compile(r"^[A-Za-z0-9][A-Za-z0-9./:_-]*@sha256:[a-f0-9]{64}$")


def validate_image_ref(value):
    if not isinstance(value, str) or not _IMAGE_REF.fullmatch(value):
        raise HarnessError(
            "FORD_EMPTY_RESET_HARNESS_IMAGE must be an immutable image@sha256 digest reference."
        )
    return value


def validate_local_docker_host(env, home, hostname):
    docker_host = env.get("DOCKER_HOST", "")
    if not docker_host.startswith("unix://"):
        raise HarnessError("DOCKER_HOST must explicitly name an approved local Unix socket.")
    if env.get("SSH_CONNECTION") or env.get("SSH_CLIENT"):
        raise HarnessError("Synthetic harness cannot run from an SSH session.")
    if any(token in hostname.lower() for token in ("emme", "testbox")):
        raise HarnessError("Synthetic harness cannot run on emme or testbox hosts.")

    supplied_path = docker_host[len("unix://"):]
    try:
        socket_path = os.path.realpath(supplied_path)
        socket_stat = os.stat(socket_path)
    except OSError as error:
        raise HarnessError("DOCKER_HOST must point to an existing local Unix socket.") from error
    if not stat.S_ISSOCK(socket_stat.st_mode):
        raise HarnessError("DOCKER_HOST must point to an existing local Unix socket.")

    approved_paths = (
        os.path.join(home, ".colima/native/docker.sock"),
        os.path.join(home, ".docker/run/docker.sock"),
        "/var/run/docker.sock",
    )
    approved_realpaths = {os.path.realpath(path) for path in approved_paths}
    if socket_path not in approved_realpaths:
        raise HarnessError("DOCKER_HOST is not one of the approved local Docker sockets.")
    if any(token in socket_path.lower() for token in ("emme", "testbox")):
        raise HarnessError("Remote, emme, and testbox Docker endpoints are forbidden.")
    return socket_path


def _mount_path(path):
    resolved = os.path.realpath(path)
    if not os.path.isabs(resolved) or "," in resolved:
        raise HarnessError("Harness mount paths must be absolute and cannot contain commas.")
    return resolved


def container_argv(image, repo_root, fixture_dir):
    image = validate_image_ref(image)
    repo_root = _mount_path(repo_root)
    fixture_dir = _mount_path(fixture_dir)
    runner = "/workspace/deployment/ansible/roles/backup/tests/empty_reset_container_runner.py"
    return [
        "docker",
        "run",
        "--rm",
        "--pull=never",
        "--network",
        "none",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--cap-add",
        "CHOWN",
        "--security-opt",
        "no-new-privileges",
        "--mount",
        "type=bind,src=" + repo_root + ",dst=/workspace,readonly",
        "--mount",
        "type=bind,src=" + fixture_dir + ",dst=/fixture",
        "--tmpfs",
        "/tmp:rw,nosuid,nodev,size=128m",
        "--workdir",
        "/workspace",
        "--env",
        "HOME=/tmp/home",
        "--env",
        "ANSIBLE_LOCAL_TEMP=/tmp/ansible-local",
        "--env",
        "ANSIBLE_REMOTE_TEMP=/tmp/ansible-remote",
        "--env",
        "PATH=/fixture/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "--entrypoint",
        "python3",
        image,
        runner,
    ]


def run_harness(image, repo_root, fixture_dir, env=None, hostname=None):
    source_env = dict(os.environ if env is None else env)
    home = source_env.get("HOME", "")
    if not home:
        raise HarnessError("HOME must identify the local user's home directory.")
    hostname = hostname or os.uname().nodename
    image = validate_image_ref(image)
    socket_path = validate_local_docker_host(source_env, home, hostname)

    safe_env = {
        "DOCKER_HOST": "unix://" + socket_path,
        "DOCKER_CONTEXT": "",
        "HOME": home,
        "PATH": source_env.get("PATH", "/usr/bin:/bin"),
    }
    docker_executable = shutil.which("docker", path=safe_env["PATH"])
    if not docker_executable or not os.path.isabs(docker_executable):
        raise HarnessError("Docker CLI was not found on the configured local PATH.")
    inspect = subprocess.run(
        [docker_executable, "image", "inspect", "--format", "{{json .RepoDigests}}", image],
        shell=False,
        check=False,
        capture_output=True,
        text=True,
        env=safe_env,
    )
    if inspect.returncode != 0:
        raise HarnessError(
            "Pinned Ansible runner image is not available locally; implicit pulls are disabled."
        )
    try:
        repo_digests = json.loads(inspect.stdout)
    except (TypeError, json.JSONDecodeError) as error:
        raise HarnessError("Docker did not return valid local image digest metadata.") from error
    if not isinstance(repo_digests, list) or image not in repo_digests:
        raise HarnessError("Local runner image RepoDigests do not match the requested digest.")

    run_argv = container_argv(image, repo_root, fixture_dir)
    run_argv[0] = docker_executable
    return subprocess.run(
        run_argv,
        shell=False,
        check=False,
        capture_output=True,
        text=True,
        env=safe_env,
    )
