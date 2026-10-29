import unittest
from pathlib import Path

import yaml
from jinja2 import Environment, FileSystemLoader


REPO_ROOT = Path(__file__).parents[2]
APP_TEMPLATES = REPO_ROOT.parent / "deployment/ansible/roles/app/templates"


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

    def test_retired_mcp_runtime_has_no_deployment_playbooks_or_templates(self):
        deployment = REPO_ROOT.parent / "deployment/ansible"
        self.assertFalse((deployment / "mcp.yml").exists())
        self.assertFalse((deployment / "mcp-blue-green.yml").exists())
        self.assertFalse((deployment / "roles/mcp").exists())
        self.assertTrue((deployment / "catalog-sync.yml").exists())

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
