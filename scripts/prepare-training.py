#!/usr/bin/env python3
"""仅在明确维护模式下备份整个目录并迁移，不隐式操作默认数据目录。"""
import argparse
import hashlib
import re
import shutil
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


def normalized(sql):
    return ''.join(part if part.startswith("'") else re.sub(r"\s+", "", re.sub(r"IF\s+NOT\s+EXISTS\s+", "", part, flags=re.I)).lower()
                   for part in re.findall(r"'(?:''|[^'])*'|[^']+", sql))


def expected(schema):
    result = {}
    for sql in schema.split(";"):
        match = re.search(r"CREATE\s+(?:UNIQUE\s+)?(?:TABLE|INDEX)\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", sql, re.I)
        if match:
            result[match[1]] = sql
    return result


def verify(connection, legacy, migration, checksum):
    actual = dict(connection.execute("SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL"))
    applied = 'v2_schema_migrations' in actual
    if applied:
        if connection.execute('SELECT version,checksum FROM v2_schema_migrations').fetchall() != [(1, checksum)]:
            raise ValueError('V2_SCHEMA_UNSUPPORTED：未知版本或校验和不一致')
    elif any(name.startswith('v2_') for name in actual):
        raise ValueError('V2_SCHEMA_UNSUPPORTED：未登记的训练结构')
    schemas = expected(legacy) | (expected(migration) if applied else {})
    if not set(actual).issubset(schemas):
        raise ValueError('V2_SCHEMA_UNSUPPORTED：存在未知结构对象')
    if actual:
        for name, sql in schemas.items():
            # 已批准的独立附加表和索引可由新版启动补建，其他旧表和已有约束仍须完整核验。
            if name in {'project_repositories', 'job_applications', 'idx_job_applications_list'} and name not in actual:
                continue
            if name not in actual or normalized(actual[name]) != normalized(sql):
                raise ValueError('V2_SCHEMA_UNSUPPORTED：已存在结构不符合已知约束')
    if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
        raise ValueError('数据库完整性检查失败，未迁移')
    return applied


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, required=True)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--confirm-maintenance', action='store_true', help='确认所有应用与写入进程已停止')
    args = parser.parse_args()
    if not args.confirm_maintenance:
        parser.error('请先停止应用，并明确传入 --confirm-maintenance')
    data, backup = args.data_dir.resolve(), args.backup.resolve()
    if backup.exists() or data == backup or data in backup.parents or backup in data.parents:
        parser.error('备份必须是不存在的独立目录，不能与数据目录相互包含')
    source = Path(__file__).resolve().parents[1] / 'backend/src/main/resources'
    legacy = (source / 'schema.sql').read_text()
    migration = (source / 'db/migrations/001-training.sql').read_text()
    checksum = hashlib.sha256(migration.encode()).hexdigest()
    database = data / 'interview.db'
    if database.exists():
        with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as connection:
            applied = verify(connection, legacy, migration, checksum)
        if applied:
            print('已迁移且校验一致，无操作。')
            return
        shutil.copytree(data, backup)
        with sqlite3.connect((backup / 'interview.db').as_uri() + '?mode=ro', uri=True) as connection:
            if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise ValueError('备份不可读，停止迁移')
    else:
        if data.exists():
            shutil.copytree(data, backup)
        else:
            backup.mkdir(parents=True)
            (backup / 'EMPTY_DIRECTORY').write_text('迁移前数据目录不存在。\n')
    data.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database, isolation_level=None, timeout=5) as connection:
        connection.execute('PRAGMA foreign_keys=ON')
        connection.execute('BEGIN EXCLUSIVE')
        try:
            verify(connection, legacy, migration, checksum)
            for sql in (legacy + '\n' + migration).split(';'):
                if sql.strip():
                    connection.execute(sql)
            connection.execute('INSERT INTO v2_schema_migrations VALUES(1,?,?)',
                               (checksum, datetime.now(timezone.utc).isoformat()))
            if connection.execute('PRAGMA foreign_key_check').fetchall():
                raise ValueError('外键检查失败')
            connection.execute('COMMIT')
        except BaseException:
            connection.execute('ROLLBACK')
            raise
    print('专项训练结构准备完成，旧记录未回写；完整目录备份已保留。')


if __name__ == '__main__':
    try:
        main()
    except (sqlite3.Error, ValueError, OSError):
        raise SystemExit('迁移被拒绝或失败；未写成功台账。请核对结构、权限和维护状态，保留备份。')
