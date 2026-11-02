import importlib.util
import hashlib
import unittest
from decimal import Decimal
from pathlib import Path
from uuid import UUID


SCRIPT = Path(__file__).parents[1] / "catalog" / "export-liquibase-snapshot.py"
SPEC = importlib.util.spec_from_file_location("export_liquibase_snapshot", SCRIPT)
EXPORTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPORTER)


class ExportLiquibaseSnapshotTest(unittest.TestCase):
    def test_rejects_wrong_source_database(self):
        with self.assertRaisesRegex(ValueError, "database identity"):
            EXPORTER.validate_database_identity("ford", "ford_catalog_candidate_20261025")

    def test_rejects_unapproved_archive_sha(self):
        with self.assertRaisesRegex(ValueError, "approved archive SHA"):
            EXPORTER.validate_archive_sha("0" * 64)

    def test_rejects_missing_and_extra_catalog_relations(self):
        expected = {"ford_read.vehicle_search_v1", "ford_capture.price_import_journal"}
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            EXPORTER.validate_relation_inventory(
                {"ford_read.vehicle_search_v1", "ford_read.unreviewed"}, expected
            )

    def test_rejects_missing_or_unreviewed_views(self):
        expected = {"ford_read.catalog_pages_v1", "ford_read.catalog_status_v1"}
        with self.assertRaisesRegex(ValueError, "view inventory mismatch"):
            EXPORTER.validate_view_inventory({"ford_read.catalog_pages_v1", "ford_read.new_view"}, expected)

    def test_rejects_row_count_mismatch(self):
        with self.assertRaisesRegex(ValueError, "row count mismatch"):
            EXPORTER.validate_row_counts(
                {"ford_read.vehicle_search_v1": 38},
                {"ford_read.vehicle_search_v1": 39},
            )

    def test_rejects_content_hash_mismatch(self):
        with self.assertRaisesRegex(ValueError, "content hash mismatch"):
            EXPORTER.validate_content_hashes(
                {"ford_read.vehicle_search_v1": "0" * 64},
                {"ford_read.vehicle_search_v1": "1" * 64},
            )

    def test_hashes_database_json_lines_with_one_newline_per_row(self):
        lines = ['{"id":1}', '{"id":2}']
        expected = hashlib.sha256(b'{"id":1}\n{"id":2}\n').hexdigest()
        self.assertEqual(EXPORTER.canonical_rows_sha256(lines), expected)

    def test_renders_safe_sql_literals_including_json_uuid_numeric_and_vectors(self):
        value = {
            "quote": "Ford's <SUV>",
            "nested": [None, True, Decimal("1.20")],
        }
        self.assertEqual(EXPORTER.sql_literal("Ford's <SUV>"), "'Ford''s <SUV>'")
        self.assertEqual(EXPORTER.sql_literal(value), "'{\"nested\":[null,true,1.2],\"quote\":\"Ford''s <SUV>\"}'::jsonb")
        self.assertEqual(
            EXPORTER.sql_literal(UUID("15000000-0000-0000-0000-000000000001")),
            "'15000000-0000-0000-0000-000000000001'::uuid",
        )
        self.assertEqual(EXPORTER.sql_literal([0.25, -1.0], "vector"), "'[0.25,-1]'::public.vector")

    def test_rejects_non_bge_or_wrong_dimension_embeddings(self):
        with self.assertRaisesRegex(ValueError, "bge-m3"):
            EXPORTER.validate_embedding("other-model", [0.0] * 1024)
        with self.assertRaisesRegex(ValueError, "1,024"):
            EXPORTER.validate_embedding("bge-m3", [0.0] * 3)

    def test_export_sql_is_deterministic_and_omits_generated_tsvector(self):
        columns = ["document_id", "title", "embedding", "search_vector"]
        rows = [
            {"document_id": "b", "title": "B", "embedding": [0.0, 1.0], "search_vector": "ignored"},
            {"document_id": "a", "title": "A", "embedding": [1.0, 0.0], "search_vector": "ignored"},
        ]
        first = EXPORTER.render_insert("ford_catalog.catalog_documents_v1", columns, rows)
        second = EXPORTER.render_insert("ford_catalog.catalog_documents_v1", columns, rows)
        self.assertEqual(first, second)
        self.assertLess(first.index("'a'"), first.index("'b'"))
        self.assertNotIn("search_vector", first)
        self.assertIn("public.vector", first)


if __name__ == "__main__":
    unittest.main()
