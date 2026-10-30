import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[2]
WORKFLOW = REPO_ROOT.parent / ".github/workflows/reusable-java-ci.yml"


class IntegrationCiContractTest(unittest.TestCase):
    def test_integration_job_populates_bge_cache_before_testcontainers_mount_it(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        integration_job = workflow.split("  integration:\n", 1)[1].split("  evaluation:\n", 1)[0]

        self.assertIn("ollama/ollama:0.40.4", integration_job)
        self.assertIn("ollama pull bge-m3", integration_job)
        self.assertIn("$HOME/.ollama:/root/.ollama", integration_job)

    def test_production_catalog_replay_requires_an_explicit_candidate_export(self):
        replay = (
            REPO_ROOT
            / "src/integrationTest/java/com/ford/service/conversations/"
            "QwenProductionRoutingWebhookReplayIntegrationTest.java"
        ).read_text(encoding="utf-8")

        candidate_gate = (
            '@EnabledIfEnvironmentVariable(named = "FORD_LOCAL_CATALOG_EXPORT_DIR", matches = ".+")'
        )
        self.assertIn(candidate_gate, replay)
        self.assertLess(replay.index(candidate_gate), replay.index("class QwenProductionRoutingWebhookReplayIntegrationTest"))

    def test_candidate_dependent_integration_suite_never_reuses_stale_results(self):
        build = (REPO_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        integration_task = build.split('val integrationTest = tasks.register<Test>("integrationTest")', 1)[1]
        integration_task = integration_task.split("tasks.register<JavaExec>(\"importLocalCatalogSnapshot\")", 1)[0]

        self.assertIn("outputs.upToDateWhen { false }", integration_task)


if __name__ == "__main__":
    unittest.main()
