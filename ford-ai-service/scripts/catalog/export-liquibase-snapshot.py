#!/usr/bin/env python3
"""Export the approved Ford catalog source as immutable Liquibase INSERT SQL."""

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from uuid import UUID


APPROVED_DATABASE = "ford_catalog_candidate_20261025"
APPROVED_ARCHIVE_SHA = "a1fe738999b3d1148d70d8cb89b4b8e998bfcdc3f7f6304570f06e8c74c1106d"
APPROVED_ROWS = {
    "ford_capture.price_import_journal": 8,
    "ford_capture.retrieval_document_manifest": 1583,
    "ford_capture.reviewed_fact_manifest": 6,
    "ford_read.catalog_documents_v1": 1882,
    "ford_read.comparison_facts_v2": 2447,
    "ford_read.offers_v2": 10,
    "ford_read.page_links_v1": 0,
    "ford_read.vehicle_facts_v1": 0,
    "ford_read.vehicle_facts_v2": 4887,
    "ford_read.vehicle_images_v1": 0,
    "ford_read.vehicle_search_v1": 39,
    "ford_read.vehicle_variants_v2": 121,
}
APPROVED_ROW_HASHES = {
    "ford_capture.price_import_journal": "36a95a2699ef908ee08bc6bcd4085c12707fc0216cdc68fc1838ad0216cf0b46",
    "ford_capture.retrieval_document_manifest": "e9896d550932a028a0ec52e46e08610dbcf2c6b0a2edc0a50e850ad08a3d6070",
    "ford_capture.reviewed_fact_manifest": "7213b1080c5fdbccde441366c6d20808ba74d73538748ed999d58ef0e6a22321",
    "ford_read.catalog_documents_v1": "b92fe6dff8c4e7fee81f31f072abc441e964aa6f2792ff8b7e4efbdc03836ad9",
    "ford_read.comparison_facts_v2": "ea8f2baf0118e6897817bd6ef2b9d8ce6f11274244745499bd074ce35e94b279",
    "ford_read.offers_v2": "58e1a9b981477c8dd74e103894a9a9881a6b2c2e2cfe35a606ac40f51af40191",
    "ford_read.page_links_v1": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "ford_read.vehicle_facts_v1": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "ford_read.vehicle_facts_v2": "a8eef557c85c91773153358779b879f964716056cbc53a06f0f78c3e291402da",
    "ford_read.vehicle_images_v1": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "ford_read.vehicle_search_v1": "ea3256608245d9cd9675db17fc1d979c794b6b47f10c46ad51a94753980e4475",
    "ford_read.vehicle_variants_v2": "8eefe9dc1b7ee0c360d0623a372e871edbe86b2d9e01888805e3b983951356c0",
}
APPROVED_VIEWS = {
    "ford_read.catalog_pages_v1",
    "ford_read.catalog_status_v1",
    "ford_read.document_provenance_v1",
    "ford_read.promotions_v3",
}
SOURCE_TO_TARGET = {
    **{f"ford_read.{name}": f"ford_catalog.{name}" for name in (
        "catalog_documents_v1", "comparison_facts_v2", "offers_v2", "page_links_v1",
        "vehicle_facts_v1", "vehicle_facts_v2", "vehicle_images_v1", "vehicle_search_v1",
        "vehicle_variants_v2",
    )},
    **{f"ford_capture.{name}": f"ford_catalog.{name}" for name in (
        "price_import_journal", "retrieval_document_manifest", "reviewed_fact_manifest",
    )},
}
IDENTIFIER = re.compile(r"^[a-z_][a-z0-9_]*$")
GENERATED_COLUMNS = {"search_vector"}


def validate_database_identity(actual, expected=APPROVED_DATABASE):
    if actual != expected:
        raise ValueError(f"database identity mismatch: expected {expected}")


def validate_archive_sha(actual):
    if actual != APPROVED_ARCHIVE_SHA:
        raise ValueError("source does not match the approved archive SHA")


def verify_archive(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    value = digest.hexdigest()
    validate_archive_sha(value)
    return value


def validate_relation_inventory(actual, expected=None):
    expected = set(expected or APPROVED_ROWS)
    actual = set(actual)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise ValueError(f"catalog relation inventory mismatch: missing={missing}; extra={extra}")


def validate_view_inventory(actual, expected=None):
    expected = set(expected or APPROVED_VIEWS)
    actual = set(actual)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise ValueError(f"catalog view inventory mismatch: missing={missing}; extra={extra}")


def validate_row_counts(actual, expected=None):
    expected = expected or APPROVED_ROWS
    mismatches = {
        relation: (expected.get(relation), count)
        for relation, count in actual.items()
        if expected.get(relation) != count
    }
    if set(actual) != set(expected):
        raise ValueError("catalog relation inventory mismatch in row counts")
    if mismatches:
        raise ValueError(f"catalog row count mismatch: {mismatches}")


def canonical_rows_sha256(lines):
    digest = hashlib.sha256()
    for line in lines:
        digest.update(line.encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def validate_content_hashes(actual, expected=None):
    expected = expected or APPROVED_ROW_HASHES
    mismatches = {
        relation: {"expected": expected.get(relation), "actual": digest}
        for relation, digest in actual.items()
        if expected.get(relation) != digest
    }
    if set(actual) != set(expected):
        raise ValueError("catalog relation inventory mismatch in content hashes")
    if mismatches:
        raise ValueError(f"catalog content hash mismatch: {mismatches}")


def validate_embedding(model, vector):
    if model != "bge-m3":
        raise ValueError("catalog document embedding model must be bge-m3")
    parsed = parse_vector(vector)
    if len(parsed) != 1024:
        raise ValueError("catalog document embeddings must have exactly 1,024 dimensions")
    if any(not isinstance(value, (int, float, Decimal)) or not float(value) == float(value) for value in parsed):
        raise ValueError("catalog document embedding contains invalid values")


def parse_vector(value):
    if isinstance(value, str):
        text = value.strip()
        if not (text.startswith("[") and text.endswith("]")):
            raise ValueError("invalid pgvector literal")
        return [float(part) for part in text[1:-1].split(",") if part.strip()]
    if isinstance(value, (list, tuple)):
        return list(value)
    raise ValueError("unsupported pgvector value")


def sql_literal(value, type_name=None):
    if value is None:
        return "NULL"
    if type_name == "vector":
        values = parse_vector(value)
        if any(not __import__("math").isfinite(float(item)) for item in values):
            raise ValueError("pgvector contains a non-finite value")
        return quote("[" + ",".join(format(float(item), ".9g") for item in values) + "]") + "::public.vector"
    if isinstance(value, UUID):
        return quote(str(value)) + "::uuid"
    if isinstance(value, (dict, list)):
        return quote(json.dumps(json_normalize(value), sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                                 default=json_default)) + "::jsonb"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float, Decimal)):
        return str(value)
    if isinstance(value, (datetime, date)):
        return quote(value.isoformat()) + ("::timestamptz" if isinstance(value, datetime) else "::date")
    return quote(str(value))


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def json_default(value):
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, (Decimal, UUID)):
        return str(value)
    raise TypeError(f"unsupported JSON value: {type(value).__name__}")


def json_normalize(value):
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    if isinstance(value, dict):
        return {key: json_normalize(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_normalize(item) for item in value]
    return value


def render_insert(target, columns, rows, types=None):
    if not re.fullmatch(r"ford_catalog\.[a-z_][a-z0-9_]*", target):
        raise ValueError("invalid catalog target relation")
    columns = [column for column in columns if column not in GENERATED_COLUMNS]
    if any(not IDENTIFIER.fullmatch(column) for column in columns):
        raise ValueError("invalid SQL column identifier")
    if not rows:
        return ""
    types = dict(types or {})
    if "embedding" in columns:
        types.setdefault("embedding", "vector")
    normalized = sorted(rows, key=lambda row: json.dumps(row, sort_keys=True, default=json_default, separators=(",", ":")))
    rendered = []
    for row in normalized:
        values = ", ".join(sql_literal(row.get(column), types.get(column)) for column in columns)
        rendered.append(f"({values})")
    return (f"INSERT INTO {target} ({', '.join(columns)}) VALUES\n  " +
            ",\n  ".join(rendered) + ";\n")


def source_relations(connection):
    with connection.cursor() as cursor:
        cursor.execute("""
            select table_schema || '.' || table_name
            from information_schema.tables
            where table_schema in ('ford_capture', 'ford_read') and table_type = 'BASE TABLE'
            order by 1
        """)
        return {row[0] for row in cursor.fetchall()}


def source_views(connection):
    with connection.cursor() as cursor:
        cursor.execute("""
            select table_schema || '.' || table_name
            from information_schema.views
            where table_schema in ('ford_capture', 'ford_read')
            order by 1
        """)
        return {row[0] for row in cursor.fetchall()}


def source_content_sha256(connection, relation):
    schema, table = relation.split(".", 1)
    if schema not in {"ford_capture", "ford_read"} or not IDENTIFIER.fullmatch(table):
        raise ValueError("unapproved source relation")
    with connection.cursor() as cursor:
        cursor.execute("""
            select a.attname
            from pg_index i
            cross join lateral unnest(i.indkey) with ordinality key_column(attnum, ordinality)
            join pg_attribute a on a.attrelid = i.indrelid and a.attnum = key_column.attnum
            where i.indrelid = %s::regclass and i.indisprimary
            order by key_column.ordinality
        """, (relation,))
        primary_key = [row[0] for row in cursor.fetchall()]
        order_by = ", ".join(
            f't."{column.replace(chr(34), chr(34) * 2)}"' for column in primary_key
        ) if primary_key else "row_to_json(t)::text"
        cursor.execute(f"select row_to_json(t)::text from {schema}.{table} t order by {order_by}")
        digest = hashlib.sha256()
        while batch := cursor.fetchmany(256):
            for (line,) in batch:
                digest.update(line.encode("utf-8"))
                digest.update(b"\n")
        return digest.hexdigest()


def read_relation(connection, relation):
    schema, table = relation.split(".", 1)
    if schema not in {"ford_capture", "ford_read"} or not IDENTIFIER.fullmatch(table):
        raise ValueError("unapproved source relation")
    with connection.cursor() as cursor:
        cursor.execute("""
            select column_name, udt_name, is_generated
            from information_schema.columns
            where table_schema = %s and table_name = %s
            order by ordinal_position
        """, (schema, table))
        descriptors = cursor.fetchall()
        columns = [name for name, _, generated in descriptors if generated == "NEVER"]
        types = {name: data_type for name, data_type, generated in descriptors
                 if generated == "NEVER" and data_type == "vector"}
        quoted_columns = ", ".join('"' + column.replace('"', '""') + '"' for column in columns)
        cursor.execute(f"select {quoted_columns} from {schema}.{table}")
        raw_rows = cursor.fetchall()
    rows = [dict(zip(columns, values)) for values in raw_rows]
    if relation == "ford_read.catalog_documents_v1":
        for row in rows:
            validate_embedding(row.get("embedding_model"), row.get("embedding"))
    return columns, types, rows


def atomic_write(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(file_descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def export(database_url, source_database, source_archive, output_dir):
    validate_database_identity(source_database)
    source_sha = verify_archive(source_archive)
    try:
        import psycopg
    except ImportError as error:
        raise RuntimeError("psycopg 3 is required for snapshot export") from error

    connection = psycopg.connect(database_url)
    try:
        with connection.cursor() as cursor:
            cursor.execute("begin transaction read only")
            cursor.execute("select current_database()")
            validate_database_identity(cursor.fetchone()[0])
        relations = source_relations(connection)
        validate_relation_inventory(relations)
        validate_view_inventory(source_views(connection))
        counts = {}
        for relation in sorted(relations):
            schema, table = relation.split(".", 1)
            with connection.cursor() as cursor:
                cursor.execute(f"select count(*) from {schema}.{table}")
                counts[relation] = cursor.fetchone()[0]
        validate_row_counts(counts)

        sections = ["-- Approved source SHA-256: " + source_sha, "-- Generated deterministically; do not edit.", ""]
        hashes = {}
        content_hashes = {}
        for relation in sorted(relations):
            columns, types, rows = read_relation(connection, relation)
            content_hashes[relation] = source_content_sha256(connection, relation)
            target = SOURCE_TO_TARGET[relation]
            sql = render_insert(target, columns, rows, types)
            sections.extend([f"-- Source relation: {relation}; rows: {len(rows)}", sql])
            hashes[relation] = hashlib.sha256(sql.encode("utf-8")).hexdigest()
        validate_content_hashes(content_hashes)
        data_sql = "\n".join(sections)
        content_hash = hashlib.sha256(data_sql.encode("utf-8")).hexdigest()
        output_dir = Path(output_dir)
        sql_name = f"catalog-snapshot-{source_sha}.sql"
        manifest = {
            "source_database": source_database,
            "source_archive_sha256": source_sha,
            "row_counts": counts,
            "source_content_sha256": content_hashes,
            "relation_sql_sha256": hashes,
            "data_sql_sha256": content_hash,
        }
        atomic_write(output_dir / sql_name, data_sql)
        atomic_write(output_dir / "manifest.json", json.dumps(manifest, sort_keys=True, indent=2) + "\n")
        return manifest
    finally:
        connection.rollback()
        connection.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--source-database", required=True)
    parser.add_argument("--source-archive", required=True,
                        help="Verified source dump; its SHA-256 must equal the approved archive identity")
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args(argv)
    try:
        result = export(args.database_url, args.source_database, args.source_archive, args.output_dir)
    except Exception as error:
        print(f"Snapshot export refused: {error}", file=sys.stderr)
        return 1
    print(f"Exported {sum(result['row_counts'].values())} approved rows; SQL SHA-256 {result['data_sql_sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
