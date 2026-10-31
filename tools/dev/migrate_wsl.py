"""一次性把本工具旧Docker演示库迁至WSL；备份及核对通过后才切换本机配置。"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import time

import run as dev


def binary_command(args, config, destination=None, source=None):
    """备份/恢复只经二进制文件和管道，不把数据库内容或散列输出到终端。"""
    output = destination.open('wb') if destination else subprocess.PIPE
    input_stream = source.open('rb') if source else subprocess.DEVNULL
    process = None
    try:
        process = subprocess.Popen(args, stdin=input_stream, stdout=output, stderr=subprocess.PIPE,
            start_new_session=dev.os.name != 'nt',
            creationflags=subprocess.CREATE_NO_WINDOW if dev.os.name == 'nt' else 0)
        try:
            _, error = process.communicate(timeout=300)
        except subprocess.TimeoutExpired:
            dev.stop_tree(process)
            process.communicate(timeout=15)
            raise RuntimeError('备份/恢复超时，旧数据保留。')
        if process.returncode:
            (dev.LOCAL / 'migration-error.log').write_text(
                dev.redact(error.decode('utf-8', errors='replace'), config), encoding='utf-8')
            raise RuntimeError('备份/恢复失败，诊断位于受限目录.local/dev/migration-error.log。')
    finally:
        if process:
            dev.stop_tree(process)
        if destination:
            output.close()
        if source:
            input_stream.close()


def source_sql(config, text):
    name = config['settings']['container_name']
    dev.inspect_owned('container', name, config)
    return dev.command([dev.docker(), 'exec', '-i', name, 'psql', '-X', '-qAt', '-v',
                        'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'teraschool_dev'],
                       config, input_text=text, timeout=60)


def snapshot(query):
    tables = json.loads(query("SELECT coalesce(json_agg(json_build_array(schemaname,tablename) "
        "ORDER BY schemaname,tablename),'[]'::json) FROM pg_tables WHERE schemaname "
        "IN ('public','identity','organization','infra','audit','dev');"))
    result = {}
    for schema, table in tables:
        identifier = '"' + schema.replace('"','""') + '"."' + table.replace('"','""') + '"'
        text = "SET TIME ZONE 'UTC'; SELECT json_build_object('count',count(*),'digest'," \
            "md5(coalesce(string_agg(md5(row_to_json(t)::text),'' ORDER BY md5(row_to_json(t)::text) COLLATE \"C\"),''))) FROM " + identifier + ' t;'
        result[schema + '.' + table] = json.loads(query(text))
    return result


def migrate():
    old = json.loads(dev.STATE.read_text(encoding='utf-8'))
    if old['workspace'] != dev.WORKSPACE:
        raise RuntimeError('旧配置不属于当前目录。')
    if old['settings'] == dev.SETTINGS:
        backups = dev.LOCAL / 'backups'
        completed = backups / 'wsl-migration-result.json'
        if completed.exists():
            record = json.loads(completed.read_text(encoding='utf-8'))
            if record.get('status') != 'passed' or record.get('workspace', dev.WORKSPACE) != dev.WORKSPACE \
                    or record.get('environment_id', old['environment_id']) != old['environment_id']:
                raise RuntimeError('迁移完成记录不匹配。')
        else:
            progress = json.loads((backups / 'wsl-migration-progress.json').read_text(encoding='utf-8'))
            source = json.loads((backups / 'docker-environment.json').read_text(encoding='utf-8'))
            if progress.get('environment_id') != old['environment_id'] or source['workspace'] != dev.WORKSPACE \
                    or source['environment_id'] != old['environment_id'] or not progress.get('data_verified'):
                raise RuntimeError('迁移恢复记录不匹配，拒绝重做或覆盖。')
            if dev.control('status'):
                raise RuntimeError('请先停止前后端，再继续未完成的迁移准备。')
            finish(old, source, progress['tables'])
        print('本机配置已切换到WSL，不重复导入。')
        return old
    settings = old['settings']
    if settings.get('database_name') != 'teraschool_dev' or settings.get('container_name') != 'teraschool-dev-postgres':
        raise RuntimeError('不是本工具支持的旧开发环境，拒绝导入。')
    if dev.control('status'):
        raise RuntimeError('请先停止前后端，避免迁移期间继续写入。')
    detail = dev.inspect_owned('container', settings['container_name'], old)
    dev.inspect_owned('volume', settings['volume_name'], old)
    if not detail['State']['Running']:
        dev.command([dev.docker(), 'start', settings['container_name']], old)
    for _ in range(40):
        try:
            source_sql(old, 'SELECT 1;')
            break
        except RuntimeError:
            time.sleep(0.5)
    else:
        raise RuntimeError('旧开发库未就绪。')
    target = dict(old, settings=dev.SETTINGS)
    backups = dev.LOCAL / 'backups'
    backups.mkdir(exist_ok=True)
    dev.write_json(backups / 'docker-environment.json', old)
    print('[1/4] 备份原Docker演示库', flush=True)
    before = snapshot(lambda query: source_sql(old, query))
    dump = backups / 'teraschool_dev-docker.dump'
    binary_command([dev.docker(), 'exec', settings['container_name'], 'pg_dump', '-U', 'postgres',
                    '-d', 'teraschool_dev', '-Fc'], old, destination=dump)
    if snapshot(lambda query: source_sql(old, query)) != before:
        raise RuntimeError('备份期间源库发生变化，未切换连接，请停止写入后重试。')
    dev.write_json(backups / 'docker-manifest.json', {'tables': before,
        'sha256': hashlib.sha256(dump.read_bytes()).hexdigest()})
    print('[2/4] 准备WSL专用库与受限角色', flush=True)
    dev.ensure_database(target)
    existing = snapshot(lambda query: dev.sql(target, query))
    if existing and existing != before:
        raise RuntimeError('WSL项目库已有不同数据，不覆盖；原Docker库与备份均保留。')
    if not existing:
        binary_command(dev.wsl_args('pg_restore', '-p', str(dev.SETTINGS['database_port']),
            '--exit-on-error', '--single-transaction', '-d', 'teraschool_dev'), target, source=dump)
    print('[3/4] 核对全部项目表行数及内容摘要', flush=True)
    after = snapshot(lambda query: dev.sql(target, query))
    if after != before:
        raise RuntimeError('迁移后数据核对不一致，未切换连接；原库和备份保留。')
    target['passwords'] = {k:v for k,v in old['passwords'].items() if k != 'postgres'}
    dev.write_json(backups / 'wsl-migration-progress.json', {'environment_id': target['environment_id'],
        'data_verified': True, 'tables': after})
    dev.write_json(dev.STATE, target)
    finish(target, old, after)
    return target


def finish(target, source, tables):
    """核验数据后可续跑的准备阶段；不重新恢复或覆盖已经切换的目标库。"""
    print('[4/4] 切换应用配置并重新准备构建', flush=True)
    dev.prepare(target)
    name = source['settings']['container_name']
    dev.inspect_owned('container', name, source)
    dev.command([dev.docker(), 'stop', name], source)
    dev.write_json(dev.LOCAL / 'backups/wsl-migration-result.json', {'status': 'passed',
        'workspace': dev.WORKSPACE, 'environment_id': target['environment_id'],
        'source': 'Docker:55432/teraschool_dev', 'target': 'Ubuntu:5432/teraschool_dev',
        'tables': tables, 'source_container_stopped': True})
    print('切换完成，原账号、组织与审计数据保留；旧容器和卷未删除。', flush=True)


def delete_unused_vectordb(config):
    if dev.wsl_admin(config, "SELECT count(*) FROM pg_database WHERE datname='vectordb';") == '0':
        print('WSL vectordb已不存在，无需删除。')
        return
    active = dev.wsl_admin(config, "SELECT count(*) FROM pg_stat_activity WHERE datname='vectordb';")
    backup = dev.LOCAL / 'backups/vectordb-wsl.dump'
    backup.parent.mkdir(exist_ok=True)
    binary_command(dev.wsl_args('pg_dump', '-p', str(dev.SETTINGS['database_port']), '-d', 'vectordb', '-Fc'),
                   config, destination=backup)
    digest = hashlib.sha256(backup.read_bytes()).hexdigest()
    # 此显式清理入口对应用户“该库无用，可以删除”的授权，FORCE仅作用于该库连接。
    dev.wsl_admin(config, 'DROP DATABASE vectordb WITH (FORCE);')
    dev.write_json(dev.LOCAL / 'backups/vectordb-deletion.json', {'database': 'Ubuntu/vectordb',
        'backup': backup.name, 'sha256': digest, 'deleted': True, 'connections_at_backup': int(active),
        'authorization': '用户明确说明WSL中的vectordb无用，可以删除；未删除vecuser角色。'})
    print('已备份并删除WSL vectordb；其他库及vecuser角色保留。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--delete-unused-vectordb', action='store_true',
                        help='仅在用户明确授权时，备份后删除WSL中名为vectordb的库')
    args = parser.parse_args()
    dev.private_directory()
    with dev.operation_lock():
        config = migrate()
        if args.delete_unused_vectordb:
            delete_unused_vectordb(config)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        config = json.loads(dev.STATE.read_text(encoding='utf-8')) if dev.STATE.exists() else None
        print('错误：' + dev.redact(str(error), config))
        raise SystemExit(1)
