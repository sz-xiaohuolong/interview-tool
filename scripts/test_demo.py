"""验证演示数据初始化的隔离、重复启动与失败保护。"""
import json
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class DemoDataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.data = Path(self.temporary.name).resolve() / 'demo'

    def seed(self, *extra):
        return subprocess.run([sys.executable, str(ROOT / 'scripts/demo-data.py'),
                               '--data-dir', str(self.data), *extra], capture_output=True, text=True)

    def test_first_start_has_fictional_materials_reports_and_training(self):
        result = self.seed()
        self.assertEqual(result.returncode, 0, result.stderr)
        with sqlite3.connect(self.data / 'interview.db') as connection:
            kinds = {row[0] for row in connection.execute('SELECT kind FROM materials')}
            self.assertEqual(kinds, {'RESUME', 'PROJECT', 'JD'})
            self.assertGreaterEqual(connection.execute('SELECT count(*) FROM materials').fetchone()[0], 5)
            self.assertEqual(connection.execute('PRAGMA foreign_key_check').fetchall(), [])
            self.assertEqual(connection.execute('SELECT count(*) FROM v2_stories').fetchone()[0], 2)
            self.assertGreaterEqual(connection.execute('SELECT count(*) FROM v2_practice_cases').fetchone()[0], 2)
        sessions = [json.loads(path.read_text()) for path in (self.data / 'v3/sessions').glob('*.json')]
        self.assertTrue(any(s['status'] == 'REPORT_READY' and s['report']['findings'] for s in sessions))
        self.assertTrue(all(s['demo'] for s in sessions))
        self.assertFalse((self.data / 'model-config.json').exists())

    def test_restart_preserves_edits_deleted_samples_and_model_configuration(self):
        self.assertEqual(self.seed().returncode, 0)
        with sqlite3.connect(self.data / 'interview.db') as connection:
            connection.execute("UPDATE materials SET name='我的演示修改'")
        sample = next((self.data / 'v3/sessions').glob('*.json'))
        sample.unlink()
        config = self.data / 'model-config.json'
        config.write_text('{"演示配置":"保留"}')
        self.assertEqual(self.seed().returncode, 0)
        self.assertFalse(sample.exists())
        self.assertEqual(config.read_text(), '{"演示配置":"保留"}')
        with sqlite3.connect(self.data / 'interview.db') as connection:
            self.assertEqual(connection.execute('SELECT DISTINCT name FROM materials').fetchall(), [('我的演示修改',)])

    def test_refuses_unmarked_nonempty_directory_without_changing_files(self):
        self.data.mkdir()
        private = self.data / 'interview.db'
        private.write_bytes('私人数据'.encode())
        self.assertNotEqual(self.seed().returncode, 0)
        self.assertEqual(list(self.data.iterdir()), [private])
        self.assertEqual(private.read_bytes(), '私人数据'.encode())

    def test_refuses_symlink_directory_and_keeps_target_untouched(self):
        target = Path(self.temporary.name) / 'private'
        target.mkdir()
        self.data.symlink_to(target, target_is_directory=True)
        self.assertNotEqual(self.seed().returncode, 0)
        self.assertEqual(list(target.iterdir()), [])

    def test_busy_port_stops_before_creating_demo_data(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            result = self.seed('--check-ports', str(listener.getsockname()[1]))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.data.exists())

    def test_just_stopped_port_can_be_reused_without_waiting_for_tcp_timeout(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            port = listener.getsockname()[1]
            with socket.create_connection(('127.0.0.1', port)) as client:
                peer, _ = listener.accept()
                peer.close()
                client.recv(1)
        result = self.seed('--check-ports', str(port))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_launcher_ignores_daily_configuration_and_cleans_up_children(self):
        # 用可控进程代替启动依赖，验证实际脚本传出的配置与退出行为。
        project = Path(self.temporary.name).resolve() / 'project'
        (project / 'scripts').mkdir(parents=True)
        (project / 'frontend/node_modules').mkdir(parents=True)
        (project / '.runtime/demo').mkdir(parents=True)
        launcher = project / 'scripts/demo.sh'
        launcher.write_bytes((ROOT / 'scripts/demo.sh').read_bytes())
        (project / 'scripts/demo-data.py').write_text('')
        private = project / '.env.local'
        private.write_text('touch "$HOME/不应执行"\n')
        commands = project / 'bin'
        commands.mkdir()
        real_python = sys.executable
        stub = '''#!{python}
import json, os, signal, sys, time
from pathlib import Path
name=Path(sys.argv[0]).name
allowed=['DATA_DIR','PORT','WEB_PORT','DEMO_MODE','TRAINING_ENABLED','LLM_PROVIDER','LLM_API_KEY','API_PROXY_TARGET','SPRING_APPLICATION_JSON','JAVA_TOOL_OPTIONS']
Path(os.environ['HOME'],name+'.json').write_text(json.dumps(dict({{k:os.environ.get(k) for k in allowed}}, arguments=sys.argv[1:])))
if name=='mvn': sys.exit(0)
def stop(*args):
    Path(os.environ['HOME'],name+'.stopped').touch()
    sys.exit(0)
signal.signal(signal.SIGTERM,stop)
while True: time.sleep(.05)
'''.format(python=real_python)
        for command in ['mvn', 'npm', 'java', 'node']:
            path = commands / command
            path.write_text(stub)
            path.chmod(0o755)
        child_env = dict(os.environ, PATH=str(commands) + ':' + os.environ['PATH'], HOME=str(project), JAVA_HOME=str(project),
                         DATA_DIR='/private/daily', PORT='8080', LLM_PROVIDER='mock', LLM_API_KEY='私人配置不应继承',
                         API_PROXY_TARGET='http://127.0.0.1:8080', SPRING_APPLICATION_JSON='私人覆盖', JAVA_TOOL_OPTIONS='私人覆盖')
        process = subprocess.Popen(['bash', str(launcher)], cwd=project, env=child_env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            import time
            deadline = time.monotonic() + 15
            while not all((project / (name + '.json')).exists() for name in ['mvn', 'java', 'node']) and process.poll() is None and time.monotonic() < deadline:
                time.sleep(.05)
            self.assertIsNone(process.poll(), '演示脚本在启动前意外退出')
            for command in ['mvn', 'java', 'node']:
                actual = json.loads((project / (command + '.json')).read_text())
                self.assertEqual(actual['DATA_DIR'], str(project / '.runtime/demo/data'))
                self.assertEqual(actual['PORT'], '8081')
                self.assertEqual(actual['WEB_PORT'], '5174')
                self.assertEqual(actual['API_PROXY_TARGET'], 'http://127.0.0.1:8081')
                self.assertEqual(actual['LLM_PROVIDER'], 'openai')
                self.assertEqual(actual['LLM_API_KEY'], '')
                self.assertIsNone(actual['SPRING_APPLICATION_JSON'])
                self.assertIsNone(actual['JAVA_TOOL_OPTIONS'])
                if command == 'mvn':
                    self.assertIn('-Dinterview.build-directory=' + str(project / '.runtime/demo/backend-target'), actual['arguments'])
                if command == 'java':
                    self.assertEqual(actual['arguments'], ['-jar', str(project / '.runtime/demo/backend-target/interview-coach-1.0.0.jar')])
            self.assertFalse((project / '不应执行').exists())
            self.assertEqual(private.read_text(), 'touch "$HOME/不应执行"\n')
        finally:
            process.terminate()
            process.communicate(timeout=10)
        self.assertTrue((project / 'java.stopped').exists())
        self.assertTrue((project / 'node.stopped').exists())


if __name__ == '__main__':
    unittest.main()
