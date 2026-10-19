"""多轮发现试跑的离线协议与本机合成 HTTP 边界测试。"""
import copy
import fcntl
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'scripts/interview-continuity-eval.py'


class ContinuityTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), '尚未实现独立12次预算的多轮发现运行器')
        spec = importlib.util.spec_from_file_location('continuity_eval', SCRIPT)
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)
        self.suite = self.runner.load_suite()
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name) / '.evidence'
        self.out.mkdir()
        self.ledger = self.out / 'shared/ledger.json'
        self.requests = []

    def server(self, transform=None):
        received = self.requests
        class Handler(BaseHTTPRequestHandler):
            def do_POST(inner):
                body = json.loads(inner.rfile.read(int(inner.headers['Content-Length'])))
                received.append(body)
                payload = json.loads(body['messages'][1]['content'])
                content = {
                    'action': 'NEXT', 'question': '', 'sourceIds': [],
                    'diagnosis': {'category': 'UNCERTAIN', 'coveredPoints': [],
                                  'answerQuote': payload['answer'][:16], 'gap': '',
                                  'nextGoal': '', 'reason': '本机合成协议样本，等待人工判断'},
                }
                envelope = {'model': 'fixture-model', 'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(content, ensure_ascii=False)}}]}
                if transform:
                    envelope = transform(envelope, payload)
                raw = json.dumps(envelope, ensure_ascii=False).encode()
                inner.send_response(200)
                inner.send_header('Content-Type', 'application/json')
                inner.send_header('Content-Length', str(len(raw)))
                inner.end_headers()
                try:
                    inner.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(inner, *_):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return {'provider': 'openai', 'baseUrl': f'http://127.0.0.1:{server.server_port}',
                'apiKey': 'synthetic-local-key', 'model': 'fixture-model',
                'fastModel': '', 'fastReasoningEffort': 'low', 'preset': 'CUSTOM'}

    def prepare(self, config=None, name='run'):
        directory = self.out / name
        manifest = self.runner.prepare_run(self.suite, directory, config)
        return directory, manifest

    def pack(self, directory):
        with patch.object(self.runner, 'default_ledger', return_value=self.ledger):
            return self.runner.export_review_pack(directory)

    def test_default_is_offline_and_never_loads_connection(self):
        with patch.object(self.runner.shared, 'load_config', side_effect=AssertionError('离线不能读取连接')):
            self.assertEqual(0, self.runner.main([]))
        self.assertEqual([], list(self.out.iterdir()))

    def test_white_list_excludes_oracle_labels_and_future_turns(self):
        case = copy.deepcopy(self.suite['cases'][0])
        for key in ('id', 'title', 'scenario', 'oracle'):
            case[key] = '不可发送的人工哨兵'
        case['dialogue']['context'] = '不可发送的人工哨兵'
        for t in case['dialogue']['turns'][2:]:
            t['question'] = t['answer'] = '不可发送的未来问答'
        payload = self.runner.build_input(case, self.suite, 2)
        raw = json.dumps(payload, ensure_ascii=False)
        self.assertNotIn('不可发送', raw)
        self.assertEqual(4, len(payload['messages']))
        self.assertEqual('CONT-C01-t02-q', payload['currentQuestion']['questionId'])
        self.assertEqual([], payload['diagnostics'])
        self.assertEqual({}, payload['summary'])

    def test_quick_and_comprehensive_have_distinct_stage_fields(self):
        quick = next(c for c in self.suite['cases'] if c['dialogue']['sessionType'] == 'QUICK')
        payload = self.runner.build_input(quick, self.suite, 5)
        self.assertEqual('QUICK', payload['sessionType'])
        self.assertFalse({'stage', 'experienceVersion', 'reduceFollowups', 'mainProject'} & payload.keys())
        comprehensive = self.runner.build_input(self.suite['cases'][0], self.suite, 5)
        self.assertEqual('TECHNICAL', comprehensive['stage'])
        self.assertEqual({'hypothetical': True, 'brief': ''}, comprehensive['mainProject'])
        self.assertNotIn('plannedQuestionCount', comprehensive)

    def test_preparation_binds_twelve_ordered_followups_without_secrets(self):
        config = self.server()
        directory, run = self.prepare(config)
        self.assertEqual(['CONT-C01', 'CONT-Q01', 'CONT-C02', 'CONT-Q02'], [t['caseId'] for t in run['tasks'][:4]])
        self.assertEqual(12, len(run['tasks']))
        self.assertTrue(all(t['stage'] == 'FOLLOWUP' and t['checkpointTurn'] == 5 for t in run['tasks']))
        self.assertEqual(30, run['parameters']['FOLLOWUP']['timeoutSeconds'])
        self.assertEqual(31, run['parameters']['wholeDeadlineSeconds'])
        self.assertEqual(800, run['parameters']['FOLLOWUP']['max_tokens'])
        stored = (directory / 'run.json').read_text()
        self.assertNotIn(config['apiKey'], stored)
        self.assertNotIn(config['baseUrl'], stored)
        self.assertEqual([], self.requests)
        with self.assertRaisesRegex(ValueError, '已存在'):
            self.runner.prepare_run(self.suite, directory, config)

    def test_changed_payload_is_rejected_before_reserving_or_sending(self):
        config = self.server()
        directory, run = self.prepare(config)
        run['tasks'][0]['input']['answer'] = '被修改的输入'
        self.runner.shared.write_json(directory / 'run.json', run)
        with self.assertRaisesRegex(ValueError, '输入|协议'):
            self.runner.run_real(directory, config, ledger_path=self.ledger)
        self.assertFalse(self.ledger.exists())
        self.assertEqual([], self.requests)

    def test_twelve_attempts_are_shared_across_output_directories(self):
        config = self.server()
        directory, _ = self.prepare(config)
        outcome = self.runner.run_real(directory, config, ledger_path=self.ledger)
        self.assertEqual(12, outcome['newAttempts'])
        self.assertEqual(12, len(self.requests))
        other, _ = self.prepare(config, 'other')
        reused = self.runner.run_real(other, config, ledger_path=self.ledger)
        self.assertEqual(0, reused['newAttempts'])
        self.assertEqual(12, reused['reusedResults'])
        self.assertEqual(12, len(self.runner.shared.read_json(self.ledger)['attempts']))
        changed = dict(config, fastReasoningEffort='medium')
        altered, _ = self.prepare(changed, 'altered')
        with self.assertRaisesRegex(ValueError, '12|协议|预算'):
            self.runner.run_real(altered, changed, ledger_path=self.ledger)
        self.assertEqual(12, len(self.requests))

    def test_first_invalid_json_stops_batch_even_with_new_identity(self):
        def invalid(envelope, _):
            envelope['choices'][0]['message']['content'] = '{不完整的原始结果'
            return envelope
        config = self.server(invalid)
        directory, run = self.prepare(config)
        with self.assertRaisesRegex(ValueError, '停批'):
            self.runner.run_real(directory, config, ledger_path=self.ledger)
        ledger = self.runner.shared.read_json(self.ledger)
        self.assertEqual('STOPPED', ledger['state'])
        self.assertEqual(1, len(ledger['attempts']))
        record = self.runner.shared.read_json(directory / 'results' / (run['tasks'][0]['taskHash'] + '.json'))
        self.assertEqual('{不完整的原始结果', record['outputText'])
        self.assertIn('rawResponse', record)
        changed = dict(config, fastReasoningEffort='high')
        other, _ = self.prepare(changed, 'different')
        with self.assertRaisesRegex(ValueError, '停批|停止'):
            self.runner.run_real(other, changed, ledger_path=self.ledger)
        self.assertEqual(1, len(self.requests))

    def test_missing_diagnosis_stops_current_strategy(self):
        def missing(envelope, _):
            envelope['choices'][0]['message']['content'] = '{"action":"NEXT","question":"","sourceIds":[]}'
            return envelope
        config = self.server(missing)
        directory, _ = self.prepare(config)
        with self.assertRaisesRegex(ValueError, '诊断|停批'):
            self.runner.run_real(directory, config, ledger_path=self.ledger)
        self.assertEqual(1, len(self.requests))

    def test_interruption_is_counted_and_blocks_later_runs(self):
        config = self.server()
        directory, _ = self.prepare(config)
        with patch.object(self.runner.shared, 'call_once', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.runner.run_real(directory, config, ledger_path=self.ledger)
        ledger = self.runner.shared.read_json(self.ledger)
        self.assertEqual(1, len(ledger['attempts']))
        self.assertEqual('INTERRUPTED', ledger['attempts'][0]['status'])
        with self.assertRaisesRegex(ValueError, '停批|停止'):
            self.runner.run_real(directory, config, ledger_path=self.ledger)
        self.assertEqual([], self.requests)

    def test_unfinished_reservation_cannot_be_skipped(self):
        config = self.server()
        directory, run = self.prepare(config)
        self.ledger.parent.mkdir()
        self.runner.shared.write_json(self.ledger, {
            'schema': self.runner.LEDGER_SCHEMA, 'limit': 12, 'state': 'ACTIVE',
            'attempts': [{'number': 1, 'status': 'RESERVED', 'taskHash': run['tasks'][0]['taskHash']}],
        })
        with self.assertRaisesRegex(ValueError, '中断|未完成|停批'):
            self.runner.run_real(directory, config, ledger_path=self.ledger)
        self.assertEqual([], self.requests)

    def test_concurrent_second_executor_is_rejected_before_network(self):
        config = self.server()
        directory, _ = self.prepare(config)
        self.ledger.parent.mkdir()
        with open(self.ledger.with_suffix('.lock'), 'a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(ValueError, '执行|并发'):
                self.runner.run_real(directory, config, ledger_path=self.ledger)
        self.assertEqual([], self.requests)
        self.assertFalse(self.ledger.exists())

    def test_partial_usage_and_production_behavior_remain_unknown(self):
        def partial(envelope, _):
            envelope['usage'] = {'prompt_tokens': 13}
            return envelope
        config = self.server(partial)
        directory, run = self.prepare(config)
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        record = self.runner.shared.read_json(directory / 'results' / (run['tasks'][0]['taskHash'] + '.json'))
        self.assertEqual({'inputTokens': 13, 'outputTokens': None, 'totalTokens': None}, record['actualUsage'])
        self.assertIsNone(record['effectiveAction'])
        self.assertIsNone(record['businessElapsedMs'])
        self.assertIsNone(record['fallbackTriggered'])
        self.assertEqual('NEXT', record['mechanicalAction'])
        self.assertGreaterEqual(record['transportElapsedMs'], 0)

    def test_whole_request_deadline_interrupts_a_real_slow_local_response(self):
        def slow(envelope, _):
            time.sleep(0.25)
            return envelope
        config = self.server(slow)
        payload = self.runner.build_input(self.suite['cases'][0], self.suite, 5)
        settings = self.runner.parameters(config)['FOLLOWUP']
        start = time.monotonic()
        with self.assertRaisesRegex(ValueError, '截止|超时'):
            with self.runner.deadline(0.05):
                self.runner.shared.call_once(config, settings, '合成边界测试', payload, {})
        self.assertLess(time.monotonic() - start, 0.2)
        self.assertEqual(1, len(self.requests))

    def test_partial_review_pack_preserves_manual_edits(self):
        config = self.server()
        directory, _ = self.prepare(config)
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        self.pack(directory)
        text = (directory / 'review.md').read_text()
        self.assertIn('CONT-C01', text)
        self.assertIn('未执行', text)
        scores = self.runner.shared.read_json(directory / 'scores.json')
        self.assertFalse(scores['confirmedByHuman'])
        self.assertEqual('未执行', scores['cases'][1]['status'])
        scores['cases'][0]['reason'] = '人工尚未填写，先保留此备注'
        self.runner.shared.write_json(directory / 'scores.json', scores)
        self.pack(directory)
        self.assertEqual(scores, self.runner.shared.read_json(directory / 'scores.json'))

    def test_review_pack_rejects_results_edited_after_execution(self):
        config = self.server()
        directory, run = self.prepare(config)
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        path = directory / 'results' / (run['tasks'][0]['taskHash'] + '.json')
        record = self.runner.shared.read_json(path)
        record['outputText'] = '被手工替换的模型输出'
        self.runner.shared.write_json(path, record)
        with self.assertRaisesRegex(ValueError, '摘要|账本'):
            self.pack(directory)

    def test_new_results_leave_old_human_annotations_explicitly_stale(self):
        config = self.server()
        directory, _ = self.prepare(config)
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        self.pack(directory)
        gate = self.runner.shared.read_json(directory / 'human-gate.json')
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        outcome = self.pack(directory)
        self.assertTrue(outcome.get('annotationsStale', False))
        self.assertEqual(gate, self.runner.shared.read_json(directory / 'human-gate.json'))

    def test_confirmed_old_results_cannot_be_repackaged_as_current(self):
        config = self.server()
        directory, _ = self.prepare(config)
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        self.pack(directory)
        gate = self.runner.shared.read_json(directory / 'human-gate.json')
        gate['confirmedByHuman'] = True
        self.runner.shared.write_json(directory / 'human-gate.json', gate)
        before = (directory / 'review.md').read_bytes()
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        with self.assertRaisesRegex(ValueError, '人工|确认|过期'):
            self.pack(directory)
        self.assertEqual(before, (directory / 'review.md').read_bytes())

    def test_batch_uses_the_prompt_snapshot_verified_before_first_send(self):
        run_folder = self.out / 'run'
        def edit_prompt(envelope, _):
            (run_folder / 'followup.txt').write_text('未被协议绑定的新提示词')
            return envelope
        config = self.server(edit_prompt)
        directory, _ = self.prepare(config)
        original = (directory / 'followup.txt').read_text()
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=2)
        self.assertEqual(2, len(self.requests))
        self.assertEqual([original, original], [r['messages'][0]['content'] for r in self.requests])

    def test_cli_does_not_offer_ledger_or_budget_reset(self):
        result = subprocess.run([sys.executable, str(SCRIPT), 'real', '--ledger', 'other.json'], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.ledger.exists())

    def test_review_archive_survives_missing_current_sources_but_real_stays_strict(self):
        config = self.server()
        directory, _ = self.prepare(config)
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        ledger_before = self.ledger.read_bytes()
        with patch.object(self.runner, 'PRODUCTION_FILES', ['已经改名的生产参考.java']), \
             patch.object(self.runner, 'HELPER', self.out / '已经移动的旧辅助.py'):
            outcome = self.pack(directory)
            self.assertEqual(1, outcome['recorded'])
            self.assertIn('历史协议', (directory / 'review.md').read_text())
            with self.assertRaises((ValueError, OSError)):
                self.runner.run_real(directory, config, ledger_path=self.ledger)
        self.assertEqual(1, len(self.requests))
        self.assertEqual(ledger_before, self.ledger.read_bytes())

    def test_archive_rejects_rehashed_protocol_not_bound_to_ledger(self):
        config = self.server()
        directory, run = self.prepare(config)
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        run['runnerHash'] = '0' * 64
        protocol = {k: run[k] for k in self.runner.identity(self.suite, run['parameters'], (directory / 'followup.txt').read_text())}
        run['protocolHash'] = self.runner.shared.digest(protocol)
        run['tasks'] = self.runner.make_tasks(self.suite, protocol)
        self.runner.shared.write_json(directory / 'run.json', run)
        with self.assertRaisesRegex(ValueError, '账本'):
            self.pack(directory)
        self.assertFalse((directory / 'review.md').exists())

    def test_archive_rejects_missing_record_for_a_registered_attempt(self):
        config = self.server()
        directory, run = self.prepare(config)
        self.runner.run_real(directory, config, ledger_path=self.ledger, max_attempts=1)
        (directory / 'results' / (run['tasks'][0]['taskHash'] + '.json')).unlink()
        with self.assertRaisesRegex(ValueError, '缺失'):
            self.pack(directory)
        self.assertFalse((directory / 'review.md').exists())


if __name__ == '__main__':
    unittest.main()
