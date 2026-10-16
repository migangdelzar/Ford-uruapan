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

    def test_mcp_vault_environment_passes_only_secret_references_to_containers(self):
        templates = MCP_TEMPLATES
        environment = Environment(loader=FileSystemLoader(templates), autoescape=False)
        values = {
            "FORD_SECRETS_PROVIDER": "oci-vault",
            "FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD": "ocid-database",
            "FORD_VAULT_SECRET_OCID_MCP_HTTP_BEARER_TOKEN": "ocid-mcp-token",
            "POSTGRES_PASSWORD": "synthetic-db-password",
            "MCP_HTTP_BEARER_TOKEN": "synthetic-mcp-token",
        }
        environment.globals["lookup"] = lambda _plugin, key: values.get(key, "")
        rendered = environment.get_template("mcp.env.j2").render(
            mcp_image="ghcr.io/example/mcp@sha256:digest",
            emme_2_private_ip="10.0.0.2",
        )

        self.assertIn("FORD_SECRETS_PROVIDER=oci-vault", rendered)
        self.assertIn("FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD=ocid-database", rendered)
        self.assertIn("FORD_VAULT_SECRET_OCID_MCP_HTTP_BEARER_TOKEN=ocid-mcp-token", rendered)
        self.assertNotIn("synthetic-db-password", rendered)
        self.assertNotIn("synthetic-mcp-token", rendered)
        self.assertNotRegex(rendered, r"(?m)^POSTGRES_PASSWORD=")
        self.assertNotRegex(rendered, r"(?m)^MCP_HTTP_BEARER_TOKEN=")

    def test_mcp_compose_delegates_vault_resolution_to_the_mcp_process(self):
        for template_name in ("compose.yml.j2", "blue-green-compose.yml.j2"):
            compose = self.render(
                MCP_TEMPLATES,
                template_name,
                mcp_image="ghcr.io/example/ford-mcp@sha256:newdigest",
                mcp_previous_image="ghcr.io/example/ford-mcp@sha256:olddigest",
                mcp_candidate_slot="blue",
                mcp_blue_port=3101,
                mcp_green_port=3102,
                mcp_active_port=3101,
                mcp_proxy_image="nginx@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de",
                emme_2_private_ip="10.0.0.2",
                remote_root="/opt/ford",
                mcp_scheduler_image="ghcr.io/example/ford-mcp@sha256:scheduler",
            )
            services = compose["services"]
            mcp_services = [name for name in services if name.startswith("mcp") and name not in ("mcp-proxy",)]
            self.assertTrue(mcp_services)
            for name in mcp_services:
                environment = services[name]["environment"]
                self.assertIn("FORD_SECRETS_PROVIDER", environment)
                self.assertIn("FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD", environment)
                self.assertIn("DATABASE_HOST", environment)
                self.assertNotIn("POSTGRES_PASSWORD", environment)

    def test_mcp_protocol_smoke_resolves_vault_token_inside_container(self):
        playbook = (MCP_TEMPLATES.parent / "tasks" / "blue-green.yml").read_text()

        self.assertIn("loadRuntimeEnvironment", playbook)
        self.assertIn("command: 'mcp-http'", playbook)
        self.assertNotIn("process.env.MCP_HTTP_BEARER_TOKEN", playbook)

    def test_mcp_blue_green_refreshes_runtime_environment_from_protected_config(self):
        playbook = (MCP_TEMPLATES.parent / "tasks" / "blue-green.yml").read_text()

        self.assertIn("src: mcp.env.j2", playbook)
        self.assertIn('dest: "{{ remote_root }}/mcp/.env"', playbook)
        self.assertIn("FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD", playbook)
        candidate_validation = playbook.index("Validate current slot and requested rollback identity")
        environment_render = playbook.index("Render MCP runtime environment from protected configuration")
        self.assertLess(candidate_validation, environment_render)

    def test_service_blue_green_refreshes_application_environment(self):
        playbook = APP_TEMPLATES.parent / "tasks" / "blue-green.yml"
        source = playbook.read_text()

        self.assertIn("src: app.env.j2", source)
        self.assertIn('dest: "{{ remote_root }}/app/.env"', source)

    def test_service_environment_keeps_deepseek_primary_with_bounded_openrouter_fallback(self):
        template = (APP_TEMPLATES / "app.env.j2").read_text()
        production = (REPO_ROOT.parent / "ford-ai-service/src/main/resources/application-prod.yml").read_text()
        example = (REPO_ROOT.parent / "deployment/config/oci.env.example").read_text()

        self.assertIn("FORD_AI_CHAT_PROVIDER={{ ford_ai_chat_provider | default('openai') }}", template)
        self.assertIn("FORD_AI_CLOUD_ENABLED={{ (ford_ai_cloud_enabled | default(true))", template)
        self.assertIn("FORD_AI_LOCAL_ENABLED={{ (ford_ai_local_enabled | default(false))", template)
        self.assertIn("FORD_AI_CLOUD_BASE_URL={{ ford_ai_cloud_base_url | default('https://api.deepseek.com') }}", template)
        self.assertIn("FORD_AI_CLOUD_MODEL_VERSION={{ ford_ai_cloud_model_version | default('deepseek-flash') }}", template)
        self.assertIn("FORD_AI_PROVIDER_MAX_ATTEMPTS={{ ford_ai_provider_max_attempts | default(2) }}", template)
        self.assertIn("FORD_AI_FALLBACK_ORDER={{ ford_ai_fallback_order | default('openrouter') }}", template)
        self.assertIn("FORD_AI_OPENROUTER_MODEL={{ ford_ai_openrouter_model | default('xiaomi/mimo-v2.6-flash') }}", template)
        self.assertIn("model: ${FORD_AI_OPENROUTER_MODEL:xiaomi/mimo-v2.6-flash}", production)
        self.assertIn("FORD_AI_OPENROUTER_MODEL=xiaomi/mimo-v2.6-flash", example)
        self.assertIn("fallback-order: ${FORD_AI_FALLBACK_ORDER:openrouter}", production)
        self.assertIn("FORD_AI_OPENROUTER_ENABLED={{ (ford_ai_openrouter_enabled | default(true))", template)
        self.assertIn("FORD_AI_OPENROUTER_TOOL_CALLING_VERIFIED={{ (ford_ai_openrouter_tool_calling_verified | default(false))", template)
        self.assertIn("FORD_AI_OPENROUTER_STRUCTURED_OUTPUT_VERIFIED={{ (ford_ai_openrouter_structured_output_verified | default(false))", template)
        self.assertIn("FORD_AI_FALLBACK_ORDER=openrouter", example)
        self.assertIn("FORD_AI_OPENROUTER_ENABLED=true", example)
        self.assertIn("FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY", template)

    def test_app_proxy_keeps_stable_listener_and_routes_to_active_slot(self):
        template = Environment(loader=FileSystemLoader(APP_TEMPLATES)).get_template("proxy.conf.j2")
        rendered = template.render(app_active_port=18082)

        self.assertIn("listen 127.0.0.1:8080", rendered)
        self.assertIn("proxy_pass http://127.0.0.1:18082", rendered)
        self.assertIn('proxy_set_header Forwarded "";', rendered)
        self.assertIn("proxy_set_header X-Forwarded-Host $host;", rendered)
        self.assertIn("proxy_set_header X-Forwarded-Proto https;", rendered)
        self.assertIn("proxy_set_header X-Forwarded-Port 443;", rendered)
        self.assertNotIn("proxy_set_header X-Forwarded-Proto $scheme;", rendered)

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
