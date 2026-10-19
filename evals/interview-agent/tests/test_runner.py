"""评测运行器的离线行为和本机合成 HTTP 边界验证。"""
import copy
import hashlib
import importlib.util
import json
import subprocess
import sys
sys.dont_write_bytecode = True
import tempfile
import unittest
from unittest.mock import patch
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'scripts/interview-agent-eval.py'


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), '尚未实现默认离线的固定评测运行器')
        spec = importlib.util.spec_from_file_location('interview_eval', SCRIPT)
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)
        self.suite = self.runner.load_suite(ROOT / 'evals/interview-agent')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name) / ".evidence"
        self.out.mkdir()

    def test_default_command_validates_offline_without_configuration(self):
        result = subprocess.run(['python3', str(SCRIPT)], text=True, capture_output=True, cwd=self.out)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('24', result.stdout)
        self.assertIn('离线', result.stdout)
        self.assertEqual([], list(self.out.iterdir()))

    def test_suite_contains_24_cases_and_balanced_cross_category_pilot(self):
        self.assertEqual(24, len(self.suite['cases']))
        categories = [case['category'] for case in self.suite['cases']]
        self.assertEqual([6, 6, 6, 6], sorted(categories.count(k) for k in set(categories)))
        pilot = self.runner.select_cases(self.suite, 'pilot')
        self.assertEqual(6, len(pilot))
        self.assertEqual(4, len({case['category'] for case in pilot}))
        self.assertTrue(all(case['rubric'] for case in pilot))

    def test_expected_behavior_and_scores_never_enter_model_inputs(self):
        case = copy.deepcopy(self.suite['cases'][0])
        case['expectedBehavior'] = ['禁止泄露预期标记-EXPECTED']
        case['forbiddenBehavior'] = ['禁止泄露规则标记-FORBIDDEN']
        case['rubric'] = {'sentinel': '禁止泄露评分标记-RUBRIC'}
        for stage in ('FOLLOWUP', 'REVIEW'):
            value = self.runner.build_input(case, self.suite, stage)
            encoded = json.dumps(value, ensure_ascii=False)
            self.assertNotIn('EXPECTED', encoded)
            self.assertNotIn('FORBIDDEN', encoded)
            self.assertNotIn('RUBRIC', encoded)
            self.assertEqual(case['answer'], value['messages'][-1]['content'])
            self.assertTrue(value['mainProject']['hypothetical'])
            if stage == 'REVIEW':
                self.assertEqual(2, value['reviewVersion'])
                self.assertEqual(1, value['assessmentVersion'])

    def test_baseline_prompt_digest_is_verified_before_preparing(self):
        asset = self.out / 'asset'
        import shutil
        shutil.copytree(ROOT / 'evals/interview-agent', asset)
        (asset / 'baseline/followup.txt').write_text('被篡改的提示词', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, '摘要'):
            self.runner.load_suite(asset)

    def prepare(self, strategy='baseline', cases='pilot', config=None):
        path = self.out / strategy
        return self.runner.prepare_run(self.suite, path, strategy, cases, config=config)

    def test_preparation_exports_twelve_tasks_and_unconfirmed_human_gate(self):
        run = self.prepare()
        self.assertEqual(12, len(run['tasks']))
        self.assertEqual(hashlib.sha256(SCRIPT.read_bytes()).hexdigest(), run['runnerHash'])
        self.assertEqual('92261c9', run['strategy']['sourceCommit'])
        gate = self.runner.read_json(self.out / 'baseline/human-gate.json')
        self.assertFalse(gate['confirmedByHuman'])
        self.assertEqual('', gate['reviewer'])
        self.assertEqual(12, len(gate['taskHashes']))
        scores = self.runner.read_json(self.out / 'baseline/scores.json')
        self.assertEqual(6, len(scores['cases']))
        self.assertTrue(all(item['scores']['answer_targeting']['value'] is None for item in scores['cases']))

    def test_preparation_refuses_overwriting_existing_annotations(self):
        self.prepare()
        score = self.out / 'baseline/scores.json'
        before = score.read_bytes()
        with self.assertRaisesRegex(ValueError, '已存在'):
            self.prepare()
        self.assertEqual(before, score.read_bytes())

    def test_budget_counts_failed_and_inflight_attempts_across_output_directories(self):
        ledger = self.out / 'shared/ledger.json'
        for index in range(96):
            self.runner.reserve_attempt(ledger, 'run-' + str(index), 'task-' + str(index), 'protocol')
        with self.assertRaisesRegex(ValueError, '96'):
            self.runner.reserve_attempt(ledger, 'another-directory', 'another-task', 'protocol')
        self.assertEqual(96, len(self.runner.read_json(ledger)['attempts']))

    def test_same_task_is_never_implicitly_retried_after_crash_or_failure(self):
        ledger = self.out / 'ledger.json'
        self.runner.reserve_attempt(ledger, 'one', 'task', 'protocol')
        with self.assertRaisesRegex(ValueError, '已登记|重试'):
            self.runner.reserve_attempt(ledger, 'one', 'task', 'protocol')
        self.assertEqual(1, len(self.runner.read_json(ledger)['attempts']))

    def test_candidate_cannot_run_without_human_baseline_confirmation(self):
        baseline = self.prepare()
        with self.assertRaisesRegex(ValueError, '人工|未完成'):
            self.runner.check_human_gate(self.out / 'baseline', baseline)

    def test_gate_rejects_confirmed_flag_without_complete_baseline_results(self):
        run = self.prepare()
        gate = self.runner.read_json(self.out / 'baseline/human-gate.json')
        gate.update(confirmedByHuman=True, reviewer='真实评分者', confirmedAt='2026-10-19T00:00:00Z')
        self.runner.write_json(self.out / 'baseline/human-gate.json', gate)
        with self.assertRaisesRegex(ValueError, '结果|未完成'):
            self.runner.check_human_gate(self.out / 'baseline', run)

    def test_full_run_reuses_pilot_task_identity_instead_of_charging_again(self):
        pilot = self.prepare()
        full = self.runner.prepare_run(self.suite, self.out / 'full', 'baseline', 'full')
        first = {(task['caseId'], task['stage']): task['taskHash'] for task in pilot['tasks']}
        second = {(task['caseId'], task['stage']): task['taskHash'] for task in full['tasks']}
        self.assertTrue(all(second[key] == digest for key, digest in first.items()))
        self.assertEqual(48, len(full['tasks']))

    def test_strict_model_validation_rejects_invented_source_and_extra_text(self):
        case = self.suite['cases'][0]
        payload = self.runner.build_input(case, self.suite, 'FOLLOWUP')
        with self.assertRaisesRegex(ValueError, '引用'):
            self.runner.validate_output('FOLLOWUP', payload, {'action': 'FOLLOW_UP', 'question': '如何验证？', 'sourceIds': ['invented']})
        with self.assertRaises(ValueError):
            self.runner.strict_json('{"action":"NEXT","sourceIds":[]} 偷跑答案')

    def test_review_requires_exactly_one_finding_per_answered_question(self):
        payload = self.runner.build_input(self.suite['cases'][0], self.suite, 'REVIEW')
        with self.assertRaisesRegex(ValueError, '已答|findings'):
            self.runner.validate_output('REVIEW', payload, {'summary': '合成摘要', 'findings': []})

    def test_review_rejects_non_object_findings_and_non_string_question_ids(self):
        payload = self.runner.build_input(self.suite['cases'][0], self.suite, 'REVIEW')
        for finding in (None, '异常观察', 7, [], {'questionId': []}, {'questionId': {}}):
            with self.subTest(finding=finding), self.assertRaisesRegex(ValueError, 'findings|题号'):
                self.runner.validate_output('REVIEW', payload, {'summary': '合成摘要', 'findings': [finding]})

    def test_review_rejects_non_string_detail_question_ids(self):
        payload = self.runner.build_input(self.suite['cases'][0], self.suite, 'REVIEW')
        question_id = payload['messages'][-1]['questionId']
        result = {'summary': '合成摘要', 'findings': [{'questionId': question_id, 'assessment': '待核实', 'suggestion': '补充证据', 'status': 'INSUFFICIENT', 'sourceIds': [], 'answerStatus': 'UNVERIFIED'}], 'detailedAssessment': {'version': 1, 'overall': {'text': '有限证据', 'questionIds': [[]]}, 'strengths': [], 'improvements': [], 'nextSteps': []}}
        with self.assertRaisesRegex(ValueError, '题号'):
            self.runner.validate_output('REVIEW', payload, result)

    def test_compare_rejects_different_input_or_model_protocol(self):
        before = {'inputHash': 'a', 'parametersHash': 'same', 'suiteHash': 'same', 'caseIds': ['JAVA-01']}
        after = dict(before, inputHash='b')
        with self.assertRaisesRegex(ValueError, '输入|混组'):
            self.runner.assert_comparable(before, after)
        after = dict(before, parametersHash='changed')
        with self.assertRaisesRegex(ValueError, '参数|混组'):
            self.runner.assert_comparable(before, after)
        before['runnerHash'] = 'original-runner'
        after = dict(before, runnerHash='changed-runner')
        with self.assertRaisesRegex(ValueError, '运行器|混组'):
            self.runner.assert_comparable(before, after)

    def test_returned_models_are_compared_by_case_and_stage(self):
        before = [
            {'caseId': ident, 'stage': stage, 'returnedModel': model}
            for ident, model in (('JAVA-01', 'model-one'), ('JAVA-06', 'model-two'))
            for stage in self.runner.STAGES
        ]
        self.runner.assert_returned_models(before, list(reversed(before)))
        after = copy.deepcopy(before)
        for item in after:
            item['returnedModel'] = 'model-two' if item['caseId'] == 'JAVA-01' else 'model-one'
        with self.assertRaisesRegex(ValueError, '实际返回模型|混组'):
            self.runner.assert_returned_models(before, after)

    def test_returned_models_reject_missing_or_duplicate_identity(self):
        before = [{'caseId': 'JAVA-01', 'stage': stage, 'returnedModel': 'model-one'} for stage in self.runner.STAGES]
        for model in (None, '', [], {}):
            after = copy.deepcopy(before)
            after[0]['returnedModel'] = model
            with self.subTest(model=model), self.assertRaisesRegex(ValueError, '实际返回模型|混组'):
                self.runner.assert_returned_models(before, after)
        with self.assertRaisesRegex(ValueError, '实际返回模型|混组'):
            self.runner.assert_returned_models(before, before + [before[0]])

    def score_document(self, value):
        return {'confirmedByHuman': True, 'reviewer': '人工评分者', 'cases': [
            {'caseId': case['id'], 'category': case['category'],
             'scores': {metric: {'value': value, 'reason': '依据完整问答人工检查'} for metric in self.runner.METRICS},
             'blockers': [], 'baselineIssues': ['重复考查已答内容'] if value < 2 else []}
            for case in self.suite['cases'][:6]]}

    def test_scoring_lists_na_separately_and_requires_its_reason(self):
        scores = self.score_document(2)
        entry = scores['cases'][0]['scores']['new_value']
        entry.update(value='N/A', reason='')
        with self.assertRaisesRegex(ValueError, '不适用|N/A'):
            self.runner.validate_scores(scores)
        entry['reason'] = '本例预期换题，追问新增价值不适用'
        summary = self.runner.summarize_scores(scores)
        self.assertEqual(1, summary['notApplicable'])
        self.assertEqual(35, summary['scoredCount'])

    def test_scoring_blockers_fail_even_when_all_dimension_scores_are_full(self):
        before, after = self.score_document(1), self.score_document(2)
        after['cases'][0]['blockers'] = ['ANSWER_LEAK']
        outcome = self.runner.compare_scores(before, after)
        self.assertFalse(outcome['passed'])
        self.assertIn('阻断', ' '.join(outcome['reasons']))

    def test_scoring_cannot_hide_one_category_regression_in_average(self):
        before, after = self.score_document(1), self.score_document(2)
        category = after['cases'][0]['category']
        for case in after['cases']:
            if case['category'] == category:
                for metric in case['scores'].values(): metric['value'] = 0
        outcome = self.runner.compare_scores(before, after)
        self.assertFalse(outcome['passed'])
        self.assertIn(category, ' '.join(outcome['reasons']))

    def test_scoring_requires_improving_at_least_one_baseline_issue(self):
        before, after = self.score_document(1), self.score_document(1)
        outcome = self.runner.compare_scores(before, after)
        self.assertFalse(outcome['passed'])
        self.assertIn('改善', ' '.join(outcome['reasons']))

    def test_score_comparison_rejects_changed_na_applicability(self):
        before, after = self.score_document(1), self.score_document(2)
        after['cases'][0]['scores']['new_value'] = {'value': 'N/A', 'reason': '试图隐藏退化'}
        with self.assertRaisesRegex(ValueError, '适用|N/A'):
            self.runner.compare_scores(before, after)


    def server(self, response_code=200, diagnosis_goal="验证原答尚未覆盖的边界", envelope_transform=None, ledger_path=None):
        requests = []
        ledger = ledger_path or self.out / 'shared/ledger.json'
        runner = self.runner
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append({'path': self.path, 'payload': payload, 'ledgerBeforeRequest': runner.read_json(ledger)})
                if response_code != 200:
                    body = b'private-upstream-body synthetic-secret-key'
                else:
                    value = json.loads(payload['messages'][1]['content'])
                    if 'reviewVersion' not in value:
                        result = {'action': 'NEXT', 'question': '', 'sourceIds': []}
                        if 'diagnosis' in payload['messages'][0]['content']:
                            result.update(action='FOLLOW_UP', question='你的结论如何用并发时序验证？', diagnosis={'category': 'INCOMPLETE', 'coveredPoints': [], 'answerQuote': value['answer'][:12], 'gap': '缺少可核对验证', 'nextGoal': diagnosis_goal, 'reason': '依据当前原答'})
                    else:
                        findings = [{'questionId': m['questionId'], 'assessment': '技术结论待核实', 'suggestion': '补充验证证据', 'status': 'INSUFFICIENT', 'sourceIds': [], 'sourceQuote': '', 'answerStatus': 'UNVERIFIED', 'answerQuote': '', 'headline': '尚未验证', 'observation': '只基于本题已提交回答', 'priority': 3, 'terms': []} for m in value['messages'] if m['role'] == 'USER']
                        result = {'summary': '仅基于合成问答', 'findings': findings, 'detailedAssessment': {'version': 1, 'overall': {'text': '样本范围有限', 'questionIds': []}, 'strengths': [], 'improvements': [], 'nextSteps': []}}
                    envelope = {'id': 'local-synthetic', 'model': 'synthetic-model', 'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(result, ensure_ascii=False)}}], 'usage': {'prompt_tokens': 11, 'completion_tokens': 9}}
                    if envelope_transform:
                        envelope_transform(envelope, value)
                    body = json.dumps(envelope).encode()
                self.send_response(response_code)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(body)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        config = {'provider': 'openai', 'preset': 'CUSTOM', 'baseUrl': 'http://127.0.0.1:' + str(server.server_port) + '/v1', 'apiKey': 'synthetic-secret-key', 'model': 'synthetic-model', 'fastModel': '', 'reasoningEffort': 'high', 'fastReasoningEffort': 'low'}
        return config, ledger, requests

    def test_real_http_registers_before_call_and_reuses_completed_results(self):
        self.assertTrue(callable(getattr(self.runner, 'run_real', None)), '尚未实现显式真实调用入口')
        config, ledger, requests = self.server()
        self.prepare(config=config)
        outcome = self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger, max_attempts=12)
        self.assertEqual(12, outcome['newAttempts'])
        self.assertEqual(12, len(requests))
        self.assertEqual('/v1/chat/completions', requests[0]['path'])
        self.assertEqual('RESERVED', requests[0]['ledgerBeforeRequest']['attempts'][0]['status'])
        self.assertEqual('json_object', requests[0]['payload']['response_format']['type'])
        self.assertEqual(800, requests[0]['payload']['max_tokens'])
        self.assertEqual(10000, requests[1]['payload']['max_tokens'])
        self.assertEqual(1, json.loads(requests[0]['payload']['messages'][1]['content'])['strategyVersion'])
        other = self.out / 'another-output'
        self.runner.prepare_run(self.suite, other, 'baseline', 'pilot', config=config)
        outcome = self.runner.run_real(other, config, ledger_path=ledger, max_attempts=12)
        self.assertEqual(0, outcome['newAttempts'])
        self.assertEqual(12, len(self.runner.read_json(ledger)['attempts']))
        self.assertEqual(12, len(requests))
        self.assertNotIn('synthetic-secret-key', json.dumps(self.runner.read_json(other / 'run.json')))
        self.assertNotIn('127.0.0.1', json.dumps(self.runner.read_json(other / 'run.json')))

    def test_http_failure_is_counted_once_no_retry_or_private_error_leak(self):
        self.assertTrue(callable(getattr(self.runner, 'run_real', None)), '尚未实现失败计数和无重试入口')
        config, ledger, requests = self.server(500)
        self.prepare(config=config)
        with self.assertRaises(ValueError) as caught:
            self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger, max_attempts=12)
        self.assertNotIn('private-upstream', str(caught.exception))
        self.assertNotIn('synthetic-secret-key', str(caught.exception))
        self.assertEqual(1, len(requests))
        self.assertEqual('FAILED', self.runner.read_json(ledger)['attempts'][0]['status'])
        with self.assertRaises(ValueError):
            self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger, max_attempts=12)
        self.assertEqual(1, len(requests))
        self.assertEqual(1, len(self.runner.read_json(ledger)['attempts']))

    def assert_failed_response_is_archived(self, directory, config, ledger, requests, expected_attempts):
        run = self.runner.prepare_run(self.suite, directory, 'baseline', 'pilot', config=config)
        with self.assertRaises(ValueError) as caught:
            self.runner.run_real(directory, config, ledger_path=ledger, max_attempts=12)
        self.assertNotIn('synthetic-secret-key', str(caught.exception))
        self.assertEqual(expected_attempts, len(requests))
        attempts = self.runner.read_json(ledger)['attempts']
        self.assertEqual(expected_attempts, len(attempts))
        self.assertEqual('FAILED', attempts[-1]['status'])
        task = run['tasks'][expected_attempts - 1]
        record = self.runner.read_json(directory / 'results' / (task['taskHash'] + '.json'))
        shared = self.runner.read_json(ledger.parent / 'results' / (task['taskHash'] + '.json'))
        self.assertEqual(record, shared)
        self.assertEqual('FAILED', record['status'])
        self.assertFalse(record['structureValidation']['passed'])
        self.assertTrue(record['rawResponse'])
        self.assertTrue(record['finishedAt'])
        self.assertEqual(self.runner.digest(record), attempts[-1]['resultHash'])
        self.assertNotIn('synthetic-secret-key', record['error'])
        with self.assertRaises(ValueError):
            self.runner.run_real(directory, config, ledger_path=ledger, max_attempts=12)
        self.assertEqual(expected_attempts, len(requests))
        self.assertEqual(expected_attempts, len(self.runner.read_json(ledger)['attempts']))
        return record

    def test_malformed_review_finding_is_archived_before_batch_stops(self):
        def malformed(envelope, value):
            if 'reviewVersion' in value:
                output = json.loads(envelope['choices'][0]['message']['content'])
                output['findings'] = [None]
                envelope['choices'][0]['message']['content'] = json.dumps(output)
        config, ledger, requests = self.server(envelope_transform=malformed)
        record = self.assert_failed_response_is_archived(self.out / 'baseline', config, ledger, requests, 2)
        self.assertEqual([None], record['output']['findings'])
        self.assertEqual(record['output'], json.loads(record['outputText']))

    def test_non_object_message_is_archived_and_never_retried(self):
        for index, message in enumerate((None, '异常消息', [], 7)):
            with self.subTest(message=message):
                folder = self.out / str(index)
                def malformed(envelope, value):
                    envelope['choices'][0]['message'] = message
                config, ledger, requests = self.server(envelope_transform=malformed, ledger_path=folder / 'shared/ledger.json')
                record = self.assert_failed_response_is_archived(folder / 'baseline', config, ledger, requests, 1)
                self.assertEqual(message, json.loads(record['rawResponse'])['choices'][0]['message'])

    def test_invalid_response_metadata_is_archived_before_batch_stops(self):
        invalid = [('usage', '异常统计'), ('usage', []), ('model', None), ('model', ''), ('model', []), ('model', {}), ('system_fingerprint', {})]
        for index, (field, value) in enumerate(invalid):
            with self.subTest(field=field, value=value):
                folder = self.out / str(index)
                def malformed(envelope, payload):
                    envelope[field] = value
                config, ledger, requests = self.server(envelope_transform=malformed, ledger_path=folder / 'shared/ledger.json')
                self.assert_failed_response_is_archived(folder / 'baseline', config, ledger, requests, 1)

    def test_usage_may_be_absent_or_null(self):
        for index, absent in enumerate((True, False)):
            with self.subTest(absent=absent):
                folder = self.out / str(index)
                def optional_usage(envelope, payload):
                    if absent:
                        envelope.pop('usage')
                    else:
                        envelope['usage'] = None
                config, ledger, requests = self.server(envelope_transform=optional_usage, ledger_path=folder / 'shared/ledger.json')
                run = self.runner.prepare_run(self.suite, folder / 'baseline', 'baseline', 'pilot', config=config)
                result = self.runner.run_real(folder / 'baseline', config, ledger_path=ledger, max_attempts=1)
                self.assertEqual(1, result['newAttempts'])
                record = self.runner.read_json(folder / 'baseline/results' / (run['tasks'][0]['taskHash'] + '.json'))
                self.assertEqual('PASSED_STRUCTURE', record['status'])
                self.assertEqual({}, record['usage'])

    def test_excessive_json_depth_is_archived_before_batch_stops(self):
        raw = '{"action":"NEXT","sourceIds":[],"deep":' + '[' * 1100 + 'null' + ']' * 1100 + '}'
        def excessive_depth(envelope, payload):
            envelope['choices'][0]['message']['content'] = raw
        config, ledger, requests = self.server(envelope_transform=excessive_depth)
        record = self.assert_failed_response_is_archived(self.out / 'baseline', config, ledger, requests, 1)
        self.assertEqual(raw, record['outputText'])
        self.assertIn('嵌套', record['error'])

    def test_unexpected_validation_failure_is_archived_without_private_error(self):
        config, ledger, requests = self.server()
        with patch.object(self.runner, 'validate_output', side_effect=RuntimeError('synthetic-secret-key')):
            record = self.assert_failed_response_is_archived(self.out / 'baseline', config, ledger, requests, 1)
        self.assertEqual('NEXT', record['output']['action'])

    def test_candidate_real_call_stops_before_network_without_human_gate(self):
        self.assertTrue(callable(getattr(self.runner, 'run_real', None)), '尚未实现人工基线关卡')
        config, ledger, requests = self.server()
        self.prepare(config=config)
        self.prepare(strategy='candidate', config=config)
        with self.assertRaisesRegex(ValueError, '人工|结果|未完成'):
            self.runner.run_real(self.out / 'candidate', config, ledger_path=ledger, baseline_dir=self.out / 'baseline')
        self.assertEqual([], requests)
        self.assertFalse(ledger.exists())

    def test_changed_model_settings_stop_before_network(self):
        self.assertTrue(callable(getattr(self.runner, 'run_real', None)), '尚未实现协议绑定')
        config, ledger, requests = self.server()
        self.prepare(config=config)
        changed = dict(config, model='another-model')
        with self.assertRaisesRegex(ValueError, '参数|配置'):
            self.runner.run_real(self.out / 'baseline', changed, ledger_path=ledger)
        self.assertEqual([], requests)

    def test_tampered_runner_hash_stops_before_network(self):
        config, ledger, requests = self.server()
        run = self.prepare(config=config)
        run['runnerHash'] = 'changed-runner'
        self.runner.write_json(self.out / 'baseline/run.json', run)
        with self.assertRaisesRegex(ValueError, '运行器|校验版本'):
            self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger)
        self.assertEqual([], requests)
        self.assertFalse(ledger.exists())

    def test_changed_runner_rejects_prior_protocol_and_changes_cache_identity(self):
        config, ledger, requests = self.server()
        before = self.prepare(config=config)
        self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger, max_attempts=1)
        with patch.object(self.runner, 'current_runner_hash', return_value='f' * 64, create=True):
            with self.assertRaisesRegex(ValueError, '运行器|校验版本'):
                self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger)
            after = self.runner.prepare_run(self.suite, self.out / 'changed-runner', 'baseline', 'pilot', config=config)
        self.assertNotEqual(before['runId'], after['runId'])
        self.assertTrue(all(old['taskHash'] != new['taskHash'] for old, new in zip(before['tasks'], after['tasks'])))
        self.assertEqual(1, len(requests))
        self.assertEqual(1, len(self.runner.read_json(ledger)['attempts']))

    def test_pilot_cap_cannot_be_increased_and_full_batch_needs_pilot_gate(self):
        self.assertTrue(callable(getattr(self.runner, 'run_real', None)), '尚未实现首批限额')
        config, ledger, requests = self.server()
        self.prepare(config=config)
        with self.assertRaisesRegex(ValueError, '12'):
            self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger, max_attempts=13)
        self.runner.prepare_run(self.suite, self.out / 'full', 'baseline', 'full', config=config)
        with self.assertRaisesRegex(ValueError, '首批|pilot'):
            self.runner.run_real(self.out / 'full', config, ledger_path=ledger, max_attempts=36)
        self.assertEqual([], requests)

    def test_config_refuses_credential_urls_and_remote_plain_http(self):
        self.assertTrue(callable(getattr(self.runner, 'load_config', None)), '尚未实现安全配置读取')
        for address in ['http://example.com/v1', 'https://user:password@example.com/v1', 'https://example.com/v1?api_key=secret']:
            path = self.out / 'config.json'
            self.runner.write_json(path, {'baseUrl': address, 'apiKey': 'synthetic-secret-key', 'model': 'synthetic-model'})
            with self.assertRaises(ValueError) as caught:
                self.runner.load_config(path)
            self.assertNotIn('password', str(caught.exception))
            self.assertNotIn('api_key=secret', str(caught.exception))

    def test_candidate_diagnosis_requires_literal_current_answer_anchor(self):
        payload = self.runner.build_input(self.suite['cases'][0], self.suite, 'FOLLOWUP')
        result = {'action': 'FOLLOW_UP', 'question': '如何验证？', 'sourceIds': [], 'diagnosis': {'category': 'INCOMPLETE', 'coveredPoints': [], 'answerQuote': '原答中不存在的内容', 'gap': '缺少验证', 'nextGoal': '核对边界', 'reason': '依据当前回答'}}
        self.assertTrue(callable(getattr(self.runner, 'validate_diagnosis', None)), '尚未实现诊断原答锚点校验')
        with self.assertRaisesRegex(ValueError, '原答|短引'):
            self.runner.validate_diagnosis(result['diagnosis'], payload['answer'])

    def test_review_pack_binds_results_without_filling_human_confirmation(self):
        self.assertTrue(callable(getattr(self.runner, 'export_review_pack', None)), '尚未实现人工评阅包')
        config, ledger, requests = self.server()
        run = self.prepare(config=config)
        self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger)
        self.runner.export_review_pack(self.out / 'baseline', self.suite)
        gate = self.runner.read_json(self.out / 'baseline/human-gate.json')
        self.assertFalse(gate['confirmedByHuman'])
        self.assertIsNotNone(gate['resultsHash'])
        self.assertEqual('', gate['reviewer'])
        pack = (self.out / 'baseline/review.md').read_text()
        self.assertIn('JAVA-01', pack)
        self.assertIn('人工', pack)
        self.assertNotIn('synthetic-secret-key', pack)


    def confirm_synthetic_review(self, directory, value=1):
        """仅在合成HTTP测试里模拟文件格式，不构成真实人工验收。"""
        self.runner.export_review_pack(directory, self.suite)
        scores = self.runner.read_json(directory / 'scores.json')
        scores.update(confirmedByHuman=True, reviewer='合成测试评分占位')
        for entry in scores['cases']:
            for score in entry['scores'].values(): score.update(value=value, reason='合成测试证据')
            entry['baselineIssues'] = [{'metric': 'new_value', 'description': '缺少新的验证目标'}] if value < 2 else []
        self.runner.write_json(directory / 'scores.json', scores)
        gate = self.runner.read_json(directory / 'human-gate.json')
        gate.update(confirmedByHuman=True, reviewer='合成测试评分占位', confirmedAt='2026-10-19T00:00:00Z')
        self.runner.write_json(directory / 'human-gate.json', gate)

    def prepare_synthetic_candidate(self, config):
        prompts = self.out / 'synthetic-prompts'
        prompts.mkdir()
        for stage in ('followup', 'review'):
            text = (ROOT / 'evals/interview-agent/baseline' / (stage + '.txt')).read_text()
            (prompts / (stage + '.txt')).write_text(text + ('\n本机合成测试diagnosis契约。' if stage == 'followup' else ''))
        return self.runner.prepare_run(self.suite, self.out / 'candidate', 'candidate', 'pilot', config=config, candidate_prompts=prompts)

    def test_cli_real_command_is_explicit_and_human_gate_blocks_network(self):
        config, ledger, requests = self.server()
        self.prepare(config=config)
        self.prepare(strategy='candidate', config=config)
        path = self.out / 'synthetic-config.json'
        self.runner.write_json(path, config)
        result = subprocess.run(['python3', str(SCRIPT), 'real', '--run', str(self.out / 'candidate'), '--baseline', str(self.out / 'baseline'), '--config', str(path)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn('原策略结果未完成', result.stderr)
        self.assertEqual([], requests)
        self.assertNotIn('synthetic-secret-key', result.stderr)

    def test_followup_without_new_goal_fails_and_is_counted(self):
        config, ledger, requests = self.server(diagnosis_goal='')
        self.prepare(config=config)
        self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger)
        self.confirm_synthetic_review(self.out / 'baseline')
        self.prepare_synthetic_candidate(config)
        with self.assertRaisesRegex(ValueError, '目的|nextGoal'):
            self.runner.run_real(self.out / 'candidate', config, ledger_path=ledger, baseline_dir=self.out / 'baseline')
        self.assertEqual(13, len(requests))
        self.assertEqual('FAILED', self.runner.read_json(ledger)['attempts'][-1]['status'])

    def test_blind_pack_refuses_tampered_fixed_input(self):
        config, ledger, requests = self.server()
        self.prepare(config=config)
        self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger)
        self.confirm_synthetic_review(self.out / 'baseline')
        candidate = self.prepare_synthetic_candidate(config)
        self.runner.run_real(self.out / 'candidate', config, ledger_path=ledger, baseline_dir=self.out / 'baseline')
        candidate['tasks'][0]['input']['answer'] = '被替换的答案与泄入的评分规则'
        self.runner.write_json(self.out / 'candidate/run.json', candidate)
        with self.assertRaisesRegex(ValueError, '输入|混组'):
            self.runner.export_blind_pack(self.out / 'baseline', self.out / 'candidate', self.out / 'blind')


    def test_low_baseline_scores_cannot_bypass_improvement_by_omitting_issue_list(self):
        before, after = self.score_document(1), self.score_document(1)
        for entry in before['cases']: entry['baselineIssues'] = []
        outcome = self.runner.compare_scores(before, after)
        self.assertFalse(outcome['passed'])
        self.assertIn('改善', ' '.join(outcome['reasons']))

    def test_full_batch_gate_refuses_changed_returned_model_in_pilot(self):
        config, ledger, requests = self.server()
        self.prepare(config=config)
        self.runner.run_real(self.out / 'baseline', config, ledger_path=ledger)
        self.confirm_synthetic_review(self.out / 'baseline', 1)
        candidate = self.prepare_synthetic_candidate(config)
        self.runner.run_real(self.out / 'candidate', config, ledger_path=ledger, baseline_dir=self.out / 'baseline')
        path = self.out / 'candidate/results' / (candidate['tasks'][0]['taskHash'] + '.json')
        record = self.runner.read_json(path)
        record['returnedModel'] = 'unexpected-upstream-model'
        self.runner.write_json(path, record)
        self.confirm_synthetic_review(self.out / 'candidate', 2)
        full = self.runner.prepare_run(self.suite, self.out / 'full', 'baseline', 'full', config=config)
        with self.assertRaisesRegex(ValueError, '实际返回模型|混组'):
            self.runner.require_pilot_passed(self.out / 'baseline', self.out / 'candidate', full)

    def test_followup_upper_limit_records_effective_next_instead_of_json_failure(self):
        payload = self.runner.build_input(self.suite['cases'][0], self.suite, 'FOLLOWUP')
        payload['followUpCount'] = 2
        result = {'action': 'FOLLOW_UP', 'question': '如何进一步验证？', 'sourceIds': []}
        try:
            mechanical = self.runner.validate_output('FOLLOWUP', payload, result)
        except ValueError:
            self.fail('追问次数上限属于服务动作约束，不能误报JSON结构错误')
        self.assertEqual('NEXT', mechanical['effectiveAction'])


if __name__ == '__main__':
    unittest.main()
