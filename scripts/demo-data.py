#!/usr/bin/env python3
"""在全新的独立目录创建虚构演示样本；重复启动保留已有编辑与模型配置。"""
import argparse
import hashlib
import json
import shutil
import socket
import sqlite3
import tempfile
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MARKER = '.interview-demo.json'
IDENTITY = {'kind': 'interview-tool-demo', 'schemaVersion': 1}


def identifier(name):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'interview-tool/fictional-demo/' + name))


def encode(value):
    return json.dumps(value, ensure_ascii=False, indent=2)


def seed(data):
    resources = ROOT / 'backend/src/main/resources'
    schema = (resources / 'schema.sql').read_text()
    migration = (resources / 'db/migrations/001-training.sql').read_text()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    stamp = lambda days=0: (now - timedelta(days=days)).isoformat().replace('+00:00', 'Z')
    created = stamp(2)
    resume = '林知远｜Java 后端工程师\n虚构演示人物，无真实联系方式。\n三年后端开发经验，熟悉 Java 21、Spring Boot、MySQL、Redis 与消息队列。\n在星河书店负责订单幂等、库存事务与异常补偿；在知识助手项目负责文档检索、引用校验与离线评测。\n希望把业务问题转化为可验证的技术方案。以上经历仅供产品演示。'
    order = '星河书店订单服务（虚构项目）\n背景：促销期间存在重复提交与库存竞争。\n个人职责：设计订单创建接口、唯一幂等键、数据库事务和失败补偿。\n方案：订单与库存变更放在同一事务；唯一约束拒绝重复请求；通过事务消息表记录待发送事件，消费者按事件 ID 去重。\n验证：使用重复请求、并发扣减和异常注入测试检查边界。\n结果：合成测试中未产生重复订单；线上容量与收益尚未测量，不作实际效果承诺。'
    rag = '知行知识助手（虚构项目）\n面向团队技术文档的检索问答工具。个人负责分段、混合检索、重排与引用定位。\n以固定问题集检查命中率与答案可追溯性；权限过滤在检索前执行；证据不足时明确表示无法回答。\n当前为合成原型，暂无生产业务指标。'
    monitoring = '云帆任务平台（虚构项目）\n负责异步任务状态机、失败重试与运行观测。使用状态版本拒绝迟到回调，保留原始输入和操作记录。\n通过超时、进程重启与重复通知验证任务恢复；所有描述仅用于演示。'
    materials = [
        ('resume', 'RESUME', '林知远 · Java 后端简历', resume),
        ('orders', 'PROJECT', '星河书店 · 订单与库存服务', order),
        ('rag', 'PROJECT', '知行 · 企业知识助手', rag),
        ('jobs', 'PROJECT', '云帆 · 异步任务平台', monitoring),
        ('jd', 'JD', '星河科技 · Java 后端工程师', '虚构演示岗位\n负责订单服务与业务 API，熟悉 Spring Boot、关系数据库、事务和索引。\n能解释缓存一致性、消息重复消费与服务故障恢复，具备清晰的技术表达能力。'),
    ]
    (data / 'uploads').mkdir()
    sessions_dir = data / 'v3/sessions'
    sessions_dir.mkdir(parents=True)
    with sqlite3.connect(data / 'interview.db') as db:
        db.execute('PRAGMA foreign_keys=ON')
        db.executescript(schema + '\n' + migration)
        db.execute('INSERT INTO v2_schema_migrations VALUES(1,?,?)', (hashlib.sha256(migration.encode()).hexdigest(), created))
        for key, kind, name, content in materials:
            db.execute('INSERT INTO materials VALUES(?,?,?,?,?,?)', (identifier(key), kind, name, content, created, created))
        for key, title, content in [('resume', '林知远-演示简历.md', resume), ('orders', '订单服务-设计说明.md', order)]:
            storage = identifier(key + '-document') + '.md'
            (data / 'uploads' / storage).write_text(content)
            db.execute('INSERT INTO documents VALUES(?,?,?,?,?,?,?)', (identifier(key + '-document'), identifier(key), title, storage, content, 0, created))

        questions = [
            ('INTRO', '请用一分钟介绍你与 Java 后端岗位最相关的经历。', '我叫林知远，主要使用 Java 和 Spring Boot。最近在虚构的星河书店项目中负责订单接口，重点解决重复提交与库存一致性，也做过文档检索问答原型。'),
            ('PROJECT', '订单创建时，如何避免用户重复点击产生多笔订单？', '我会给每次业务提交分配幂等键，在数据库建立唯一约束。订单创建和库存更新放进同一事务，重复请求读取已有结果。'),
            ('PROJECT', '如果订单事务提交成功，但消息发送失败，你如何处理？', '我会在本地事务中保存订单和待发送消息，再异步投递。发送失败重试，消费者按事件 ID 去重，并记录失败以便人工排查。'),
            ('TECHNICAL', 'Redis 缓存与数据库的订单状态如何保持一致？', '更新数据库后删除缓存，再让读请求回源。异常时会重试删除，但还需要分析并发读写与旧值回填的时序。'),
            ('TECHNICAL', '检索问答返回了内容相关但证据不足的片段，你会如何处理？', '先做权限过滤，再检查片段是否真的支撑结论。证据不足时让系统明确回答无法判断，并通过固定问题集验证引用和拒答行为。'),
            ('QUESTIONS', '你希望进一步了解目标团队的哪些技术实践？', '我想了解团队如何定义服务可靠性目标、开展故障复盘，以及新人如何参与核心业务的方案评审。'),
        ]
        messages = []
        for index, (stage, question, answer) in enumerate(questions):
            qid = identifier('question-' + str(index))
            for role, text in [('ASSISTANT', question), ('USER', answer)]:
                messages.append({'id': identifier(role + str(index)), 'role': role, 'content': text, 'questionId': qid,
                                 'rootQuestionId': qid, 'questionKind': 'MAIN', 'stage': stage, 'createdAt': created})
        stages = [{'id': key, 'label': label, 'status': 'COMPLETED', 'answeredCount': count}
                  for key, label, count in [('INTRO', '自我介绍', 1), ('PROJECT', '项目深挖', 2), ('TECHNICAL', '技术基础', 2), ('QUESTIONS', '候选人反问', 1)]]
        findings = []
        for index in range(6):
            clear = index != 3
            findings.append({'questionId': identifier('question-' + str(index)), 'answerStatus': 'CLEAR' if clear else 'PARTIAL',
                             'headline': ['岗位经历表达清晰', '幂等边界已说明', '事务消息与消费去重', '缓存并发时序待补强', '引用与拒答边界明确', '反问聚焦工程实践'][index],
                             'observation': '能够结合具体方案说明行动及边界。' if clear else '已提到更新后删除缓存，但尚未展开并发旧值回填的完整时序。',
                             'assessment': '这是一条预置的虚构演示评语。', 'answerQuote': questions[index][2],
                             'status': 'UNVERIFIED', 'sourceIds': [],
                             'suggestion': '继续补充异常与验证方法。' if clear else '画出读写并发时间线，说明缓存失效、重试与数据版本的适用边界。'})
        report = {'version': 2, 'summary': '虚构演示复盘：项目职责与事务边界表达清晰，缓存并发时序仍需补充。本报告为预置样例，未经真实模型评估。',
                  'overview': {'answeredCount': 6, 'mainQuestionCount': 6, 'followUpCount': 0, 'endReason': 'USER_FINISHED'},
                  'findings': findings, 'stages': stages,
                  'detailedAssessment': {'version': 1,
                      'overall': {'text': '虚构演示样例：能围绕问题给出具体方案，并区分个人行动、测试结果与未知信息。', 'questionIds': [identifier('question-1')]},
                      'strengths': [{'text': '将唯一约束、本地事务、事务消息与消费去重放在同一条链路中解释。', 'questionIds': [identifier('question-1'), identifier('question-2')]}],
                      'improvements': [{'text': '需要补充缓存读写竞争中旧值回填的时序与应对策略。', 'questionIds': [identifier('question-3')]}],
                      'nextSteps': [{'text': '重练缓存一致性问题，先画并发时序，再解释方案取舍。', 'questionIds': [identifier('question-3')]}]}}
        comprehensive = {'id': identifier('comprehensive'), 'schemaVersion': 1, 'demo': True, 'mock': False,
                         'sessionType': 'COMPREHENSIVE', 'status': 'REPORT_READY', 'phase': '复盘完成', 'revision': 1,
                         'role': 'Java 后端工程师', 'level': 'JUNIOR', 'durationMinutes': 20, 'plannedQuestionCount': 8,
                         'experienceVersion': 1, 'reviewVersion': 2, 'interviewMode': 'TRAINING', 'stage': 'QUESTIONS',
                         'resumeId': identifier('resume'), 'jdId': identifier('jd'), 'projectId': identifier('orders'),
                         'mainProject': {'id': identifier('orders'), 'name': materials[1][2], 'brief': order, 'hypothetical': True},
                         'createdAt': stamp(1), 'updatedAt': stamp(1), 'messages': messages, 'sources': [],
                         'warnings': ['预置问答与报告均为虚构演示样例，未经真实模型生成或评估。'],
                         'report': report, 'usage': {'calls': 0, 'reportedCalls': 0}, 'questions': [], 'generation': 0}
        (sessions_dir / (comprehensive['id'] + '.json')).write_text(encode(comprehensive))
        quick = dict(comprehensive, id=identifier('quick'), sessionType='QUICK', role='Java 后端工程师',
                     plannedQuestionCount=3, createdAt=stamp(2), updatedAt=stamp(2), messages=messages[2:8],
                     report=dict(report, findings=findings[1:4], overview={'answeredCount': 3, 'mainQuestionCount': 3, 'followUpCount': 0, 'endReason': 'QUESTIONS_COMPLETED'}))
        for key in ['experienceVersion', 'resumeId', 'jdId', 'projectId', 'mainProject']:
            quick.pop(key, None)
        quick['report'].pop('detailedAssessment', None)
        quick['report'].pop('stages', None)
        (sessions_dir / (quick['id'] + '.json')).write_text(encode(quick))

        legacy_id = identifier('project-session')
        legacy_messages = []
        for index in range(3):
            for message in messages[2 + index * 2:4 + index * 2]:
                legacy_messages.append(dict(message, questionIndex=index, followUp=False))
        legacy = {'id': legacy_id, 'demo': True, 'mock': False, 'schemaVersion': 1, 'mode': 'PROJECT',
                  'role': 'Java 后端工程师', 'level': 'JUNIOR', 'style': 'NORMAL', 'durationMinutes': 10,
                  'createdAt': created, 'updatedAt': created, 'endedAt': created, 'status': 'COMPLETED',
                  'questionIndex': 2, 'followUpCount': 0, 'totalQuestions': 3, 'currentTopic': '订单服务',
                  'messages': legacy_messages, 'context': order, 'contextTruncated': False, 'requests': {},
                  'reportStatus': 'READY', 'pendingAction': '', 'error': ''}
        db.execute('INSERT INTO sessions VALUES(?,?,?)', (legacy_id, encode(legacy), created))
        legacy_report = {'sessionId': legacy_id, 'demo': True, 'mock': False, 'createdAt': created,
                         'summary': '虚构演示复盘：幂等与事务消息有清晰思路，缓存并发边界有待补充；预置评分仅作界面样例。',
                         'dimensions': [{'name': name, 'score': score, 'reason': '预置演示评分，未经真实模型评估。'} for name, score in [('技术准确性', 3), ('理解深度', 2), ('表达清晰度', 3), ('追问应对能力', 2)]],
                         'questions': [{'questionIndex': i, 'question': questions[i + 1][1], 'correct': ['明确描述了核心实现思路。'], 'incorrect': [],
                                        'missing': ['需要补充并发与失败时序。'], 'uncertain': [], 'answerOutline': '先说明约束，再描述正常路径、失败恢复与验证方法。',
                                        'knowledgeExplanation': '唯一约束处理重复写入，本地事务保证原子性；跨服务通信需要单独设计重试与去重。', 'evidence': [questions[i + 1][2]]} for i in range(3)],
                         'weaknesses': ['缓存并发读写时序'], 'suggestions': ['画出异常时序，并说明测试方法。']}
        db.execute('INSERT INTO reports VALUES(?,?)', (legacy_id, encode(legacy_report)))

        def insert(table, row):
            row = dict({'schemaVersion': 1, 'revision': 0, 'createdAt': created, 'updatedAt': created, 'demo': True, 'mock': False}, **row)
            columns = [r[1] for r in db.execute('PRAGMA table_info(' + table + ')')]
            def value(column):
                if column == 'payload':
                    return encode(row)
                parts = column.split('_')
                return row.get(parts[0] + ''.join(part.title() for part in parts[1:]))
            db.execute('INSERT INTO ' + table + '(' + ','.join(columns) + ') VALUES(' + ','.join('?' for _ in columns) + ')', [value(c) for c in columns])
            return row

        for kind, texts, expression in [
            ('STAR', dict(situation='虚构项目：促销期间出现重复下单。', task='个人负责明确订单与库存的一致性边界。', action='实现唯一幂等键、本地事务与事务消息表；补充重复请求和失败注入测试。', result='合成测试中每个幂等键只生成一笔订单，暂无生产容量数据。', reflection='重试不等于业务成功，需要同时设计消费去重、告警与人工补偿。'), '我想分享星河书店的订单幂等设计。在这个虚构项目中，我负责订单接口和库存事务。我的行动是先定位重复请求的入口，再通过唯一约束与事务固定业务边界，最后补上消息补偿和故障测试。结果仅来自合成测试，不能代替生产指标。'),
            ('INTRO', dict(targetRole='Java 后端工程师', background='虚构演示人物林知远，三年 Java 开发经验。', experience='订单服务与团队知识助手原型。', contribution='负责订单幂等、事务消息和检索引用校验。', aspiration='希望参与核心业务设计，提升可靠性验证能力。'), '你好，我是林知远，主要使用 Java 与 Spring Boot。最近在订单服务中负责接口幂等和事务消息，也实现过检索问答原型。我关注方案的边界与验证方法，希望参与可靠性要求更高的业务。以上为虚构演示。')]:
            sid, vid = identifier('story-' + kind), identifier('story-version-' + kind)
            fields = {key: {'text': text, 'originType': 'USER_ASSERTION', 'sourceRefs': [], 'confirmation': {'confirmed': False}} for key, text in texts.items()}
            insert('v2_stories', {'id': sid, 'templateKind': kind, 'status': 'DRAFT', 'currentVersion': 1, 'currentVersionId': vid,
                                 'generationState': 'NONE', 'templateFields': list(texts), 'fields': fields, 'materialIds': [identifier('orders' if kind == 'STAR' else 'resume')]})
            insert('v2_story_versions', {'id': vid, 'storyId': sid, 'version': 1, 'status': 'DRAFT', 'fields': fields,
                                        'sourceSnapshots': [], 'dependencies': [], 'expressionDraft': expression})
        for index in [0, 2]:
            cid = identifier('practice-' + str(index))
            rubric = [{'checkId': key, 'description': text} for key, text in [('SCOPE', '回应问题并说明边界'), ('REASONING', '解释方案与依据'), ('COVERAGE', '覆盖异常恢复'), ('EXPRESSION', '结构清晰且行动具体')]]
            sha = lambda v: hashlib.sha256(json.dumps(v, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
            insert('v2_practice_cases', {'id': cid, 'familyId': cid, 'caseVersion': 1, 'sourceKind': 'SESSION', 'sourceId': legacy_id,
                                       'messageId': legacy_messages[index * 2]['id'], 'questionIndex': index,
                                       'question': questions[index + 1][1], 'context': order, 'contextOrigin': 'LEGACY_CONTEXT',
                                       'contextTruncated': False, 'analysisExposure': 'PROVIDED', 'originalExposure': 'UNKNOWN',
                                       'priorDialogue': [], 'rubric': rubric, 'rubricHash': sha(rubric),
                                       'questionHash': sha(questions[index + 1][1]), 'contextHash': sha({'text': order, 'priorDialogue': []}),
                                       'referenceAnalysis': legacy_report['questions'][index]})
            insert('v2_practice_attempts', {'id': identifier('attempt-' + str(index)), 'caseId': cid, 'ordinal': 0,
                                          'kind': 'ORIGINAL_SNAPSHOT', 'status': 'IMPORTED', 'answerText': questions[index + 1][2],
                                          'protocolStatus': 'UNKNOWN', 'effectiveExposure': 'UNKNOWN', 'submittedAt': None, 'evaluation': None})
        if db.execute('PRAGMA foreign_key_check').fetchall():
            raise ValueError('演示样本关联检查失败')
    (data / MARKER).write_text(encode(IDENTITY))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, required=True)
    parser.add_argument('--check-ports', type=int, nargs='+', default=[])
    args = parser.parse_args()
    data = args.data_dir.absolute()
    if data != data.resolve() or any(p in [data, *data.parents] for p in [ROOT / 'data', ROOT / 'backend/data']):
        parser.error('演示目录不能是日常数据目录、其子目录或符号链接。')
    for port in args.check_ports:
        try:
            with socket.socket() as listener:
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                listener.bind(('127.0.0.1', port))
        except (OSError, OverflowError):
            parser.error(f'演示端口 {port} 已占用或无效，未修改数据，也不会自动切换端口。')
    if data.exists() and any(data.iterdir()):
        marker = data / MARKER
        if any(p.is_symlink() for p in data.rglob('*')) or not marker.is_file() or json.loads(marker.read_text()) != IDENTITY:
            parser.error('目标目录不是已登记的演示空间，拒绝读取或写入已有数据。')
        if not (data / 'interview.db').is_file():
            parser.error('演示数据库缺失，请保留目录并核对文件，未自动重建或覆盖。')
        print('沿用已有演示数据，编辑、删除和模型配置保持。')
        return
    data.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.demo-seed-', dir=data.parent))
    try:
        seed(temporary)
        if data.exists():
            data.rmdir()
        temporary.rename(data)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    print('虚构演示数据已创建，未读取日常资料或模型配置。')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, sqlite3.Error):
        raise SystemExit('演示数据初始化被拒绝或失败，请核对目录与权限；未覆盖已有数据。')
