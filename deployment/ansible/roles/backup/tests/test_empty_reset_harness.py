import os
import json
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import hashlib
import importlib.util
import io

from empty_reset_harness import (
    HarnessError,
    container_argv,
    main,
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

    @patch("empty_reset_harness.subprocess.run")
    def test_cli_refuses_missing_pinned_image_before_docker_access(self, run):
        output = io.StringIO()
        errors = io.StringIO()
        with tempfile.TemporaryDirectory() as home:
            result = main(
                env={"HOME": home, "PATH": "/usr/bin:/bin"},
                stdout=output,
                stderr=errors,
                hostname="local-mac",
            )

        self.assertEqual(result, 2)
        self.assertIn("FORD_EMPTY_RESET_HARNESS_IMAGE", errors.getvalue())
        run.assert_not_called()

    @patch("empty_reset_harness.run_harness")
    def test_cli_fixture_uses_colima_shared_repository_mount(self, run_harness):
        run_harness.return_value = subprocess.CompletedProcess(
            args=["docker", "run"],
            returncode=0,
            stdout="SYNTHETIC PLAYBOOK SAFETY TEST: PASS\n",
            stderr="",
        )
        image = "example.invalid/ansible-runner@sha256:" + "d" * 64
        output = io.StringIO()
        errors = io.StringIO()
        result = main(
            env={"FORD_EMPTY_RESET_HARNESS_IMAGE": image},
            stdout=output,
            stderr=errors,
            hostname="local-mac",
        )

        self.assertEqual(result, 0)
        fixture_path = Path(run_harness.call_args.args[2])
        shared_root = Path(__file__).resolve().parents[5] / ".superpowers/sdd"
        self.assertEqual(fixture_path.parent, shared_root)
        self.assertTrue(fixture_path.name.startswith("ford-empty-reset-harness-"))
        self.assertIn("SYNTHETIC PLAYBOOK SAFETY TEST: PASS", output.getvalue())
        self.assertEqual(errors.getvalue(), "")

    def test_fixture_has_valid_synthetic_backup_and_local_inventory(self):
        from empty_reset_container_runner import make_fixture

        verifier_path = (
            Path(__file__).resolve().parents[2]
            / "catalog_sync/files/verify_backup.py"
        )
        spec = importlib.util.spec_from_file_location("verify_backup", verifier_path)
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)

        with tempfile.TemporaryDirectory() as temporary:
            fixture = make_fixture(Path(temporary), "synthetic-approval-01")
            self.assertEqual(
                verifier.verify_backup(
                    fixture["archive_dir"],
                    "20261102",
                    "ford_service",
                    fixture["postgres_archive"].name,
                ),
                {"backupCreatedAt": "20261102T120000Z"},
            )
            self.assertEqual(
                hashlib.sha256(fixture["manifest_path"].read_bytes()).hexdigest(),
                fixture["manifest_sha256"],
            )
            self.assertEqual(fixture["postgres_sentinel"].read_bytes(), b"synthetic-postgres-sentinel\n")
            self.assertEqual(fixture["redis_sentinel"].read_bytes(), b"synthetic-redis-sentinel\n")
            inventory = fixture["inventory_path"].read_text(encoding="utf-8")
            self.assertIn("[data]", inventory)
            self.assertIn("emme-2 ansible_connection=local", inventory)
            self.assertNotIn("emme-1", inventory)
            self.assertNotIn("ssh", inventory.lower())

    def test_fake_docker_is_resolved_from_its_fixture_path_first(self):
        from empty_reset_container_runner import make_fixture, resolve_fake_docker

        with tempfile.TemporaryDirectory() as temporary:
            fixture = make_fixture(Path(temporary), "synthetic-approval-02")
            path = str(fixture["docker_stub"].parent) + ":/usr/bin:/bin"
            self.assertEqual(
                resolve_fake_docker(path), str(fixture["docker_stub"])
            )

    def test_container_ansible_config_uses_supported_cfg_extension(self):
        from empty_reset_container_runner import write_ansible_config

        with tempfile.TemporaryDirectory() as temporary:
            config = write_ansible_config(Path(temporary))
            self.assertEqual(config.suffix, ".cfg")
            self.assertIn("[defaults]", config.read_text(encoding="utf-8"))

    def test_optional_stat_symlink_fields_default_to_false(self):
        playbook_dir = Path(__file__).resolve().parents[3]
        for playbook_name in ("ford-empty-reset.yml", "ford-empty-rollback.yml"):
            with self.subTest(playbook=playbook_name):
                lines = (playbook_dir / playbook_name).read_text(encoding="utf-8").splitlines()
                symlink_checks = [line for line in lines if ".stat.islnk" in line]
                self.assertTrue(symlink_checks)
                for line in symlink_checks:
                    self.assertIn("| default(false)", line, line)

    def test_backup_verifier_script_uses_python_interpreter(self):
        playbook_dir = Path(__file__).resolve().parents[3]
        playbook = (playbook_dir / "ford-empty-reset.yml").read_text(encoding="utf-8")
        task = playbook.split("- name: Verify exact manifest and PostgreSQL/Redis checksums", 1)[1]
        task = task.split("- name:", 1)[0]
        self.assertIn("executable: python3", task)

    def test_synthetic_refusal_matrix_covers_reset_and_rollback_guards(self):
        from empty_reset_container_runner import REFUSAL_CASES

        self.assertEqual(
            set(REFUSAL_CASES),
            {
                "missing-reset-approval",
                "wrong-inventory-alias",
                "wrong-manifest-digest",
                "corrupt-backup-artifact",
                "missing-backup-artifact",
                "existing-reset-archive",
                "symlinked-compose-path",
                "mismatched-rollback-approval",
                "mismatched-rollback-digest",
                "symlinked-reset-state",
                "occupied-rollback-destination",
            },
        )


if __name__ == "__main__":
    unittest.main()
