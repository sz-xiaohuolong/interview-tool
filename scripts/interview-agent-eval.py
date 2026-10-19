#!/usr/bin/env python3
"""固定合成面试评测。默认离线；真实模型调用必须显式选择 real 子命令。"""
import argparse
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'evals/interview-agent'
METRICS = ('answer_targeting', 'new_value', 'correct_understanding', 'no_repetition', 'review_evidence', 'no_answer_leak')
BLOCKERS = ('ANSWER_LEAK', 'FABRICATED_EXPERIENCE', 'FABRICATED_TECHNICAL_EVIDENCE')
STAGES = ('FOLLOWUP', 'REVIEW')
RUNNER_SCHEMA = 'interview-agent-eval/1'
TOTAL_ATTEMPTS = 96
MAX_JSON_DEPTH = 64


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(value):
    raw = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(raw).hexdigest()


def current_runner_hash():
    return digest(Path(__file__).read_bytes())


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write_json(path, value):
    """原子写入本机证据，避免中断时只留下半个 JSON。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.' + secrets.token_hex(5) + '.tmp')
    try:
        with open(temporary, 'x', encoding='utf-8') as stream:
            os.chmod(temporary, 0o600)
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def strict_json(text):
    def duplicate(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError('JSON含重复键')
            value[key] = item
        return value
    try:
        value = json.loads(text, object_pairs_hook=duplicate, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('JSON含非法数值')))
    except RecursionError:
        raise ValueError('JSON嵌套过深，无法安全解析') from None
    if not isinstance(value, dict):
        raise ValueError('模型输出必须是一个JSON对象')
    pending = [(value, 1)]
    while pending:
        item, depth = pending.pop()
        if depth > MAX_JSON_DEPTH:
            raise ValueError('JSON嵌套超过64层上限')
        children = item.values() if isinstance(item, dict) else item
        pending.extend((child, depth + 1) for child in children if isinstance(child, (dict, list)))
    return value


def load_suite(folder=ASSETS):
    folder = Path(folder)
    suite = read_json(folder / 'cases.json')
    sources = read_json(folder / 'sources.json')
    baseline = read_json(folder / 'baseline/manifest.json')
    for item in list(baseline['prompts'].values()) + [baseline['validator']]:
        if digest((folder / item['path']).read_bytes()) != item['sha256']:
            raise ValueError('原策略快照摘要不符，拒绝改变基线')
    if baseline.get('sourceCommit') != '92261c9':
        raise ValueError('原策略来源不是已冻结的92261c9')
    cases = suite['cases']
    if len(cases) != 24 or len({case['id'] for case in cases}) != 24:
        raise ValueError('固定评测集必须包含24个不同案例')
    categories = set(suite['categories'])
    if len(categories) != 4 or any(sum(case['category'] == key for case in cases) != 6 for key in categories):
        raise ValueError('固定评测集必须四类各6例')
    source_map = {}
    for source in sources['sources']:
        if source['id'] in source_map or source.get('kind') != 'OFFICIAL':
            raise ValueError('来源重复或不是官方来源')
        if digest(source['excerpt'].encode()) != source['excerptSha256']:
            raise ValueError('官方短引摘要不符')
        source_map[source['id']] = source
    for case in cases:
        if case.get('synthetic') is not True:
            raise ValueError('只允许固定合成案例')
        for field in ('question', 'answer', 'role', 'level', 'expectedBehavior', 'forbiddenBehavior'):
            if not case.get(field):
                raise ValueError('案例缺少必填项：' + case['id'] + '/' + field)
        if set(case['rubric']) != set(METRICS):
            raise ValueError('人工评分规则必须覆盖六个维度')
        if any(ident not in source_map for ident in case['sourceIds']):
            raise ValueError('案例引用不存在的来源')
    pilot = suite['pilotCaseIds']
    if len(pilot) != 6 or len(set(pilot)) != 6 or not set(pilot) <= {case['id'] for case in cases}:
        raise ValueError('首批必须选取6个固定案例')
    if {case['category'] for case in cases if case['id'] in pilot} != categories:
        raise ValueError('首批必须覆盖全部四类')
    suite['_folder'] = folder
    suite['_sources'] = source_map
    suite['_baseline'] = baseline
    suite['_suiteHash'] = digest({'cases': read_json(folder / 'cases.json'), 'sources': sources})
    return suite


def select_cases(suite, batch):
    if batch not in ('pilot', 'full'):
        raise ValueError('批次只允许pilot或full')
    return [case for case in suite['cases'] if batch == 'full' or case['id'] in suite['pilotCaseIds']]


def build_input(case, suite, stage):
    """白名单构造输入，避免预期、禁止行为和人工评分规则泄入请求。"""
    if stage not in STAGES:
        raise ValueError('只允许FOLLOWUP和REVIEW，不能额外调用分析或规划')
    messages = []
    for index, turn in enumerate(case['history'] + [{'question': case['question'], 'answer': case['answer']}], 1):
        qid = case['id'] + '-q' + str(index)
        messages.extend([
            {'id': qid + '-question', 'role': 'ASSISTANT', 'questionId': qid, 'rootQuestionId': qid, 'questionKind': 'MAIN', 'content': turn['question']},
            {'id': qid + '-answer', 'role': 'USER', 'questionId': qid, 'rootQuestionId': qid, 'questionKind': 'MAIN', 'content': turn['answer']},
        ])
    source_keys = ('id', 'title', 'url', 'kind', 'stale', 'fetchedAt', 'excerpt')
    value = {
        'role': case['role'], 'level': case['level'], 'jd': '', 'resume': '',
        'summary': {'technicalStack': [], 'responsibilities': [], 'clarifications': [], 'focus': []},
        'messages': messages, 'currentQuestion': messages[-2], 'answer': case['answer'],
        'followUpCount': case['followUpCount'], 'coveredPoints': copy.deepcopy(case['coveredPoints']),
        'sources': [{key: suite['_sources'][ident][key] for key in source_keys} for ident in case['sourceIds']],
        'experienceVersion': 1, 'strategyVersion': 1, 'stage': 'TECHNICAL', 'reduceFollowups': False,
        'mainProject': {'hypothetical': True, 'brief': ''},
    }
    if stage == 'REVIEW':
        value.update(reviewVersion=2, assessmentVersion=1)
    return value


def parameters(config=None):
    config = config or {}
    model = config.get('model', '未配置')
    fast = config.get('fastModel') or model
    preset = config.get('preset', 'CUSTOM')
    return {
        'providerIdentity': digest({'provider': config.get('provider', 'openai'), 'baseUrl': config.get('baseUrl', '')}),
        'FOLLOWUP': {'model': fast, 'reasoning_effort': '' if preset == 'DEEPSEEK' else config.get('fastReasoningEffort', 'low'), 'temperature': 0.4, 'max_tokens': 800, 'timeoutSeconds': 6, 'extraBody': {'thinking': {'type': 'disabled'}} if preset == 'DEEPSEEK' else {}},
        'REVIEW': {'model': model, 'reasoning_effort': config.get('reasoningEffort', ''), 'temperature': 0.4, 'max_tokens': 10000, 'timeoutSeconds': 150, 'extraBody': {}},
    }


def prepare_run(suite, output, strategy, batch, config=None, candidate_prompts=None):
    output = Path(output)
    if output.exists():
        raise ValueError('输出目录已存在，不能覆盖已生成输入、结果或人工标注')
    if strategy not in ('baseline', 'candidate'):
        raise ValueError('策略只允许baseline或candidate')
    prompts = {}
    if strategy == 'baseline':
        for stage, meta in suite['_baseline']['prompts'].items():
            prompts[stage] = (suite['_folder'] / meta['path']).read_text(encoding='utf-8')
        strategy_meta = {'kind': strategy, 'sourceCommit': '92261c9', 'validatorHash': suite['_baseline']['validator']['sha256']}
    else:
        folder = Path(candidate_prompts or ROOT / 'backend/src/main/resources/prompts/interview')
        prompts = {stage: (folder / (stage.lower() + '.txt')).read_text(encoding='utf-8') for stage in STAGES}
        strategy_meta = {'kind': strategy, 'sourceCommit': 'WORKTREE', 'validatorHash': digest((ROOT / 'backend/src/main/java/cn/interview/validation/InterviewOutputValidator.java').read_bytes())}
    strategy_meta['promptHashes'] = {stage: digest(text.encode()) for stage, text in prompts.items()}
    cases = select_cases(suite, batch)
    settings = parameters(config)
    strategy_hash = digest(strategy_meta)
    runner_hash = current_runner_hash()
    tasks = []
    for case in cases:
        for stage in STAGES:
            value = build_input(case, suite, stage)
            identity = {'caseId': case['id'], 'stage': stage, 'input': value, 'parameters': settings[stage], 'providerIdentity': settings['providerIdentity'], 'strategyHash': strategy_hash, 'suiteHash': suite['_suiteHash'], 'runnerSchema': RUNNER_SCHEMA, 'runnerHash': runner_hash}
            tasks.append({'caseId': case['id'], 'category': case['category'], 'stage': stage, 'input': value, 'inputHash': digest(value), 'taskHash': digest(identity)})
    manifest = {'version': 1, 'runnerSchema': RUNNER_SCHEMA, 'runnerHash': runner_hash, 'createdAt': now(), 'batch': batch, 'strategy': strategy_meta, 'suiteHash': suite['_suiteHash'], 'sourceHash': digest(list(suite['_sources'].values())), 'caseIds': [case['id'] for case in cases], 'parameters': settings, 'parametersHash': digest(settings), 'inputHash': digest([{'caseId': task['caseId'], 'stage': task['stage'], 'inputHash': task['inputHash']} for task in tasks]), 'tasks': tasks}
    manifest['runId'] = digest({'strategy': strategy_meta, 'suiteHash': manifest['suiteHash'], 'inputHash': manifest['inputHash'], 'parametersHash': manifest['parametersHash'], 'runnerHash': runner_hash})
    output.mkdir(parents=True)
    for stage, prompt in prompts.items():
        (output / (stage.lower() + '.txt')).write_text(prompt, encoding='utf-8')
    write_json(output / 'run.json', manifest)
    write_json(output / 'scores.json', {'runId': manifest['runId'], 'confirmedByHuman': False, 'reviewer': '', 'cases': [{'caseId': case['id'], 'category': case['category'], 'scores': {metric: {'value': None, 'reason': ''} for metric in METRICS}, 'blockers': [], 'baselineIssues': []} for case in cases]})
    write_json(output / 'human-gate.json', {'runId': manifest['runId'], 'confirmedByHuman': False, 'reviewer': '', 'confirmedAt': '', 'taskHashes': [task['taskHash'] for task in tasks], 'resultsHash': None, 'instruction': '由真实人工阅读结果并完成scores.json后填写确认；不得将Agent初评写作人工标注。'})
    write_json(output / 'human-reference.json', {'cases': [{key: case[key] for key in ('id', 'title', 'expectedAction', 'expectedBehavior', 'forbiddenBehavior', 'rubric')} for case in cases]})
    return manifest


def validate_output(stage, payload, result):
    """校验能机械核对的契约和逐字引用；语义专业性仍须人工评分。"""
    if not isinstance(result, dict):
        raise ValueError('模型输出必须是对象')
    sources = {source['id']: source for source in payload['sources']}
    def refs(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key == 'sourceIds':
                    if not isinstance(item, list) or any(not isinstance(ident, str) or ident not in sources for ident in item):
                        raise ValueError('引用了输入中不存在的技术来源')
                else:
                    refs(item)
        elif isinstance(value, list):
            for item in value:
                refs(item)
    refs(result)
    if stage == 'FOLLOWUP':
        if result.get('action') not in ('FOLLOW_UP', 'NEXT') or not isinstance(result.get('sourceIds'), list):
            raise ValueError('追问动作或引用字段无效')
        if result['action'] == 'FOLLOW_UP' and (not isinstance(result.get('question'), str) or not result['question'].strip() or len(result['question']) > 1500):
            raise ValueError('追问题干缺失或超长')
        if 'stageComplete' in result and (not isinstance(result['stageComplete'], bool) or (result['stageComplete'] and result['action'] != 'NEXT')):
            raise ValueError('阶段完成标志无效')
        effective = 'FOLLOW_UP' if result['action'] == 'FOLLOW_UP' and payload['followUpCount'] < 2 and not payload.get('reduceFollowups') else 'NEXT'
        return {'effectiveAction': effective}
    if stage != 'REVIEW' or not isinstance(result.get('summary'), str) or not result['summary'].strip():
        raise ValueError('复盘摘要缺失')
    answers = {message['questionId']: message['content'] for message in payload['messages'] if message['role'] == 'USER'}
    findings = result.get('findings')
    if not isinstance(findings, list) or len(findings) != len(answers) or any(not isinstance(item, dict) or not isinstance(item.get('questionId'), str) for item in findings) or {item['questionId'] for item in findings} != set(answers):
        raise ValueError('findings必须覆盖每个已答questionId且恰好一条')
    for item in findings:
        if not all(isinstance(item.get(key), str) and item[key].strip() for key in ('assessment', 'suggestion')):
            raise ValueError('复盘观察或建议缺失')
        if item.get('status') not in ('SUPPORTED', 'INSUFFICIENT', 'CONFLICTING') or not isinstance(item.get('sourceIds'), list):
            raise ValueError('复盘技术状态或来源无效')
        quote = item.get('sourceQuote', '')
        if item['status'] == 'SUPPORTED' and (not isinstance(quote, str) or len(quote) < 12 or not any(sources[ident].get('kind') == 'OFFICIAL' and not sources[ident].get('stale') and quote in sources[ident]['excerpt'] for ident in item['sourceIds'])):
            raise ValueError('确定性技术判断没有逐字官方引用')
        state = item.get('answerStatus')
        if state not in ('CLEAR', 'PARTIAL', 'UNVERIFIED'):
            raise ValueError('复盘内容状态无效')
        answer_quote = item.get('answerQuote', '')
        if not isinstance(answer_quote, str) or len(answer_quote) > 500 or (answer_quote and answer_quote not in answers[item['questionId']]):
            raise ValueError('原答短引不存在或跨题引用')
        if state != 'UNVERIFIED' and (not answer_quote.strip() or not item.get('observation')):
            raise ValueError('内容观察没有具体原答证据')
    detail = result.get('detailedAssessment')
    if not isinstance(detail, dict) or detail.get('version') != 1:
        raise ValueError('详细评估缺失')
    by_id = {item['questionId']: item for item in findings}
    improvements = set()
    for section in ('overall', 'strengths', 'improvements', 'nextSteps'):
        entries = [detail.get(section)] if section == 'overall' else detail.get(section)
        if not isinstance(entries, list) or len(entries) > (1 if section == 'overall' else 3):
            raise ValueError('详细评估段落结构无效')
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get('text'), str) or not entry['text'].strip():
                raise ValueError('详细评估正文缺失')
            ids = entry.get('questionIds')
            if not isinstance(ids, list) or any(not isinstance(ident, str) for ident in ids) or len(ids) != len(set(ids)) or not set(ids) <= set(answers) or (section != 'overall' and not ids):
                raise ValueError('详细评估引用了未答或重复题号')
            if section != 'overall':
                required = 'CLEAR' if section == 'strengths' else 'PARTIAL'
                if any(by_id[ident]['answerStatus'] != required or not by_id[ident].get('answerQuote') for ident in ids):
                    raise ValueError('详细评估与原答观察不一致')
            if section == 'improvements': improvements.update(ids)
            if section == 'nextSteps' and not set(ids) <= improvements:
                raise ValueError('行动没有对应改进项')


def default_ledger():
    common = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', '--path-format=absolute', '--git-common-dir'], text=True).strip()
    return Path(common).parent / '.evidence/interview-agent-eval/ledger.json'


def reserve_attempt(ledger_path, run_id, task_hash, protocol_hash):
    """跨进程锁保护同一仓库的96次总账；登记后才允许网络请求。"""
    ledger_path = Path(ledger_path)
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    with open(ledger_path.with_suffix('.lock'), 'a', encoding='utf-8') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        ledger = read_json(ledger_path) if ledger_path.exists() else {'version': 1, 'limit': TOTAL_ATTEMPTS, 'attempts': []}
        if ledger.get('limit') != TOTAL_ATTEMPTS:
            raise ValueError('共享账本预算被改变，拒绝执行')
        if any(item['taskHash'] == task_hash for item in ledger['attempts']):
            raise ValueError('同一任务已登记，不能隐式重试；请检查既有结果或中断记录')
        if len(ledger['attempts']) >= TOTAL_ATTEMPTS:
            raise ValueError('已达到96次真实请求总预算，失败与中断均计数')
        item = {'number': len(ledger['attempts']) + 1, 'runId': run_id, 'taskHash': task_hash, 'protocolHash': protocol_hash, 'reservedAt': now(), 'status': 'RESERVED'}
        ledger['attempts'].append(item)
        write_json(ledger_path, ledger)
        return item


def validate_scores(document):
    if document.get('confirmedByHuman') is not True or not str(document.get('reviewer', '')).strip():
        raise ValueError('评分尚未由人工完成并确认')
    entries = document.get('cases')
    if not isinstance(entries, list) or not entries or len({entry.get('caseId') for entry in entries}) != len(entries):
        raise ValueError('人工评分案例缺失或重复')
    for entry in entries:
        if set(entry.get('scores', {})) != set(METRICS):
            raise ValueError('人工评分必须包含六个维度')
        if not isinstance(entry.get('blockers'), list) or any(blocker not in BLOCKERS for blocker in entry['blockers']):
            raise ValueError('人工阻断项无效')
        for score in entry['scores'].values():
            value = score.get('value')
            if value == 'N/A':
                if not str(score.get('reason', '')).strip(): raise ValueError('N/A不适用项必须单列理由')
            elif type(value) is not int or value not in (0, 1, 2):
                raise ValueError('人工分值必须为0、1、2或N/A，不能用空值充作完成')
            if not str(score.get('reason', '')).strip():
                raise ValueError('每个人工分值必须有可核对理由')
    return document


def summarize_scores(document):
    validate_scores(document)
    values = [score['value'] for entry in document['cases'] for score in entry['scores'].values()]
    scored = [value for value in values if value != 'N/A']
    categories = {}
    for category in {entry['category'] for entry in document['cases']}:
        items = [score['value'] for entry in document['cases'] if entry['category'] == category for score in entry['scores'].values() if score['value'] != 'N/A']
        categories[category] = {'average': sum(items) / len(items) if items else None, 'scoredCount': len(items)}
    return {'average': sum(scored) / len(scored) if scored else None, 'scoredCount': len(scored), 'notApplicable': values.count('N/A'), 'categories': categories}


def compare_scores(before, after):
    left, right = summarize_scores(before), summarize_scores(after)
    old = {entry['caseId']: entry for entry in before['cases']}
    new = {entry['caseId']: entry for entry in after['cases']}
    if set(old) != set(new):
        raise ValueError('对比的案例不一致，拒绝混组')
    improvement = False
    for ident, entry in old.items():
        candidate = new[ident]
        if entry['category'] != candidate['category']:
            raise ValueError('对比的案例类别被改变')
        for metric in METRICS:
            a, b = entry['scores'][metric]['value'], candidate['scores'][metric]['value']
            if (a == 'N/A') != (b == 'N/A'):
                raise ValueError('对比改变了N/A适用范围，须人工统一规则后重评')
            if a != 'N/A' and b > a:
                issues = entry.get('baselineIssues', [])
                if a < 2 or any(isinstance(issue, str) or issue.get('metric') == metric for issue in issues): improvement = True
        if set(entry['blockers']) - set(candidate['blockers']): improvement = True
    reasons = []
    if any(entry['blockers'] for entry in after['cases']): reasons.append('新策略存在人工确认的阻断行为')
    if left['average'] is None or right['average'] is None or right['average'] < left['average']:
        reasons.append('整体评分退步或没有可比较分值')
    for category, summary in left['categories'].items():
        candidate = right['categories'][category]
        if summary['average'] is not None and (candidate['average'] is None or candidate['average'] < summary['average']):
            reasons.append(category + '类别评分退步')
    baseline_has_problem = any(entry.get('baselineIssues') or entry['blockers'] or any(score['value'] != 'N/A' and score['value'] < 2 for score in entry['scores'].values()) for entry in before['cases'])
    if baseline_has_problem and not improvement:
        reasons.append('尚未证明改善基线暴露的具体问题')
    return {'passed': not reasons, 'reasons': reasons, 'baseline': left, 'candidate': right, 'improvedBaselineIssue': improvement}


def assert_comparable(before, after):
    names = {'suiteHash': '固定样本', 'sourceHash': '技术来源', 'inputHash': '输入', 'parametersHash': '模型参数', 'caseIds': '案例', 'runnerSchema': '校验版本', 'runnerHash': '运行器源码'}
    for key, name in names.items():
        if before.get(key) != after.get(key):
            raise ValueError(name + '不一致，拒绝混组对比')


def result_paths(directory, manifest):
    return [Path(directory) / 'results' / (task['taskHash'] + '.json') for task in manifest['tasks']]


def completed_results(directory, manifest):
    records = []
    for task, path in zip(manifest['tasks'], result_paths(directory, manifest)):
        if not path.exists(): raise ValueError('原策略结果未完成，不能通过人工关卡')
        record = read_json(path)
        if record.get('taskHash') != task['taskHash'] or record.get('status') != 'PASSED_STRUCTURE':
            raise ValueError('存在失败或不匹配结果，人工关卡未完成')
        records.append(record)
    return records


def check_human_gate(directory, manifest):
    directory = Path(directory)
    records = completed_results(directory, manifest)
    gate = read_json(directory / 'human-gate.json')
    scores = read_json(directory / 'scores.json')
    validate_scores(scores)
    if gate.get('confirmedByHuman') is not True or not str(gate.get('reviewer', '')).strip() or not gate.get('confirmedAt'):
        raise ValueError('原策略基线尚未得到真实人工明确确认')
    if gate.get('runId') != manifest['runId'] or scores.get('runId') != manifest['runId'] or gate.get('taskHashes') != [task['taskHash'] for task in manifest['tasks']] or gate.get('resultsHash') != digest(records):
        raise ValueError('人工确认未绑定当前结果、输入或运行协议')
    if {entry['caseId'] for entry in scores['cases']} != set(manifest['caseIds']):
        raise ValueError('人工评分案例不完整')
    categories = {task['caseId']: task['category'] for task in manifest['tasks']}
    if any(entry['category'] != categories[entry['caseId']] for entry in scores['cases']):
        raise ValueError('人工评分篡改了固定类别')
    return scores


def validate_diagnosis(value, answer, action=None):
    categories = ('SUFFICIENT', 'INCOMPLETE', 'INCORRECT', 'UNCERTAIN', 'CONTRADICTORY', 'NO_EVIDENCE')
    if not isinstance(value, dict) or value.get('category') not in categories:
        raise ValueError('诊断类别无效')
    points = value.get('coveredPoints')
    if not isinstance(points, list) or len(points) > 3 or any(not isinstance(item, str) or not item.strip() or len(item) > 30 for item in points):
        raise ValueError('诊断已覆盖考点无效')
    for key, maximum, blank in [('answerQuote', 80, False), ('gap', 70, True), ('nextGoal', 60, True), ('reason', 70, False)]:
        item = value.get(key)
        if not isinstance(item, str) or len(item) > maximum or (not blank and not item.strip()):
            raise ValueError('诊断字段缺失或超出预算：' + key)
    if action == 'FOLLOW_UP' and not value['nextGoal'].strip():
        raise ValueError('FOLLOW_UP的新增考查目的nextGoal不能为空')
    if value['answerQuote'] not in answer:
        raise ValueError('诊断短引未逐字存在于当前原答')
    if len(json.dumps(value, ensure_ascii=False, separators=(',', ':'))) > 650:
        raise ValueError('诊断超过650字符预算')


def load_config(path=None):
    """只在显式准备配置或真实调用时读取；不会把地址和密钥写入证据。"""
    from urllib.parse import urlsplit
    import re
    if path is None and os.environ.get('INTERVIEW_AGENT_EVAL_CONFIG'):
        path = Path(os.environ['INTERVIEW_AGENT_EVAL_CONFIG'])
    if path:
        config = read_json(path)
        if not isinstance(config, dict): raise ValueError('模型配置必须为对象')
    else:
        config = {'provider': os.environ.get('LLM_PROVIDER', 'openai'), 'baseUrl': os.environ.get('LLM_BASE_URL', ''), 'apiKey': os.environ.get('LLM_API_KEY', ''), 'model': os.environ.get('LLM_MODEL', ''), 'fastModel': os.environ.get('INTERVIEW_FOLLOWUP_MODEL', os.environ.get('V3_FAST_MODEL', '')), 'reasoningEffort': os.environ.get('LLM_REASONING_EFFORT', ''), 'fastReasoningEffort': os.environ.get('INTERVIEW_FOLLOWUP_REASONING_EFFORT', os.environ.get('V3_FAST_REASONING_EFFORT', 'low'))}
    if config.get('provider', 'openai') != 'openai' or not isinstance(config.get('apiKey'), str) or not config['apiKey'].strip():
        raise ValueError('真实评测需要显式OpenAI兼容连接；缺少配置时不会切换Mock')
    try:
        endpoint = urlsplit(config.get('baseUrl', ''))
        valid = endpoint.scheme == 'https' or (endpoint.scheme == 'http' and endpoint.hostname in ('localhost', '127.0.0.1', '::1'))
        valid = valid and bool(endpoint.hostname) and not endpoint.username and not endpoint.password and not endpoint.query and not endpoint.fragment
        if not valid: raise ValueError()
        endpoint.port
    except (TypeError, ValueError):
        raise ValueError('模型地址格式无效；仅支持HTTPS或本机回环HTTP，不能含账号、查询或片段') from None
    for key in ('model', 'fastModel'):
        model = config.get(key, '')
        if not isinstance(model, str) or (key == 'model' and not model) or (model and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/@+\-]{0,199}', model)):
            raise ValueError('模型ID格式无效')
    if any(ord(char) < 33 or ord(char) > 126 for char in config['apiKey']):
        raise ValueError('模型密钥格式无效')
    for key in ('reasoningEffort', 'fastReasoningEffort'):
        if config.get(key, '' if key == 'reasoningEffort' else 'low') not in ('', 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'):
            raise ValueError('模型思考强度无效')
    config = dict(config)
    config.setdefault('provider', 'openai')
    config.setdefault('preset', 'DEEPSEEK' if endpoint.hostname == 'api.deepseek.com' else 'CUSTOM')
    config.setdefault('fastReasoningEffort', 'low')
    config['baseUrl'] = config['baseUrl'].rstrip('/')
    return config


def verify_manifest(directory, manifest, suite):
    """执行前重新核对固定输入、Prompt、参数与任务身份，拒绝编辑后混组。"""
    if manifest.get('runnerHash') != current_runner_hash():
        raise ValueError('运行器源码与准备协议不同，拒绝混组或复用旧校验结果')
    if manifest.get('runnerSchema') != RUNNER_SCHEMA or manifest.get('suiteHash') != suite['_suiteHash']:
        raise ValueError('固定样本或运行器校验版本已变化，拒绝混组')
    cases = select_cases(suite, manifest['batch'])
    if manifest['caseIds'] != [case['id'] for case in cases] or len(manifest['tasks']) != len(cases) * 2:
        raise ValueError('任务列表偏离固定批次')
    for stage in STAGES:
        prompt = (Path(directory) / (stage.lower() + '.txt')).read_bytes()
        if digest(prompt) != manifest['strategy']['promptHashes'][stage]:
            raise ValueError('执行Prompt摘要与准备协议不符')
    if manifest['strategy']['kind'] == 'baseline':
        base = suite['_baseline']
        if manifest['strategy']['sourceCommit'] != '92261c9' or manifest['strategy']['validatorHash'] != base['validator']['sha256'] or manifest['strategy']['promptHashes'] != {stage: meta['sha256'] for stage, meta in base['prompts'].items()}:
            raise ValueError('原策略Prompt或校验契约偏离92261c9')
    for task, (case, stage) in zip(manifest['tasks'], [(case, stage) for case in cases for stage in STAGES]):
        value = build_input(case, suite, stage)
        identity = {'caseId': case['id'], 'stage': stage, 'input': value, 'parameters': manifest['parameters'][stage], 'providerIdentity': manifest['parameters']['providerIdentity'], 'strategyHash': digest(manifest['strategy']), 'suiteHash': suite['_suiteHash'], 'runnerSchema': RUNNER_SCHEMA, 'runnerHash': manifest['runnerHash']}
        if task['caseId'] != case['id'] or task['stage'] != stage or task['input'] != value or task['inputHash'] != digest(value) or task['taskHash'] != digest(identity):
            raise ValueError('任务输入、策略或模型协议已改变，拒绝混组')
    expected = digest([{'caseId': task['caseId'], 'stage': task['stage'], 'inputHash': task['inputHash']} for task in manifest['tasks']])
    if expected != manifest['inputHash'] or digest(manifest['parameters']) != manifest['parametersHash']:
        raise ValueError('输入或参数摘要不符')


def finish_attempt(ledger_path, task_hash, record):
    ledger_path = Path(ledger_path)
    with open(ledger_path.with_suffix('.lock'), 'a', encoding='utf-8') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        ledger = read_json(ledger_path)
        for item in ledger['attempts']:
            if item['taskHash'] == task_hash:
                item.update(status=record['status'], finishedAt=record['finishedAt'], resultHash=digest(record))
                write_json(ledger_path, ledger)
                return
        raise ValueError('找不到先登记的请求，拒绝补写账本')


def cached_result(ledger_path, task):
    ledger_path = Path(ledger_path)
    if not ledger_path.exists(): return None
    ledger = read_json(ledger_path)
    prior = next((item for item in ledger['attempts'] if item['taskHash'] == task['taskHash']), None)
    if prior is None: return None
    cache = ledger_path.parent / 'results' / (task['taskHash'] + '.json')
    if prior['status'] != 'PASSED_STRUCTURE' or not cache.exists():
        raise ValueError('任务已有失败或中断记录，没有隐式重试；请保留证据并先处理原因')
    record = read_json(cache)
    if digest(record) != prior.get('resultHash') or record.get('inputHash') != task['inputHash'] or record.get('taskHash') != task['taskHash']:
        raise ValueError('缓存结果摘要或输入不符，不能复用')
    return record


def call_once(config, settings, prompt, payload, record):
    """评测脚本直接发一个受限协议请求，不创建业务会话，不安装重试器。"""
    from urllib.request import Request, build_opener, HTTPRedirectHandler
    from urllib.error import HTTPError
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl): return None
    body = {'model': settings['model'], 'temperature': settings['temperature'], 'max_tokens': settings['max_tokens'], 'response_format': {'type': 'json_object'}, 'messages': [{'role': 'system', 'content': prompt}, {'role': 'user', 'content': json.dumps(payload, ensure_ascii=False, separators=(',', ':'))}]}
    if settings['reasoning_effort']: body['reasoning_effort'] = settings['reasoning_effort']
    body.update(settings['extraBody'])
    endpoint = config['baseUrl'].rstrip('/')
    if not endpoint.endswith('/chat/completions'): endpoint += '/chat/completions'
    request = Request(endpoint, data=json.dumps(body, ensure_ascii=False).encode(), headers={'Authorization': 'Bearer ' + config['apiKey'], 'Content-Type': 'application/json'}, method='POST')
    try:
        with build_opener(NoRedirect()).open(request, timeout=settings['timeoutSeconds']) as response:
            raw = response.read(2_000_001)
        record['rawResponse'] = raw[:2_000_000].decode('utf-8', 'replace')
        if len(raw) > 2_000_000: raise ValueError('上游响应超出2MB上限')
    except HTTPError as error:
        code = error.code
        error.close()
        raise ValueError('上游HTTP请求失败，状态码' + str(code) + '；未读取或显示上游错误正文') from None
    except (OSError, TimeoutError):
        raise ValueError('模型网络或等待失败；没有自动重试，也未输出地址或凭证') from None
    envelope = strict_json(record['rawResponse'])
    usage = envelope.get('usage')
    if usage is not None and not isinstance(usage, dict):
        raise ValueError('模型用量统计必须是对象或空值')
    record['usage'] = usage if usage is not None else {}
    returned_model = envelope.get('model')
    if not isinstance(returned_model, str) or not returned_model.strip():
        raise ValueError('上游实际返回模型标识缺失或无效')
    record['returnedModel'] = returned_model
    fingerprint = envelope.get('system_fingerprint')
    if fingerprint is not None and not isinstance(fingerprint, str):
        raise ValueError('模型系统指纹必须是字符串或空值')
    record['systemFingerprint'] = fingerprint
    choices = envelope.get('choices')
    if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
        raise ValueError('模型没有返回恰好一个结果')
    record['finishReason'] = choices[0].get('finish_reason')
    message = choices[0].get('message')
    if not isinstance(message, dict):
        raise ValueError('模型结果中的message必须是对象')
    record['outputText'] = message.get('content')
    if record['finishReason'] != 'stop' or not isinstance(record['outputText'], str):
        raise ValueError('模型输出未完整结束或没有文本JSON')
    record['output'] = strict_json(record['outputText'])
    return record['output']


def assert_returned_models(before_records, after_records):
    def by_case_and_stage(records):
        models = {}
        for item in records:
            if not isinstance(item, dict):
                raise ValueError('实际返回模型记录无效，拒绝混组')
            ident, stage, model = item.get('caseId'), item.get('stage'), item.get('returnedModel')
            if not isinstance(ident, str) or not ident.strip() or stage not in STAGES or not isinstance(model, str) or not model.strip():
                raise ValueError('上游实际返回模型标识或案例身份缺失，拒绝混组')
            key = (ident, stage)
            if key in models:
                raise ValueError('实际返回模型记录的案例与阶段重复，拒绝混组')
            models[key] = model
        if not models:
            raise ValueError('实际返回模型记录为空，拒绝混组')
        return models
    if by_case_and_stage(before_records) != by_case_and_stage(after_records):
        raise ValueError('同一案例与阶段的实际返回模型不同或记录缺失，拒绝混组')


def require_pilot_passed(pilot_baseline, pilot_candidate, current):
    if not pilot_baseline or not pilot_candidate:
        raise ValueError('完整24例必须先完成首批pilot原/新策略人工对照')
    before = read_json(Path(pilot_baseline) / 'run.json')
    after = read_json(Path(pilot_candidate) / 'run.json')
    if before.get('batch') != 'pilot' or after.get('batch') != 'pilot':
        raise ValueError('扩展验证需要首批pilot记录')
    assert_comparable(before, after)
    if not compare_runs(pilot_baseline, pilot_candidate)['passed']:
        raise ValueError('首批pilot尚未通过人工质量对照')
    reference = before if current['strategy']['kind'] == 'baseline' else after
    for key in ('suiteHash', 'sourceHash', 'parametersHash', 'strategy', 'runnerHash'):
        if reference[key] != current[key]:
            raise ValueError('完整批次与首批pilot样本、来源、模型或策略不同，不能扩组')


def run_real(directory, config, ledger_path=None, baseline_dir=None, max_attempts=12, pilot_baseline=None, pilot_candidate=None):
    directory = Path(directory)
    manifest = read_json(directory / 'run.json')
    suite = load_suite()
    verify_manifest(directory, manifest, suite)
    if parameters(config) != manifest['parameters']:
        raise ValueError('当前模型配置或参数与准备协议不一致；没有发起请求')
    if type(max_attempts) is not int or max_attempts < 1 or max_attempts > (12 if manifest['batch'] == 'pilot' else 36):
        raise ValueError('首批最多12次新增请求；扩展批最多36次，不能覆盖总96次限制')
    if manifest['strategy']['kind'] == 'candidate':
        if not baseline_dir: raise ValueError('新策略真实对照必须提供已人工确认的原策略基线')
        baseline = read_json(Path(baseline_dir) / 'run.json')
        if baseline['strategy']['kind'] != 'baseline': raise ValueError('人工基线必须是冻结的原策略')
        assert_comparable(baseline, manifest)
        check_human_gate(baseline_dir, baseline)
    if manifest['batch'] == 'full':
        require_pilot_passed(pilot_baseline, pilot_candidate, manifest)
    ledger_path = Path(ledger_path or default_ledger())
    new_attempts = reused = 0
    for task in manifest['tasks']:
        result_path = directory / 'results' / (task['taskHash'] + '.json')
        record = cached_result(ledger_path, task)
        if record is not None:
            write_json(result_path, record)
            reused += 1
            continue
        if new_attempts >= max_attempts:
            break
        protocol_hash = digest({'taskHash': task['taskHash'], 'strategy': manifest['strategy'], 'parameters': manifest['parameters']})
        reservation = reserve_attempt(ledger_path, manifest['runId'], task['taskHash'], protocol_hash)
        new_attempts += 1
        record = {'taskHash': task['taskHash'], 'inputHash': task['inputHash'], 'runId': manifest['runId'], 'runnerHash': manifest['runnerHash'], 'caseId': task['caseId'], 'stage': task['stage'], 'attemptNumber': reservation['number'], 'protocolHash': protocol_hash, 'startedAt': reservation['reservedAt'], 'status': 'FAILED'}
        failure = None
        try:
            prompt = (directory / (task['stage'].lower() + '.txt')).read_text(encoding='utf-8')
            output = call_once(config, manifest['parameters'][task['stage']], prompt, task['input'], record)
            mechanical = validate_output(task['stage'], task['input'], output)
            if task['stage'] == 'FOLLOWUP': record['effectiveAction'] = mechanical['effectiveAction']
            if task['stage'] == 'FOLLOWUP' and manifest['strategy']['kind'] == 'candidate':
                validate_diagnosis(output.get('diagnosis'), task['input']['answer'], output.get('action'))
            record.update(status='PASSED_STRUCTURE', structureValidation={'passed': True, 'version': RUNNER_SCHEMA, 'referenceProductionValidatorHash': manifest['strategy']['validatorHash'], 'scope': 'Python公共机械子集；没有执行Java生产校验器或完整Service' })
        except Exception as error:
            # 已登记请求的解析或校验异常也要归档；用户中断不在此捕获范围内。
            failure = '结果未通过结构校验或网络请求：' + str(error) if isinstance(error, ValueError) else '上游响应解析或校验异常，已保留原始结果'
            record.update(error=failure, structureValidation={'passed': False, 'version': RUNNER_SCHEMA, 'referenceProductionValidatorHash': manifest['strategy']['validatorHash'], 'scope': 'Python公共机械子集；没有执行Java生产校验器或完整Service' })
        record['finishedAt'] = now()
        write_json(ledger_path.parent / 'results' / (task['taskHash'] + '.json'), record)
        write_json(result_path, record)
        finish_attempt(ledger_path, task['taskHash'], record)
        if failure:
            raise ValueError(task['caseId'] + '/' + task['stage'] + '已停批；失败计入预算。' + failure)
    return {'newAttempts': new_attempts, 'reusedResults': reused, 'totalReserved': len(read_json(ledger_path)['attempts']) if ledger_path.exists() else 0}


def export_review_pack(directory, suite):
    directory = Path(directory)
    manifest = read_json(directory / 'run.json')
    verify_manifest(directory, manifest, suite)
    records = completed_results(directory, manifest)
    by_case = {case['id']: case for case in suite['cases']}
    lines = ['# 固定样本人工评阅包', '', '这是模型结构验证后的原始结果。结构通过不能替代人工语义评分；请逐例填写scores.json，再手动确认human-gate.json。', '']
    for ident in manifest['caseIds']:
        case = by_case[ident]
        lines += ['## ' + ident + '：' + case['title'], '', '**原问题：** ' + case['question'], '', '**候选回答：** ' + case['answer'], '', '**历史问答：** ' + json.dumps(case['history'], ensure_ascii=False), '', '**已覆盖考点：** ' + '；'.join(case['coveredPoints']), '', '**预期合理行为：** ' + '；'.join(case['expectedBehavior']), '', '**禁止行为：** ' + '；'.join(case['forbiddenBehavior']), '']
        for record in records:
            if record['caseId'] == ident:
                lines += ['### ' + ('追问输出' if record['stage'] == 'FOLLOWUP' else '结束后复盘'), '', '```json', json.dumps(record['output'], ensure_ascii=False, indent=2), '```', '']
    (directory / 'review.md').write_text('\n'.join(lines), encoding='utf-8')
    gate = read_json(directory / 'human-gate.json')
    result_hash = digest(records)
    if gate.get('confirmedByHuman') is True and gate.get('resultsHash') != result_hash:
        raise ValueError('结果变化，不能覆盖既有人工确认')
    gate['resultsHash'] = result_hash
    write_json(directory / 'human-gate.json', gate)
    return {'caseCount': len(manifest['caseIds']), 'resultsHash': result_hash}


def compare_runs(baseline_dir, candidate_dir):
    before = read_json(Path(baseline_dir) / 'run.json')
    after = read_json(Path(candidate_dir) / 'run.json')
    if before['strategy']['kind'] != 'baseline' or after['strategy']['kind'] != 'candidate':
        raise ValueError('原/新策略顺序错误')
    suite = load_suite()
    verify_manifest(baseline_dir, before, suite)
    verify_manifest(candidate_dir, after, suite)
    assert_comparable(before, after)
    assert_returned_models(completed_results(baseline_dir, before), completed_results(candidate_dir, after))
    outcome = compare_scores(check_human_gate(baseline_dir, before), check_human_gate(candidate_dir, after))
    outcome.update(baselineRunId=before['runId'], candidateRunId=after['runId'], runnerSchema=RUNNER_SCHEMA, scope='只支持本批固定合成输入和已记录模型配置，不能外推全部真实面试')
    return outcome


def export_blind_pack(baseline_dir, candidate_dir, output):
    output = Path(output)
    if output.exists(): raise ValueError('匿名评阅输出目录已存在，不能覆盖人工记录')
    before = read_json(Path(baseline_dir) / 'run.json')
    after = read_json(Path(candidate_dir) / 'run.json')
    assert_comparable(before, after)
    suite = load_suite()
    verify_manifest(baseline_dir, before, suite)
    verify_manifest(candidate_dir, after, suite)
    left, right = completed_results(baseline_dir, before), completed_results(candidate_dir, after)
    assert_returned_models(left, right)
    mappings, entries = {}, []
    for ident in before['caseIds']:
        reverse = bool(secrets.randbits(1))
        mappings[ident] = {'A': 'candidate' if reverse else 'baseline', 'B': 'baseline' if reverse else 'candidate'}
        outputs = {}
        for label, records in zip(('A', 'B'), (right, left) if reverse else (left, right)):
            outputs[label] = {}
            for record in records:
                if record['caseId'] == ident:
                    value = copy.deepcopy(record['output'])
                    value.pop('diagnosis', None)
                    outputs[label][record['stage']] = value
        task = next(task for task in before['tasks'] if task['caseId'] == ident)
        entries.append({'caseId': ident, 'input': task['input'], 'outputs': outputs})
    output.mkdir(parents=True)
    write_json(output / 'blind-review.json', {'instruction': 'A/B按案例独立打乱，只呈现问答行为；诊断与策略元数据另留原始结果。基线已先行人工标注，这份匿名包用于后续交叉核对，不声称完全双盲。', 'cases': entries})
    write_json(output / 'blind-scores.json', {'confirmedByHuman': False, 'reviewer': '', 'cases': [{'caseId': ident, 'A': {metric: {'value': None, 'reason': ''} for metric in METRICS}, 'B': {metric: {'value': None, 'reason': ''} for metric in METRICS}} for ident in before['caseIds']]})
    write_json(output.parent / (output.name + '-private-key.json'), mappings)
    return {'caseCount': len(entries)}



def main(argv=None):
    parser = argparse.ArgumentParser(description='固定面试评测；不带子命令只做离线校验')
    sub = parser.add_subparsers(dest='command')
    sub.add_parser('validate', help='离线校验24例、来源及原策略摘要')
    prepare = sub.add_parser('prepare', help='离线生成固定输入、协议及空白人工评分表')
    prepare.add_argument('--strategy', choices=('baseline', 'candidate'), required=True)
    prepare.add_argument('--batch', choices=('pilot', 'full'), default='pilot')
    prepare.add_argument('--out', type=Path, required=True)
    prepare.add_argument('--config', type=Path)
    prepare.add_argument('--use-env', action='store_true', help='显式读取已导出的启动环境，仍不发请求')
    prepare.add_argument('--candidate-prompts', type=Path)
    real = sub.add_parser('real', help='显式执行受限真实请求，会消耗模型费用')
    real.add_argument('--run', type=Path, required=True)
    real.add_argument('--config', type=Path)
    real.add_argument('--baseline', type=Path)
    real.add_argument('--max-attempts', type=int, default=12)
    real.add_argument('--pilot-baseline', type=Path)
    real.add_argument('--pilot-candidate', type=Path)
    pack = sub.add_parser('pack', help='离线导出人工评阅包，不替人工确认')
    pack.add_argument('--run', type=Path, required=True)
    blind = sub.add_parser('blind', help='离线导出匿名A/B对照与空白评分表')
    compare = sub.add_parser('compare', help='按已完成的人工评分进行离线对照')
    for command in (blind, compare):
        command.add_argument('--baseline', type=Path, required=True)
        command.add_argument('--candidate', type=Path, required=True)
        command.add_argument('--out', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        suite = load_suite()
        if args.command in (None, 'validate'):
            print('离线校验通过：24个固定合成案例，四类各6例；首批6例覆盖四类；未调用模型。')
        elif args.command == 'prepare':
            config = load_config(args.config) if args.config or args.use_env or os.environ.get('INTERVIEW_AGENT_EVAL_CONFIG') else None
            manifest = prepare_run(suite, args.out, args.strategy, args.batch, config, args.candidate_prompts)
            print('离线准备完成：' + str(len(manifest['tasks'])) + '个固定请求；没有调用模型。')
        elif args.command == 'real':
            if '.evidence' not in args.run.resolve().parts:
                raise ValueError('真实请求结果只允许保存在本机忽略目录.evidence内')
            outcome = run_real(args.run, load_config(args.config), baseline_dir=args.baseline, max_attempts=args.max_attempts, pilot_baseline=args.pilot_baseline, pilot_candidate=args.pilot_candidate)
            print('本次新增请求' + str(outcome['newAttempts']) + '次；复用结果' + str(outcome['reusedResults']) + '项；共享总账已登记' + str(outcome['totalReserved']) + '/96次。结构通过仍需人工评分。')
        elif args.command == 'pack':
            export_review_pack(args.run, suite)
            print('人工评阅包已生成；评分和人工确认仍留空，等待真实人工填写。')
        elif args.command == 'blind':
            export_blind_pack(args.baseline, args.candidate, args.out)
            print('匿名A/B评阅包已生成；私有对应表保存在输出目录同级，不与评分者共享。')
        elif args.command == 'compare':
            outcome = compare_runs(args.baseline, args.candidate)
            write_json(args.out, outcome)
            print('人工对照' + ('通过' if outcome['passed'] else '未通过') + '：' + ('整体和各类不退步，基线问题按记录核对。' if outcome['passed'] else '；'.join(outcome['reasons'])))
            return 0 if outcome['passed'] else 1
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print('评测未执行或已停批：' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
