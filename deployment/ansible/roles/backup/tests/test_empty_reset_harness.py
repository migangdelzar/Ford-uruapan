import os
import json
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from empty_reset_harness import (
    HarnessError,
    container_argv,
    run_harness,
    validate_image_ref,
    validate_local_docker_host,
)


class EmptyResetHarnessSafetyTest(unittest.TestCase):
    def test_image_requires_registry_digest(self):
        image = "example.invalid/ansible-runner@sha256:" + "a" * 64
        self.assertEqual(validate_image_ref(image), image)

        for value in (
            "",
            "ansible-runner:latest",
            "example.invalid/ansible-runner@sha256:abc",
            "example.invalid/ansible-runner@sha256:" + "A" * 64,
        ):
            with self.subTest(value=value), self.assertRaises(HarnessError):
                validate_image_ref(value)

    def test_docker_host_must_be_an_explicit_approved_local_socket(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            socket_path = home / ".colima/native/docker.sock"
            socket_path.parent.mkdir(parents=True)
            local_socket = socket.socket(socket.AF_UNIX)
            try:
                local_socket.bind(str(socket_path))
                env = {
                    "DOCKER_HOST": "unix://" + str(socket_path),
                    "DOCKER_CONTEXT": "",
                }
                self.assertEqual(
                    validate_local_docker_host(env, str(home), "local-mac"),
                    os.path.realpath(str(socket_path)),
                )

                for invalid in (
                    {},
                    {"DOCKER_HOST": "tcp://127.0.0.1:2375"},
                    {"DOCKER_HOST": "ssh://emme-2"},
                    {"DOCKER_HOST": "unix:///tmp/unapproved.sock"},
                    {
                        "DOCKER_HOST": "unix://" + str(socket_path),
                        "SSH_CONNECTION": "local 22 remote 50000",
                    },
                ):
                    with self.subTest(invalid=invalid), self.assertRaises(HarnessError):
                        validate_local_docker_host(invalid, str(home), "local-mac")

                with self.assertRaises(HarnessError):
                    validate_local_docker_host(env, str(home), "emme-2")
            finally:
                local_socket.close()

    def test_container_argv_is_local_networkless_and_socket_free(self):
        argv = container_argv(
            "example.invalid/ansible-runner@sha256:" + "b" * 64,
            "/repo/worktree",
            "/private/tmp/ford-fixture",
        )

        self.assertEqual(argv[:2], ["docker", "run"])
        self.assertIn("--pull=never", argv)
        self.assertIn("--network", argv)
        self.assertEqual(argv[argv.index("--network") + 1], "none")
        self.assertIn("--read-only", argv)
        self.assertIn("--cap-drop", argv)
        self.assertIn("--cap-add", argv)
        self.assertIn("--entrypoint", argv)
        self.assertIn("python3", argv)
        self.assertTrue(
            any(
                value == "type=bind,src=/repo/worktree,dst=/workspace,readonly"
                for value in argv
            )
        )
        self.assertTrue(
            any(
                value == "type=bind,src=/private/tmp/ford-fixture,dst=/fixture"
                for value in argv
            )
        )
        self.assertNotIn("/var/run/docker.sock", " ".join(argv))
        self.assertNotIn(".colima", " ".join(argv))
        self.assertNotIn("--privileged", argv)
        self.assertNotIn("--network=host", argv)
        self.assertFalse(any("shell=True" in value for value in argv))

    @patch("shutil.which", return_value="/safe/bin/docker")
    @patch("empty_reset_harness.subprocess.run")
    def test_runner_resolves_docker_and_drops_unrelated_host_environment(self, run, which):
        digest = "c" * 64
        image = "example.invalid/ansible-runner@sha256:" + digest
        run.side_effect = [
            subprocess.CompletedProcess(
                args=["docker", "image", "inspect"],
                returncode=0,
                stdout=json.dumps([image]),
                stderr="",
            ),
            subprocess.CompletedProcess(
                args=["docker", "run"], returncode=0, stdout="ok", stderr=""
            ),
        ]

        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            socket_path = home / ".colima/native/docker.sock"
            socket_path.parent.mkdir(parents=True)
            local_socket = socket.socket(socket.AF_UNIX)
            try:
                local_socket.bind(str(socket_path))
                repo = home / "repo"
                fixture = home / "fixture"
                repo.mkdir()
                fixture.mkdir()
                result = run_harness(
                    image,
                    str(repo),
                    str(fixture),
                    env={
                        "DOCKER_HOST": "unix://" + str(socket_path),
                        "DOCKER_CONTEXT": "colima-emme",
                        "HOME": str(home),
                        "PATH": "/usr/bin:/bin",
                        "AWS_SECRET_ACCESS_KEY": "must-not-be-forwarded",
                    },
                    hostname="local-mac",
                )
            finally:
                local_socket.close()

        self.assertEqual(result.returncode, 0)
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args_list[0].args[0][0], "/safe/bin/docker")
        self.assertEqual(run.call_args_list[1].args[0][0], "/safe/bin/docker")
        which.assert_called_once_with("docker", path="/usr/bin:/bin")
        for call in run.call_args_list:
            self.assertFalse(call.kwargs["shell"])
            self.assertEqual(call.kwargs["env"]["DOCKER_CONTEXT"], "")
            self.assertNotIn("AWS_SECRET_ACCESS_KEY", call.kwargs["env"])
            self.assertEqual(
                call.kwargs["env"]["DOCKER_HOST"],
                "unix://" + os.path.realpath(str(socket_path)),
            )


if __name__ == "__main__":
    unittest.main()
