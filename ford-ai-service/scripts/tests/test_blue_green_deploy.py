import unittest
from pathlib import Path

import yaml
from jinja2 import Environment, FileSystemLoader


REPO_ROOT = Path(__file__).parents[2]
APP_TEMPLATES = REPO_ROOT.parent / "deployment/ansible/roles/app/templates"
MCP_TEMPLATES = REPO_ROOT.parent / "deployment/ansible/roles/mcp/templates"


class BlueGreenComposeContractTest(unittest.TestCase):
    def render(self, templates: Path, template_name: str, **values: str) -> dict:
        environment = Environment(loader=FileSystemLoader(templates), autoescape=False)
        rendered = environment.get_template(template_name).render(**values)
        return yaml.safe_load(rendered)

    def test_application_slots_have_distinct_ports_and_immutable_images(self):
        compose = self.render(
            APP_TEMPLATES,
            "blue-green-compose.yml.j2",
            app_image="ghcr.io/example/ford-service@sha256:newdigest",
            app_previous_image="ghcr.io/example/ford-service@sha256:olddigest",
            app_candidate_slot="blue",
            app_blue_port=18081,
            app_green_port=18082,
            app_active_port=18081,
            app_proxy_image="nginx@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de",
            docker_container_logs_path="/var/lib/docker/containers",
        )

        self.assertIn("ford-service-blue", compose["services"])
        self.assertIn("ford-service-green", compose["services"])
        blue = compose["services"]["ford-service-blue"]
        green = compose["services"]["ford-service-green"]
        self.assertEqual(blue["image"], "ghcr.io/example/ford-service@sha256:newdigest")
        self.assertEqual(green["image"], "ghcr.io/example/ford-service@sha256:olddigest")
        self.assertEqual(blue["ports"], ["127.0.0.1:18081:8080"])
        self.assertEqual(green["ports"], ["127.0.0.1:18082:8080"])
        proxy = compose["services"]["app-proxy"]
        self.assertEqual(proxy["network_mode"], "host")
        self.assertEqual(proxy["image"], "nginx@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de")

    def test_mcp_slots_do_not_run_migrations_at_container_start(self):
        compose = self.render(
            MCP_TEMPLATES,
            "blue-green-compose.yml.j2",
            mcp_image="ghcr.io/example/ford-mcp@sha256:newdigest",
            mcp_previous_image="ghcr.io/example/ford-mcp@sha256:olddigest",
            mcp_candidate_slot="blue",
            mcp_blue_port=3101,
            mcp_green_port=3102,
            mcp_active_port=3101,
            mcp_proxy_image="nginx@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de",
            emme_2_private_ip="10.0.0.2",
            remote_root="/opt/ford",
        )

        self.assertIn("mcp-blue", compose["services"])
        self.assertIn("mcp-green", compose["services"])
        blue = compose["services"]["mcp-blue"]
        green = compose["services"]["mcp-green"]
        self.assertEqual(blue["image"], "ghcr.io/example/ford-mcp@sha256:newdigest")
        self.assertEqual(green["image"], "ghcr.io/example/ford-mcp@sha256:olddigest")
        self.assertEqual(blue["ports"], ["127.0.0.1:3101:3001"])
        self.assertEqual(green["ports"], ["127.0.0.1:3102:3001"])
        self.assertNotIn("migrate", blue.get("command", []))
        proxy = compose["services"]["mcp-proxy"]
        self.assertEqual(proxy["network_mode"], "host")
        self.assertEqual(proxy["image"], "nginx@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de")

    def test_app_proxy_keeps_stable_listener_and_routes_to_active_slot(self):
        template = Environment(loader=FileSystemLoader(APP_TEMPLATES)).get_template("proxy.conf.j2")
        rendered = template.render(app_active_port=18082)

        self.assertIn("listen 127.0.0.1:8080", rendered)
        self.assertIn("proxy_pass http://127.0.0.1:18082", rendered)

    def test_mcp_proxy_keeps_tunnel_listener_and_routes_to_active_slot(self):
        template = Environment(loader=FileSystemLoader(MCP_TEMPLATES)).get_template("proxy.conf.j2")
        rendered = template.render(mcp_active_port=3102, emme_2_private_ip="10.0.0.2")

        self.assertIn("listen 127.0.0.1:3001", rendered)
        self.assertIn("listen 10.0.0.2:3001", rendered)
        self.assertIn("proxy_pass http://127.0.0.1:3102", rendered)

    def test_mcp_deploy_checks_candidate_before_switch_and_retains_rollback_path(self):
        task_file = REPO_ROOT.parent / "deployment/ansible/roles/mcp/tasks/blue-green.yml"
        self.assertTrue(task_file.exists(), "blue-green MCP deployment tasks must exist")
        task_text = task_file.read_text(encoding="utf-8")

        candidate_check = task_text.index("Wait for candidate MCP health")
        switch = task_text.index("Switch stable MCP proxy")
        stable_check = task_text.index("Verify stable MCP route")
        state_write = task_text.index("Record active and previous MCP release slots")
        recovery = task_text.index("Restore prior MCP route after failed smoke")
        self.assertLess(candidate_check, switch)
        self.assertLess(switch, stable_check)
        self.assertLess(stable_check, state_write)
        self.assertLess(state_write, recovery)
        self.assertIn("@sha256:", task_text)
        self.assertNotIn("FORD_MCP_TOKEN", task_text)

    def test_regular_mcp_deployment_runs_migrations_once_before_startup(self):
        task_file = REPO_ROOT.parent / "deployment/ansible/roles/mcp/tasks/main.yml"
        compose_file = MCP_TEMPLATES / "compose.yml.j2"
        task_text = task_file.read_text(encoding="utf-8")
        compose_text = compose_file.read_text(encoding="utf-8")

        migration = task_text.index("Run MCP catalog migrations once before starting the service")
        startup = task_text.index("Start MCP and embedding service")
        self.assertLess(migration, startup)
        self.assertIn("node dist/src/cli.js migrate", task_text[migration:startup])
        self.assertNotIn("migrate", compose_text)

    def test_legacy_mcp_listener_must_be_unique_before_switch(self):
        task_file = REPO_ROOT.parent / "deployment/ansible/roles/mcp/tasks/blue-green.yml"
        task_text = task_file.read_text(encoding="utf-8")
        self.assertIn("Validate legacy MCP listener count", task_text)
        self.assertIn("mcp_legacy_listener.stdout_lines | length <= 1", task_text)

    def test_app_deploy_checks_candidate_switches_and_restores_prior_route(self):
        task_file = REPO_ROOT.parent / "deployment/ansible/roles/app/tasks/blue-green.yml"
        self.assertTrue(task_file.exists(), "blue-green Ford Service deployment tasks must exist")
        task_text = task_file.read_text(encoding="utf-8")

        candidate_check = task_text.index("Wait for candidate Ford Service health")
        switch = task_text.index("Switch stable Ford Service proxy")
        stable_check = task_text.index("Verify stable Ford Service health")
        state_write = task_text.index("Record active and previous Ford Service release slots")
        recovery = task_text.index("Restore prior Ford Service route after failed smoke")
        self.assertLess(candidate_check, switch)
        self.assertLess(switch, stable_check)
        self.assertLess(stable_check, state_write)
        self.assertLess(state_write, recovery)
        self.assertIn("docker logout ghcr.io", task_text)
        self.assertIn("@sha256:", task_text)


if __name__ == "__main__":
    unittest.main()
