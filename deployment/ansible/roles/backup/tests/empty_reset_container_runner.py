#!/usr/bin/env python3
"""Run the real reset/rollback playbooks against synthetic container-local data."""

import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
from pathlib import Path


ROOT = Path("/workspace")
PLAYBOOK_DIR = ROOT / "deployment/ansible"
FIXTURE_ROOT = Path("/fixture")
CREATED_AT = "20261102T120000Z"
APPROVAL_ID = "synthetic-approval-01"
MANIFEST_NAME = "manifest_" + CREATED_AT + ".json"
POSTGRES_NAME = "ford_service_" + CREATED_AT + ".dump.gz"
REDIS_NAME = "redis_" + CREATED_AT + ".rdb.gz"
POSTGRES_BACKUP = b"synthetic postgres backup bytes\n"
REDIS_BACKUP = b"synthetic redis backup bytes\n"
POSTGRES_SENTINEL = b"synthetic-postgres-sentinel\n"
REDIS_SENTINEL = b"synthetic-redis-sentinel\n"
POSTGRES_REPLACEMENT = b"synthetic-new-postgres-state\n"
REDIS_REPLACEMENT = b"synthetic-new-redis-state\n"
REFUSAL_CASES = (
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
)


def _sha256(content):
    return hashlib.sha256(content).hexdigest()


def make_fixture(root, approval_id):
    if not approval_id.startswith("synthetic-"):
        raise ValueError("fixture approval IDs must use the synthetic- prefix")
    root = Path(root).resolve()
    remote_root = root / "remote"
    data_mount = root / "data-volume"
    archive_dir = remote_root / "backup/archive"
    postgres_dir = data_mount / "postgres"
    redis_dir = data_mount / "redis"
    for directory in (archive_dir, postgres_dir, redis_dir):
        directory.mkdir(parents=True, exist_ok=True)

    (remote_root / "data").mkdir(parents=True, exist_ok=True)
    (remote_root / "data/compose.yml").write_text(
        "services:\n  postgres: {}\n  redis: {}\n", encoding="utf-8"
    )
    (remote_root / "data/.env").write_text(
        "POSTGRES_IMAGE=synthetic\nREDIS_IMAGE=synthetic\n", encoding="utf-8"
    )
    (postgres_dir / "PG_VERSION").write_bytes(POSTGRES_SENTINEL)
    (redis_dir / "dump.rdb").write_bytes(REDIS_SENTINEL)

    postgres_archive = archive_dir / POSTGRES_NAME
    redis_archive = archive_dir / REDIS_NAME
    postgres_archive.write_bytes(POSTGRES_BACKUP)
    redis_archive.write_bytes(REDIS_BACKUP)
    manifest = {
        "created_at": CREATED_AT,
        "database": "ford_service",
        "postgres": POSTGRES_NAME,
        "postgres_sha256": _sha256(POSTGRES_BACKUP),
        "redis": REDIS_NAME,
        "redis_sha256": _sha256(REDIS_BACKUP),
    }
    manifest_path = archive_dir / MANIFEST_NAME
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
    manifest_path.write_bytes(manifest_bytes)

    inventory_path = root / "inventory.ini"
    inventory_path.write_text(
        "[data]\n"
        "emme-2 ansible_connection=local ansible_python_interpreter="
        + sys.executable
        + "\n",
        encoding="utf-8",
    )
    bin_dir = root / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    docker_log = root / "docker-calls.jsonl"
    expected_docker_call = [
        "compose",
        "--project-name",
        "data",
        "--env-file",
        str(remote_root / "data/.env"),
        "-f",
        str(remote_root / "data/compose.yml"),
        "stop",
        "postgres",
        "redis",
    ]
    expected_call_path = root / "expected-docker-call.json"
    expected_call_path.write_text(json.dumps(expected_docker_call), encoding="utf-8")
    docker_stub = bin_dir / "docker"
    docker_stub.write_text(
        "#!/usr/bin/env python3\n"
        "import json, os, sys\n"
        "with open(os.environ['HARNESS_EXPECTED_DOCKER_CALL'], encoding='utf-8') as f:\n"
        "    expected = json.load(f)\n"
        "actual = sys.argv[1:]\n"
        "if actual != expected:\n"
        "    print('unexpected synthetic Docker invocation', file=sys.stderr)\n"
        "    raise SystemExit(97)\n"
        "with open(os.environ['HARNESS_DOCKER_LOG'], 'a', encoding='utf-8') as f:\n"
        "    f.write(json.dumps(actual) + '\\n')\n",
        encoding="utf-8",
    )
    docker_stub.chmod(0o755)

    return {
        "root": root,
        "remote_root": remote_root,
        "data_mount": data_mount,
        "archive_dir": archive_dir,
        "postgres_archive": postgres_archive,
        "redis_archive": redis_archive,
        "manifest_path": manifest_path,
        "manifest_sha256": _sha256(manifest_bytes),
        "postgres_sentinel": postgres_dir / "PG_VERSION",
        "redis_sentinel": redis_dir / "dump.rdb",
        "inventory_path": inventory_path,
        "docker_stub": docker_stub,
        "docker_log": docker_log,
        "expected_docker_call": expected_call_path,
        "approval_id": approval_id,
    }


def resolve_fake_docker(path):
    return shutil.which("docker", path=path)


def write_ansible_config(directory):
    config = Path(directory) / "ansible-harness.cfg"
    config.write_text("[defaults]\nretry_files_enabled = False\n", encoding="utf-8")
    return config


def run_playbook(playbook, inventory, extra_vars, env):
    playbook_path = PLAYBOOK_DIR / playbook
    return subprocess.run(
        [
            "ansible-playbook",
            "--inventory",
            str(inventory),
            str(playbook_path),
            "--extra-vars",
            json.dumps(extra_vars, sort_keys=True),
        ],
        cwd=str(ROOT),
        env=env,
        shell=False,
        check=False,
        capture_output=True,
        text=True,
    )


def _assert_success(result, playbook):
    if result.returncode != 0:
        raise AssertionError(
            playbook + " failed in synthetic harness:\n" + result.stdout + result.stderr
        )


def _assert_directory_empty(path):
    if not path.is_dir() or any(path.iterdir()):
        raise AssertionError("expected empty synthetic active directory: " + str(path))


def _snapshot_tree(root):
    snapshot = {}
    for path in sorted(Path(root).rglob("*")):
        relative = str(path.relative_to(root))
        if path.is_symlink():
            snapshot[relative] = ("symlink", os.readlink(str(path)))
        elif path.is_file():
            snapshot[relative] = ("file", _sha256(path.read_bytes()))
        elif path.is_dir():
            snapshot[relative] = ("directory",)
        else:
            snapshot[relative] = ("other",)
    return snapshot


def _playbook_environment(fixture):
    ansible_config = write_ansible_config("/tmp")
    docker_path = str(fixture["docker_stub"].parent) + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    if resolve_fake_docker(docker_path) != str(fixture["docker_stub"]):
        raise RuntimeError("fake Docker command is not first and only in PATH")
    return {
        "HOME": "/tmp/home",
        "PATH": docker_path,
        "ANSIBLE_CONFIG": str(ansible_config),
        "ANSIBLE_LOCAL_TEMP": "/tmp/ansible-local",
        "ANSIBLE_REMOTE_TEMP": "/tmp/ansible-remote",
        "ANSIBLE_RETRY_FILES_ENABLED": "False",
        "HARNESS_EXPECTED_DOCKER_CALL": str(fixture["expected_docker_call"]),
        "HARNESS_DOCKER_LOG": str(fixture["docker_log"]),
    }


def _common_vars(fixture):
    return {
        "remote_root": str(fixture["remote_root"]),
        "data_volume_mount": str(fixture["data_mount"]),
        "ansible_become": False,
    }


def _reset_vars(fixture):
    variables = _common_vars(fixture)
    variables.update(
        {
            "ford_empty_reset_approved": True,
            "ford_empty_reset_approval_id": fixture["approval_id"],
            "ford_empty_reset_backup_manifest_sha256": fixture["manifest_sha256"],
            "ford_empty_expected_database": "ford",
        }
    )
    return variables


def _seed_rollback_state(fixture):
    approval = fixture["approval_id"]
    mount = str(fixture["data_mount"])
    old_postgres = fixture["data_mount"] / ("postgres-pre-reset-" + approval)
    old_redis = fixture["data_mount"] / ("redis-pre-reset-" + approval)
    old_postgres.mkdir()
    old_redis.mkdir()
    (old_postgres / "PG_VERSION").write_bytes(POSTGRES_SENTINEL)
    (old_redis / "dump.rdb").write_bytes(REDIS_SENTINEL)
    (fixture["data_mount"] / "postgres/replacement.marker").write_bytes(POSTGRES_REPLACEMENT)
    (fixture["data_mount"] / "redis/replacement.marker").write_bytes(REDIS_REPLACEMENT)
    state = {
        "status": "reset",
        "host": "emme-2",
        "approval_id": approval,
        "backup_manifest_sha256": fixture["manifest_sha256"],
        "database": "ford",
        "data_mount": mount,
        "old_postgres_path": mount + "/postgres-pre-reset-" + approval,
        "old_redis_path": mount + "/redis-pre-reset-" + approval,
        "active_postgres_path": mount + "/postgres",
        "active_redis_path": mount + "/redis",
    }
    state_path = fixture["remote_root"] / "data/empty-reset-state.json"
    state_path.write_text(json.dumps(state), encoding="utf-8")
    state_path.chmod(0o600)
    return state_path


def run_refusal_case(case_name, fixture_root):
    if case_name not in REFUSAL_CASES:
        raise ValueError("unknown synthetic refusal case: " + case_name)
    fixture = make_fixture(fixture_root, APPROVAL_ID)
    env = _playbook_environment(fixture)
    inventory = fixture["inventory_path"]
    playbook = "ford-empty-reset.yml"
    variables = _reset_vars(fixture)
    expected = ""
    reset_cases = {
        "missing-reset-approval",
        "wrong-inventory-alias",
        "wrong-manifest-digest",
        "corrupt-backup-artifact",
        "missing-backup-artifact",
        "existing-reset-archive",
        "symlinked-compose-path",
    }

    if case_name == "missing-reset-approval":
        variables["ford_empty_reset_approved"] = False
        expected = "Empty reset requires emme-2"
    elif case_name == "wrong-inventory-alias":
        inventory.write_text(
            "[data]\nemme-1 ansible_connection=local ansible_python_interpreter="
            + sys.executable
            + "\n",
            encoding="utf-8",
        )
        expected = "Empty reset requires emme-2"
    elif case_name == "wrong-manifest-digest":
        variables["ford_empty_reset_backup_manifest_sha256"] = "0" * 64
        expected = "supplied backup manifest digest must identify exactly one"
    elif case_name == "corrupt-backup-artifact":
        fixture["postgres_archive"].write_bytes(b"tampered synthetic backup\n")
        variables["ford_empty_reset_backup_manifest_sha256"] = _sha256(
            fixture["manifest_path"].read_bytes()
        )
        expected = "postgres backup checksum does not match"
    elif case_name == "missing-backup-artifact":
        fixture["redis_archive"].unlink()
        variables["ford_empty_reset_backup_manifest_sha256"] = _sha256(
            fixture["manifest_path"].read_bytes()
        )
        expected = "redis backup artifact is missing or not regular"
    elif case_name == "existing-reset-archive":
        (fixture["data_mount"] / ("postgres-pre-reset-" + APPROVAL_ID)).mkdir()
        expected = "reset archive destination already exists"
    elif case_name == "symlinked-compose-path":
        compose_env = fixture["remote_root"] / "data/.env"
        compose_env.unlink()
        compose_env.symlink_to("compose.yml")
        expected = "Reset control, data Compose, or backup archive path is missing or a symlink"
    else:
        playbook = "ford-empty-rollback.yml"
        state_path = _seed_rollback_state(fixture)
        variables = _common_vars(fixture)
        variables.update(
            {
                "ford_empty_rollback_approved": True,
                "ford_empty_reset_approval_id": APPROVAL_ID,
                "ford_empty_reset_backup_manifest_sha256": fixture["manifest_sha256"],
            }
        )
        if case_name == "mismatched-rollback-approval":
            variables["ford_empty_reset_approval_id"] = "synthetic-other-approval"
            expected = "Reset state differs from this target, approval, digest"
        elif case_name == "mismatched-rollback-digest":
            variables["ford_empty_reset_backup_manifest_sha256"] = "0" * 64
            expected = "Reset state differs from this target, approval, digest"
        elif case_name == "symlinked-reset-state":
            state_path.unlink()
            state_path.symlink_to(".env")
            expected = "Missing or unsafe reset state record; refusing rollback"
        elif case_name == "occupied-rollback-destination":
            (fixture["data_mount"] / ("postgres-empty-" + APPROVAL_ID)).mkdir()
            expected = "empty-directory archive destination already exists"

    if case_name in reset_cases and case_name not in (
        "wrong-manifest-digest",
        "corrupt-backup-artifact",
        "missing-backup-artifact",
    ):
        variables["ford_empty_reset_backup_manifest_sha256"] = fixture["manifest_sha256"]

    before = _snapshot_tree(fixture["root"])
    result = run_playbook(playbook, inventory, variables, env)
    output = result.stdout + result.stderr
    if result.returncode == 0:
        raise AssertionError(case_name + " unexpectedly passed instead of refusing")
    if expected not in output:
        raise AssertionError(
            case_name + " refused at the wrong boundary; expected " + repr(expected) + ":\n" + output[-5000:]
        )
    if _snapshot_tree(fixture["root"]) != before:
        raise AssertionError(case_name + " changed synthetic fixture data before refusing")
    if fixture["docker_log"].exists():
        raise AssertionError(case_name + " reached the fake Docker service-stop boundary")


def run_happy_path(root):
    if Path("/var/run/docker.sock").exists():
        raise RuntimeError("container unexpectedly exposes /var/run/docker.sock")
    Path("/tmp/home").mkdir(parents=True, exist_ok=True)
    fixture = make_fixture(root, APPROVAL_ID)
    ansible_config = write_ansible_config("/tmp")
    fake_docker_path = str(fixture["docker_stub"].parent) + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    if resolve_fake_docker(fake_docker_path) != str(fixture["docker_stub"]):
        raise RuntimeError("fake Docker command is not first and only in PATH")

    env = {
        "HOME": "/tmp/home",
        "PATH": fake_docker_path,
        "ANSIBLE_CONFIG": str(ansible_config),
        "ANSIBLE_LOCAL_TEMP": "/tmp/ansible-local",
        "ANSIBLE_REMOTE_TEMP": "/tmp/ansible-remote",
        "ANSIBLE_RETRY_FILES_ENABLED": "False",
        "HARNESS_EXPECTED_DOCKER_CALL": str(fixture["expected_docker_call"]),
        "HARNESS_DOCKER_LOG": str(fixture["docker_log"]),
    }
    common_vars = {
        "remote_root": str(fixture["remote_root"]),
        "data_volume_mount": str(fixture["data_mount"]),
        "ansible_become": False,
    }
    reset_vars = dict(common_vars)
    reset_vars.update(
        {
            "ford_empty_reset_approved": True,
            "ford_empty_reset_approval_id": fixture["approval_id"],
            "ford_empty_reset_backup_manifest_sha256": fixture["manifest_sha256"],
            "ford_empty_expected_database": "ford",
        }
    )
    reset = run_playbook(
        "ford-empty-reset.yml", fixture["inventory_path"], reset_vars, env
    )
    _assert_success(reset, "ford-empty-reset.yml")

    approval = fixture["approval_id"]
    archived_postgres = fixture["data_mount"] / ("postgres-pre-reset-" + approval)
    archived_redis = fixture["data_mount"] / ("redis-pre-reset-" + approval)
    active_postgres = fixture["data_mount"] / "postgres"
    active_redis = fixture["data_mount"] / "redis"
    if (archived_postgres / "PG_VERSION").read_bytes() != POSTGRES_SENTINEL:
        raise AssertionError("reset did not preserve the original PostgreSQL sentinel")
    if (archived_redis / "dump.rdb").read_bytes() != REDIS_SENTINEL:
        raise AssertionError("reset did not preserve the original Redis sentinel")
    _assert_directory_empty(active_postgres)
    _assert_directory_empty(active_redis)

    state_path = fixture["remote_root"] / "data/empty-reset-state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if stat.S_IMODE(state_path.stat().st_mode) != 0o600:
        raise AssertionError("reset state record is not mode 0600")
    if state.get("status") != "reset" or state.get("database") != "ford":
        raise AssertionError("reset state record does not describe the synthetic empty ford target")
    if state.get("backup_manifest_sha256") != fixture["manifest_sha256"]:
        raise AssertionError("reset state record does not bind the tested manifest digest")

    (active_postgres / "replacement.marker").write_bytes(POSTGRES_REPLACEMENT)
    (active_redis / "replacement.marker").write_bytes(REDIS_REPLACEMENT)
    rollback_vars = dict(common_vars)
    rollback_vars.update(
        {
            "ford_empty_rollback_approved": True,
            "ford_empty_reset_approval_id": approval,
            "ford_empty_reset_backup_manifest_sha256": fixture["manifest_sha256"],
        }
    )
    rollback = run_playbook(
        "ford-empty-rollback.yml", fixture["inventory_path"], rollback_vars, env
    )
    _assert_success(rollback, "ford-empty-rollback.yml")

    if (active_postgres / "PG_VERSION").read_bytes() != POSTGRES_SENTINEL:
        raise AssertionError("rollback did not restore original PostgreSQL bytes")
    if (active_redis / "dump.rdb").read_bytes() != REDIS_SENTINEL:
        raise AssertionError("rollback did not restore original Redis bytes")
    preserved_postgres = fixture["data_mount"] / ("postgres-empty-" + approval)
    preserved_redis = fixture["data_mount"] / ("redis-empty-" + approval)
    if (preserved_postgres / "replacement.marker").read_bytes() != POSTGRES_REPLACEMENT:
        raise AssertionError("rollback did not preserve synthetic replacement PostgreSQL bytes")
    if (preserved_redis / "replacement.marker").read_bytes() != REDIS_REPLACEMENT:
        raise AssertionError("rollback did not preserve synthetic replacement Redis bytes")

    final_state = json.loads(state_path.read_text(encoding="utf-8"))
    if final_state.get("status") != "rolled_back":
        raise AssertionError("rollback state record was not marked rolled_back")
    calls = [json.loads(line) for line in fixture["docker_log"].read_text().splitlines()]
    if calls != [json.loads(fixture["expected_docker_call"].read_text())] * 2:
        raise AssertionError("fake Docker call log differs from the exact expected stop args")


def main():
    try:
        run_happy_path(FIXTURE_ROOT / "happy-path")
        Path("/tmp/home").mkdir(parents=True, exist_ok=True)
        for case_name in REFUSAL_CASES:
            run_refusal_case(case_name, FIXTURE_ROOT / "refusals" / case_name)
    except Exception as error:
        print("SYNTHETIC PLAYBOOK SAFETY TEST: FAIL", file=sys.stderr)
        print(str(error), file=sys.stderr)
        return 1
    print("SYNTHETIC PLAYBOOK SAFETY TEST: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
