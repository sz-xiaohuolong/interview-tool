"""岗位搜索增量表的严格预检，仅使用合成内存数据库。"""
import hashlib
import importlib.util
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('prepare_training', ROOT / 'scripts/prepare-training.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = (ROOT / 'backend/src/main/resources/schema.sql').read_text()
MIGRATION = (ROOT / 'backend/src/main/resources/db/migrations/001-training.sql').read_text()
CHECKSUM = hashlib.sha256(MIGRATION.encode()).hexdigest()
ADDITIONS = {'job_search_favorites', 'job_search_favorite_sources',
             'job_search_jd_links', 'idx_job_search_favorite_sources_favorite'}


class JobSearchPreflightTest(unittest.TestCase):
    def connection(self, legacy=False):
        connection = sqlite3.connect(':memory:')
        self.addCleanup(connection.close)
        for name, sql in MODULE.expected(SCHEMA).items():
            if not legacy or name not in ADDITIONS:
                connection.execute(sql)
        return connection

    def verify(self, connection):
        return MODULE.verify(connection, SCHEMA, MIGRATION, CHECKSUM)

    def test_only_approved_new_tables_may_be_absent(self):
        self.assertFalse(self.verify(self.connection(legacy=True)))
        current = self.connection()
        self.assertFalse(self.verify(current))
        current.execute('DROP TABLE materials')
        with self.assertRaises(ValueError):
            self.verify(current)

    def test_modified_new_table_and_unknown_objects_are_rejected(self):
        for mutation in (
            'CREATE TABLE unknown_extra(id TEXT)',
            'CREATE TRIGGER unknown_write AFTER INSERT ON sessions BEGIN DELETE FROM reports; END',
            'DROP TABLE job_search_jd_links; CREATE TABLE job_search_jd_links(source_key TEXT PRIMARY KEY, material_id TEXT, source_json TEXT, source_content_hash TEXT)'
        ):
            connection = self.connection()
            connection.executescript(mutation)
            with self.assertRaises(ValueError):
                self.verify(connection)


if __name__ == '__main__':
    unittest.main()
