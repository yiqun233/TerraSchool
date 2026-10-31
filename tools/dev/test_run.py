"""本地启动工具的进程、互斥和秘密输出回归；不连接Docker或业务库。"""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import json
import migrate_wsl as migration
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('local_dev', Path(__file__).with_name('run.py'))
dev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev)


class LocalDevTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=dev.ROOT / '.local')
        self.local = Path(self.directory.name)
        self.patch = patch.object(dev, 'LOCAL', self.local)
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        self.directory.cleanup()

    def test_timeout_kills_descendant_and_redacts_output(self):
        # 后代若遗留会写入标记；不是只断言包装进程退出。
        marker = self.local / 'orphan.txt'
        child = "import time;from pathlib import Path;time.sleep(1);Path(" + repr(str(marker)) + ").write_text('orphan')"
        wrapper = "import subprocess,sys,time;print('secret-value',flush=True);subprocess.Popen([sys.executable,'-c'," + repr(child) + "]);time.sleep(30)"
        with self.assertRaisesRegex(RuntimeError, '超时'):
            dev.command([sys.executable, '-c', wrapper], {'passwords': {'test': 'secret-value'}},
                        timeout=0.3, log='timeout.log')
        time.sleep(1.1)
        self.assertFalse(marker.exists())
        self.assertNotIn('secret-value', (self.local / 'timeout.log').read_text())

    def test_stop_does_not_stop_database_during_prepare(self):
        with dev.operation_lock(), patch.object(dev, 'control', return_value=None), \
                patch.object(dev, 'docker') as docker, patch.object(sys, 'argv', ['run.py', 'stop']):
            with self.assertRaisesRegex(RuntimeError, '未停止数据库'):
                dev.main()
            docker.assert_not_called()

    def test_foreign_resource_is_rejected(self):
        with patch.object(dev, 'docker', return_value='docker'), \
                patch.object(dev, 'command', return_value='[{"Labels":{"org.teraschool.dev":"other"}}]'):
            with self.assertRaisesRegex(RuntimeError, '不属于'):
                dev.inspect_owned('volume', 'fixture', {'environment_id': 'ours'})

    def test_wsl_foreign_database_is_rejected_before_project_sql(self):
        with patch.object(dev, 'wsl_admin', return_value='foreign') as admin:
            with self.assertRaisesRegex(RuntimeError, '标记不匹配'):
                dev.sql({'environment_id': 'ours'}, 'DELETE FROM organization.unit')
            self.assertEqual(admin.call_count, 1)
            self.assertNotIn('DELETE', admin.call_args.args[1])

    def test_wsl_stop_does_not_touch_shared_database_service(self):
        with patch.object(dev, 'control', return_value=None), patch.object(dev, 'wsl_admin') as admin, \
                patch.object(dev, 'docker') as docker, patch.object(dev, 'command') as command, \
                patch.object(sys, 'argv', ['run.py', 'stop']):
            dev.main()
            admin.assert_not_called()
            docker.assert_not_called()
            command.assert_not_called()

    def test_new_wsl_configuration_does_not_require_docker(self):
        with patch.object(dev, 'STATE', self.local / 'environment.json'), patch.object(dev, 'docker') as docker:
            config = dev.configuration()
            self.assertEqual(config['settings']['database_provider'], 'wsl')
            self.assertNotIn('postgres', config['passwords'])
            docker.assert_not_called()

    def test_foreign_wsl_role_is_rejected_without_writes(self):
        with patch.object(dev, 'wsl_admin', side_effect=['18.6', '{"ts_app":"foreign"}']) as admin:
            with self.assertRaisesRegex(RuntimeError, '已有角色不属于'):
                dev.ensure_database({'environment_id': 'ours'})
            self.assertEqual(admin.call_count, 2)
            self.assertTrue(all('SELECT' in call.args[1] for call in admin.call_args_list))

    def test_migration_resumes_prepare_after_state_has_switched(self):
        backups = self.local / 'backups'
        backups.mkdir()
        current = {'workspace': dev.WORKSPACE, 'environment_id': 'ours', 'settings': dev.SETTINGS}
        source = dict(current, settings={'container_name': 'fixture-old'})
        state = self.local / 'environment.json'
        dev.write_json(state, current)
        dev.write_json(backups / 'docker-environment.json', source)
        dev.write_json(backups / 'wsl-migration-progress.json', {
            'environment_id': 'ours', 'data_verified': True, 'tables': {'fixture': {'count': 1}}})
        with patch.object(migration, 'dev', dev), patch.object(dev, 'STATE', state), \
                patch.object(dev, 'control', return_value=None), patch.object(dev, 'prepare') as prepare, \
                patch.object(dev, 'inspect_owned'), patch.object(dev, 'docker', return_value='docker'), \
                patch.object(dev, 'command') as command:
            migration.migrate()
            prepare.assert_called_once_with(current)
            command.assert_called_once_with(['docker', 'stop', 'fixture-old'], source)
        self.assertEqual(json.loads((backups / 'wsl-migration-result.json').read_text())['status'], 'passed')

    def test_failed_vectordb_backup_never_drops_database(self):
        with patch.object(migration, 'dev', dev), patch.object(dev, 'wsl_admin', side_effect=['1','0']) as admin, \
                patch.object(migration, 'binary_command', side_effect=RuntimeError('backup failed')):
            with self.assertRaisesRegex(RuntimeError, 'backup failed'):
                migration.delete_unused_vectordb({})
            self.assertFalse(any('DROP' in call.args[1] for call in admin.call_args_list))


if __name__ == '__main__':
    unittest.main()
