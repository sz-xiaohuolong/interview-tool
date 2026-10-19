#!/usr/bin/env python3
"""多轮固定历史的发现试跑；默认离线，与原24例评测及账本隔离。"""
import argparse
import copy
from contextlib import contextmanager
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import secrets
import signal
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'evals/interview-agent/multiturn'
HELPER = ROOT / 'scripts/interview-agent-eval.py'
spec = importlib.util.spec_from_file_location('interview_fixed_eval_helpers', HELPER)
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)

SCHEMA = 'interview-continuity-eval/1'
LEDGER_SCHEMA = 'interview-continuity-ledger/1'
SCOPE_ID = 'continuity-discovery-2026-10-19'
LIMIT = 12
ORDER = [f'CONT-{mode}{number:02d}' for number in range(1, 7) for mode in ('C', 'Q')]
SCENARIOS = {'NO_REPEAT', 'VALID_DEEPENING', 'EXPLICIT_CORRECTION',
             'UNRESOLVED_CONTRADICTION', 'EARLY_RECALL', 'IRRELEVANT_OR_HYPOTHETICAL'}
PRODUCTION_FILES = [
    'backend/src/main/java/cn/interview/validation/InterviewOutputValidator.java',
    'backend/src/main/java/cn/interview/service/interview/InterviewService.java',
    'backend/src/main/java/cn/interview/domain/interview/InterviewInputBudget.java',
    'backend/src/main/java/cn/interview/client/interview/InterviewModelClient.java',
    'backend/src/main/java/cn/interview/client/transport/OpenAiJsonClient.java',
]


def load_suite(folder=ASSETS):
    folder = Path(folder)
    suite = shared.read_json(folder / 'cases.json')
    source_path = folder.parent / 'sources.json'
    source_doc = shared.read_json(source_path)
    cases = suite['cases']
    if suite.get('synthetic') is not True or len(cases) != 12 or {c['id'] for c in cases} != set(ORDER):
        raise ValueError('多轮素材必须为12个固定合成案例')
    sources = {s['id']: s for s in source_doc['sources']}
    for s in sources.values():
        if shared.digest(s['excerpt'].encode()) != s['excerptSha256']:
            raise ValueError('来源短引摘要不符')
    identities = set()
    for mode in ('COMPREHENSIVE', 'QUICK'):
        group = [c for c in cases if c['dialogue']['sessionType'] == mode]
        if len(group) != 6 or {c['scenario'] for c in group} != SCENARIOS:
            raise ValueError('两种模式必须各覆盖六类情境')
    for c in cases:
        d, o = c['dialogue'], c['oracle']
        turns = d['turns']
        if len(turns) != 5 or [t['turn'] for t in turns] != list(range(1, 6)) or o['checkpointTurn'] != 5:
            raise ValueError('每例必须五轮且只有第5轮关键点')
        if not set(d['sourceIds']) <= sources.keys() or d['observationWindow']['followUpCount'] != 0:
            raise ValueError('来源或观察窗口无效')
        for t in turns:
            if t['questionKind'] != 'MAIN' or not t['question'].strip() or not t['answer'].strip():
                raise ValueError('合成主问题或回答缺失')
            for key in ('questionId', 'rootQuestionId', 'questionMessageId', 'answerMessageId'):
                if not isinstance(t[key], str) or not t[key] or t[key] in identities:
                    raise ValueError('问题或消息身份重复')
                identities.add(t[key])
        for e in o['evidence']:
            if type(e['turn']) is not int or not 1 <= e['turn'] <= 5:
                raise ValueError('证据轮次无效')
            turn = turns[e['turn'] - 1]
            if e['answerMessageId'] != turn['answerMessageId'] or not e['quote'] or e['quote'] not in turn['answer']:
                raise ValueError('人工证据没有逐字对应原答')
    suite['_sources'] = sources
    suite['_suiteHash'] = shared.digest((folder / 'cases.json').read_bytes())
    suite['_sourceHash'] = shared.digest(source_path.read_bytes())
    return suite


def build_input(case, suite, through_turn=5):
    """只投影普通字段；人工预期与关键轮选择不进入模型数据。"""
    if type(through_turn) is not int or not 1 <= through_turn <= 5:
        raise ValueError('观察轮次必须为1至5')
    d = case['dialogue']
    messages = []
    for t in d['turns'][:through_turn]:
        common = {key: t[key] for key in ('questionId', 'rootQuestionId', 'questionKind')}
        for role, field, ident in [('ASSISTANT', 'question', 'questionMessageId'), ('USER', 'answer', 'answerMessageId')]:
            m = dict(common, role=role, content=t[field], id=t[ident])
            if d['sessionType'] == 'COMPREHENSIVE':
                m['stage'] = 'TECHNICAL'
            messages.append(m)
    keys = ('id', 'title', 'url', 'kind', 'stale', 'fetchedAt', 'excerpt')
    payload = {
        'role': d['role'], 'level': d['level'], 'sessionType': d['sessionType'],
        'summary': {}, 'jd': '', 'messages': messages,
        'currentQuestion': copy.deepcopy(messages[-2]), 'answer': messages[-1]['content'],
        'followUpCount': 0, 'strategyVersion': 1, 'diagnostics': [],
        'sources': [{key: suite['_sources'][ident][key] for key in keys} for ident in d['sourceIds']],
    }
    if d['sessionType'] == 'COMPREHENSIVE':
        payload.update(experienceVersion=1, stage='TECHNICAL', reduceFollowups=False,
                       mainProject={'hypothetical': True, 'brief': ''})
    # 当前素材很小；超限时拒绝，不能把Python自行裁剪冒充Java生产节选。
    if len(json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode('utf-16-le')) // 2 >= 94000:
        raise ValueError('输入达到生产正文预算，需要单独核对真实节选，未发送请求')
    return payload


def parameters(config=None):
    base = shared.parameters(config)
    settings = dict(base['FOLLOWUP'], timeoutSeconds=30)
    return {'providerIdentity': base['providerIdentity'], 'FOLLOWUP': settings,
            'wholeDeadlineSeconds': 31}


def identity(suite, params, prompt):
    return {
        'schema': SCHEMA, 'scopeId': SCOPE_ID,
        'suiteHash': suite['_suiteHash'], 'sourceHash': suite['_sourceHash'],
        'parameters': params, 'runnerHash': shared.digest(Path(__file__).read_bytes()),
        'helperHash': shared.digest(HELPER.read_bytes()),
        'promptHash': shared.digest(prompt.encode()),
        'productionSnapshotHashes': {name: shared.digest((ROOT / name).read_bytes()) for name in PRODUCTION_FILES},
        'experiment': {'diagnostics': '历史诊断未生成', 'summary': '刻意为空，非生产准备结果',
                       'planCompletionProtocol': False, 'productionExecuted': False},
    }


def make_tasks(suite, protocol):
    by_id = {c['id']: c for c in suite['cases']}
    tasks = []
    for ident in ORDER:
        case = by_id[ident]
        payload = build_input(case, suite)
        raw_hash = shared.digest(case['dialogue'])
        task_id = {'caseId': ident, 'stage': 'FOLLOWUP', 'checkpointTurn': 5,
                   'rawDialogueHash': raw_hash, 'inputHash': shared.digest(payload),
                   'protocolHash': shared.digest(protocol)}
        tasks.append(dict(task_id, taskHash=shared.digest(task_id), input=payload,
                          inputTruncated=False, diagnosticsOrigin='历史诊断未生成'))
    return tasks


def private_text(path, value):
    path = Path(path)
    with open(path, 'w', encoding='utf-8') as stream:
        os.chmod(path, 0o600)
        stream.write(value)


def prepare_run(suite, directory, config=None):
    directory = Path(directory)
    if directory.exists():
        raise ValueError('输出目录已存在，不能覆盖输入或人工记录')
    prompt = (ROOT / 'backend/src/main/resources/prompts/interview/followup.txt').read_text()
    protocol = identity(suite, parameters(config), prompt)
    tasks = make_tasks(suite, protocol)
    manifest = dict(protocol, runId=secrets.token_hex(12), preparedAt=shared.now(),
                    protocolHash=shared.digest(protocol), tasks=tasks)
    directory.mkdir(parents=True, mode=0o700)
    private_text(directory / 'followup.txt', prompt)
    shared.write_json(directory / 'run.json', manifest)
    return manifest


def verify_manifest(directory, manifest, suite):
    prompt = (Path(directory) / 'followup.txt').read_text()
    protocol = identity(suite, manifest['parameters'], prompt)
    if any(manifest.get(key) != value for key, value in protocol.items()) or manifest.get('protocolHash') != shared.digest(protocol):
        raise ValueError('样本、来源、Prompt、运行器或生产参考源码与准备协议不符')
    if manifest.get('tasks') != make_tasks(suite, protocol):
        raise ValueError('关键点输入或任务身份与准备协议不符')
    return prompt


def verify_archived_manifest(directory, manifest, suite, ledger):
    """离线归档校验冻结身份；当前源码一致性仍由真实执行入口负责。"""
    fields = ('schema', 'scopeId', 'suiteHash', 'sourceHash', 'parameters', 'runnerHash',
              'helperHash', 'promptHash', 'productionSnapshotHashes', 'experiment')
    protocol = {key: manifest[key] for key in fields}
    if manifest.get('protocolHash') != shared.digest(protocol):
        raise ValueError('历史协议摘要不符，不能归档')
    if ledger.get('attempts') and (
            ledger.get('schema') != LEDGER_SCHEMA or ledger.get('scopeId') != SCOPE_ID
            or ledger.get('limit') != LIMIT or ledger.get('protocolHash') != manifest['protocolHash']):
        raise ValueError('历史协议与共享账本不符，不能归档或重建运行身份')
    prompt = (Path(directory) / 'followup.txt').read_text()
    expected = {'schema': SCHEMA, 'scopeId': SCOPE_ID, 'suiteHash': suite['_suiteHash'],
                'sourceHash': suite['_sourceHash'], 'promptHash': shared.digest(prompt.encode())}
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError('历史样本、来源或Prompt与冻结协议不符')
    if manifest.get('tasks') != make_tasks(suite, protocol):
        raise ValueError('历史关键点输入或任务身份与冻结协议不符')
    attempts = ledger.get('attempts', [])
    if (len(attempts) > LIMIT or [a.get('number') for a in attempts] != list(range(1, len(attempts) + 1))
            or len({a.get('taskHash') for a in attempts}) != len(attempts)
            or any(a.get('taskHash') not in {t['taskHash'] for t in manifest['tasks']} for a in attempts)):
        raise ValueError('历史共享账本的任务或尝试序号无效')


def default_ledger():
    return shared.default_ledger().parent.parent / 'interview-continuity/ledger.json'


@contextmanager
def deadline(seconds):
    """限制本CLI等待；不声称执行Java Service的截止和备用机制。"""
    previous = signal.getsignal(signal.SIGALRM)
    if signal.getitimer(signal.ITIMER_REAL)[0]:
        raise ValueError('已有截止定时器，不能开始模型等待')
    def expired(*_):
        raise ValueError('单次发现试跑达到整体等待截止，未自动重试')
    signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def actual_usage(record):
    usage = record.get('usage', {})
    output = {}
    for field, upstream in [('inputTokens', 'prompt_tokens'), ('outputTokens', 'completion_tokens'), ('totalTokens', 'total_tokens')]:
        value = usage.get(upstream)
        output[field] = value if type(value) is int and value >= 0 else None
    return output


def read_ledger(path):
    ledger = shared.read_json(path) if path.exists() else {
        'schema': LEDGER_SCHEMA, 'scopeId': SCOPE_ID, 'limit': LIMIT, 'state': 'ACTIVE', 'attempts': [],
    }
    if ledger.get('schema') != LEDGER_SCHEMA or ledger.get('limit') != LIMIT:
        raise ValueError('多轮共享账本版本或12次预算被改变')
    attempts = ledger.get('attempts')
    if not isinstance(attempts, list) or len(attempts) > LIMIT:
        raise ValueError('共享预算记录无效')
    if ledger.get('state') == 'STOPPED' or any(a.get('status') in ('FAILED', 'INTERRUPTED', 'RESERVED') for a in attempts):
        raise ValueError('首批已停批或有未完成中断登记，不能跳过、重试或换身份续发')
    if ledger.get('state') not in ('ACTIVE', 'COMPLETE') or any(a.get('status') != 'PASSED_STRUCTURE' for a in attempts):
        raise ValueError('共享账本状态无效，停止执行')
    if len({a.get('taskHash') for a in attempts}) != len(attempts) or [a.get('number') for a in attempts] != list(range(1, len(attempts) + 1)):
        raise ValueError('共享账本身份或尝试序号无效')
    return ledger


def run_real(directory, config, ledger_path=None, max_attempts=12):
    directory = Path(directory)
    if '.evidence' not in directory.resolve().parts:
        raise ValueError('真实结果只允许保存到本机忽略目录.evidence内')
    suite = load_suite()
    manifest = shared.read_json(directory / 'run.json')
    prompt = verify_manifest(directory, manifest, suite)
    if parameters(config) != manifest['parameters']:
        raise ValueError('模型配置与准备协议不符，没有发起请求')
    if type(max_attempts) is not int or not 1 <= max_attempts <= LIMIT:
        raise ValueError('首批新增总预算最多12次')
    ledger_path = Path(ledger_path or default_ledger())
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    with open(ledger_path.with_suffix('.lock'), 'a') as lock:
        os.chmod(lock.name, 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('已有试跑执行者持锁，拒绝并发模型请求') from None
        ledger = read_ledger(ledger_path)
        if ledger.get('protocolHash', manifest['protocolHash']) != manifest['protocolHash']:
            raise ValueError('首批已有不同实验协议；换身份不能重置12次预算或混合续跑')
        ledger['protocolHash'] = manifest['protocolHash']
        new_attempts = reused = 0
        for task in manifest['tasks']:
            # 持有整个执行锁，且每次发送前重新检查持久停批与预算。
            if ledger_path.exists():
                ledger = read_ledger(ledger_path)
            record = shared.cached_result(ledger_path, task)
            if record is not None:
                shared.write_json(directory / 'results' / (task['taskHash'] + '.json'), record)
                reused += 1
                continue
            if new_attempts >= max_attempts:
                break
            if len(ledger['attempts']) >= LIMIT:
                raise ValueError('共享12次总预算已用尽')
            if task['caseId'] != ORDER[len(ledger['attempts'])]:
                raise ValueError('任务偏离固定试跑顺序，不能跳过或重复案例')
            reserved = {'number': len(ledger['attempts']) + 1, 'runId': manifest['runId'],
                        'taskHash': task['taskHash'], 'caseId': task['caseId'],
                        'status': 'RESERVED', 'reservedAt': shared.now()}
            ledger['attempts'].append(reserved)
            shared.write_json(ledger_path, ledger)
            new_attempts += 1
            record = {key: task[key] for key in ('taskHash', 'inputHash', 'rawDialogueHash', 'caseId', 'stage', 'checkpointTurn')}
            record.update(runId=manifest['runId'], protocolHash=manifest['protocolHash'],
                          attemptNumber=reserved['number'], startedAt=reserved['reservedAt'],
                          status='FAILED', effectiveAction=None, businessElapsedMs=None,
                          fallbackTriggered=None, productionExecuted=False, inputTruncated=False,
                          diagnosticsOrigin='历史诊断未生成')
            start = time.monotonic()
            interruption = None
            try:
                with deadline(manifest['parameters']['wholeDeadlineSeconds']):
                    result = shared.call_once(config, manifest['parameters']['FOLLOWUP'],
                                              prompt, task['input'], record)
                    record['transportElapsedMs'] = round((time.monotonic() - start) * 1000)
                    mechanical = shared.validate_output('FOLLOWUP', task['input'], result)
                    shared.validate_diagnosis(result.get('diagnosis'), task['input']['answer'], result.get('action'))
                    record.update(status='PASSED_STRUCTURE', mechanicalAction=mechanical['effectiveAction'])
            except BaseException as error:
                if isinstance(error, (KeyboardInterrupt, SystemExit)):
                    record.update(status='INTERRUPTED', error='人工或进程中断，已计入预算并停批')
                    interruption = error
                elif isinstance(error, Exception):
                    record['error'] = str(error) if isinstance(error, ValueError) else '请求或解析异常，未输出地址或凭证'
                else:
                    record.update(status='INTERRUPTED', error='进程中断，已计入预算并停批')
                    interruption = error
            record.setdefault('transportElapsedMs', round((time.monotonic() - start) * 1000))
            record.update(runnerElapsedMs=round((time.monotonic() - start) * 1000),
                          finishedAt=shared.now(), actualUsage=actual_usage(record),
                          requestedAction=record.get('output', {}).get('action'),
                          outputTruncated=True if record.get('finishReason') == 'length' else (False if record.get('finishReason') == 'stop' else None),
                          structureValidation={'passed': record['status'] == 'PASSED_STRUCTURE',
                                               'scope': '复用Python公共机械子集和有限诊断校验；未执行Java生产校验或Service'})
            shared.write_json(ledger_path.parent / 'results' / (task['taskHash'] + '.json'), record)
            shared.write_json(directory / 'results' / (task['taskHash'] + '.json'), record)
            reserved.update(status=record['status'], finishedAt=record['finishedAt'], resultHash=shared.digest(record))
            ledger['state'] = 'STOPPED' if record['status'] != 'PASSED_STRUCTURE' else ('COMPLETE' if len(ledger['attempts']) == LIMIT else 'ACTIVE')
            shared.write_json(ledger_path, ledger)
            if interruption:
                raise interruption
            if record['status'] != 'PASSED_STRUCTURE':
                raise ValueError(task['caseId'] + '已停批，失败计入预算；' + record['error'])
        return {'newAttempts': new_attempts, 'reusedResults': reused, 'totalReserved': len(ledger['attempts']), 'state': ledger['state']}


def export_review_pack(directory):
    directory = Path(directory)
    suite = load_suite()
    manifest = shared.read_json(directory / 'run.json')
    ledger_path = default_ledger()
    ledger = shared.read_json(ledger_path) if ledger_path.exists() else {'attempts': []}
    verify_archived_manifest(directory, manifest, suite, ledger)
    by_task = {a['taskHash']: a for a in ledger['attempts']}
    cases = {c['id']: c for c in suite['cases']}
    lines = ['# 多轮发现试跑人工评阅包', '',
             '这是空历史诊断的固定输入发现实验。结构通过不代表专业性通过，也不证明生产连续场次或记忆缺陷。请分别填写各项判断，无法观察的内容记为无法判断。', '',
             '本包绑定执行时的历史协议、固定输入和共享账本，不要求当前源码仍与当时一致；真实执行入口继续严格检查当前源码身份。', '']
    annotations, results = [], []
    for task in manifest['tasks']:
        case = cases[task['caseId']]
        path = directory / 'results' / (task['taskHash'] + '.json')
        record = shared.read_json(path) if path.exists() else None
        if task['taskHash'] in by_task and record is None:
            raise ValueError('已登记尝试的原始结果缺失，不能标成未执行')
        if record and (record.get('taskHash') != task['taskHash'] or record.get('inputHash') != task['inputHash'] or record.get('protocolHash') != manifest['protocolHash']):
            raise ValueError('评阅结果与输入协议不匹配')
        if record and (by_task.get(task['taskHash'], {}).get('resultHash') != shared.digest(record)
                       or by_task[task['taskHash']]['status'] != record['status']):
            raise ValueError('评阅结果摘要与共享账本不符，不能替换原始模型输出')
        status = record['status'] if record else '未执行'
        results.append({'taskHash': task['taskHash'], 'resultHash': shared.digest(record) if record else None})
        lines += ['## ' + case['id'] + '：' + case['title'], '', '**执行状态：** ' + status, '',
                  '**原始五轮对话：**', '', '```json', json.dumps(case['dialogue'], ensure_ascii=False, indent=2), '```', '',
                  '**模型原始文本：**', '', '```text', record.get('outputText', '未收到模型正文') if record else '未执行', '```', '',
                  '**执行记录：**', '', '```json', json.dumps(record, ensure_ascii=False, indent=2) if record else 'null', '```', '',
                  '**人工判据：**', '', '```json', json.dumps(case['oracle'], ensure_ascii=False, indent=2), '```', '']
        pending = '无法判断' if record else '未执行'
        annotations.append({'caseId': case['id'], 'status': pending, 'reason': '',
                            'actionAllowed': {'status': pending, 'reason': ''},
                            'required': [{'rule': r, 'status': pending, 'reason': ''} for r in case['oracle']['requiredBehavior']],
                            'forbidden': [{'rule': r, 'status': pending, 'reason': ''} for r in case['oracle']['forbiddenBehavior']],
                            'blockers': None, 'candidateIssues': []})
    results_hash = shared.digest(results)
    stale = False
    for filename in ('scores.json', 'human-gate.json'):
        path = directory / filename
        if path.exists():
            previous = shared.read_json(path)
            if previous.get('runId') != manifest['runId'] or previous.get('protocolHash') != manifest['protocolHash']:
                raise ValueError('已有人工文件属于不同输入或运行身份，不能覆盖')
            changed = previous.get('resultsHash') != results_hash
            if changed and previous.get('confirmedByHuman') is True:
                raise ValueError('已有人工确认已过期；不能将新结果覆盖到旧确认旁，请先由人工核对')
            stale = stale or changed
    if stale:
        lines[2] += ' 本次有新增结果，既有人工标注绑定已过期；已保留备注，不能把旧标注当作当前结果的确认。'
    shared.write_json(directory / 'review-index.json', {'runId': manifest['runId'], 'protocolHash': manifest['protocolHash'], 'results': results, 'resultsHash': results_hash, 'annotationsStale': stale,
                                                       'archiveRunnerHash': shared.digest(Path(__file__).read_bytes()),
                                                       'executionRunnerHash': manifest['runnerHash']})
    private_text(directory / 'review.md', '\n'.join(lines))
    if not (directory / 'scores.json').exists():
        shared.write_json(directory / 'scores.json', {'runId': manifest['runId'], 'protocolHash': manifest['protocolHash'], 'resultsHash': results_hash, 'confirmedByHuman': False, 'reviewer': '', 'cases': annotations})
    if not (directory / 'human-gate.json').exists():
        shared.write_json(directory / 'human-gate.json', {'runId': manifest['runId'], 'protocolHash': manifest['protocolHash'],
                                                        'resultsHash': results_hash, 'confirmedByHuman': False,
                                                        'reviewer': '', 'confirmedAt': ''})
    return {'recorded': sum(r['resultHash'] is not None for r in results), 'total': len(results), 'annotationsStale': stale}


def main(argv=None):
    parser = argparse.ArgumentParser(description='多轮发现试跑；默认离线，真实调用使用独立12次共享预算')
    commands = parser.add_subparsers(dest='command')
    prepare = commands.add_parser('prepare', help='离线准备固定输入与协议')
    prepare.add_argument('--out', type=Path, required=True)
    prepare.add_argument('--config', type=Path)
    prepare.add_argument('--use-env', action='store_true')
    real = commands.add_parser('real', help='显式发送最多12次单次追问，首次错误停批')
    real.add_argument('--run', type=Path, required=True)
    real.add_argument('--config', type=Path)
    real.add_argument('--max-attempts', type=int, default=12)
    pack = commands.add_parser('pack', help='导出含未执行项的人工评阅包，不代填人工结论')
    pack.add_argument('--run', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        suite = load_suite()
        if args.command is None:
            print('离线检查通过：12个合成案例、60轮、每例1个关键点；没有读取连接或调用模型。')
        elif args.command == 'prepare':
            config = shared.load_config(args.config) if args.config or args.use_env else None
            prepare_run(suite, args.out, config)
            print('12个FOLLOWUP输入与协议已准备；未调用模型，人工预期不进入请求。')
        elif args.command == 'real':
            result = run_real(args.run, shared.load_config(args.config), max_attempts=args.max_attempts)
            print(f"本次新增{result['newAttempts']}次、复用{result['reusedResults']}项，共享账本{result['totalReserved']}/12；仅结构检查，等待人工判断。")
        elif args.command == 'pack':
            result = export_review_pack(args.run)
            print(f"人工包已导出：{result['recorded']}/{result['total']}项有执行记录；人工结论留空，已有人工备注保留。")
            if result['annotationsStale']:
                print('既有人工标注绑定已过期，不能用于当前结果确认。')
        return 0
    except KeyboardInterrupt:
        print('执行已中断，登记的尝试保留且不能自动重试。', file=sys.stderr)
        return 130
    except (ValueError, OSError, KeyError, TypeError) as error:
        print('未执行或已停批：' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
