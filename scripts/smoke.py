#!/usr/bin/env python3
"""仅使用合成资料，对运行中的 项目与单题面试接口进行可重复的 HTTP 验收。"""

import argparse
import datetime as dt
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class AcceptanceError(Exception):
    """可以直接展示给操作者的验收失败。"""


def require(condition, message):
    if not condition:
        raise AcceptanceError(message)


def redact(value):
    """证据只含本次合成数据，仍剔除常见凭据及服务错误详情。"""
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            normalized = re.sub(r"[^a-z]", "", key.lower())
            if normalized in {"apikey", "authorization", "secret", "password", "token"}:
                result[key] = "[已隐藏]"
            elif key == "error" and item:
                result[key] = "服务返回错误，原始详情未写入证据"
            else:
                result[key] = redact(item)
        return result
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, str):
        value = re.sub(r"\bsk-[A-Za-z0-9_-]+", "[已隐藏密钥]", value)
        return re.sub(r"(?i)\bBearer\s+\S+", "Bearer [已隐藏]", value)
    return value


class Smoke:
    def __init__(self, args):
        self.args = args
        self.base = args.base_url.rstrip("/")
        self.run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
        self.checks = []
        self.materials = []
        self.sessions = []
        self.evidence = {"运行标识": self.run_id, "真实模型模式": args.real, "合成资料": [], "会话": {}, "押题": []}
        self.cleanup_errors = []

    def request(self, method, path, data=None, multipart=None, expected_error=False):
        headers = {"Accept": "application/json"}
        body = None
        if multipart is not None:
            body, content_type = multipart
            headers["Content-Type"] = content_type
        elif data is not None:
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + "/api" + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            if expected_error and exc.code in {400, 409}:
                return None
            raise AcceptanceError(f"{method} {path} 返回 HTTP {exc.code}；未将上游错误正文写入证据") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise AcceptanceError(f"无法完成 {method} {path}；请确认后端已启动、地址正确且模型服务可用") from None
        require(not expected_error, f"{method} {path} 本应拒绝，但返回成功")
        if not raw:
            return None
        try:
            return json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise AcceptanceError(f"{method} {path} 未返回有效 JSON") from None

    def passed(self, name):
        self.checks.append(name)

    def provider_marker(self, value, label):
        require(value.get("mock") is (not self.args.real), f"{label} 的 mock 标记与运行模式不符；真实服务请传 --real")

    def material(self, kind, name, content):
        material = self.request("POST", "/materials", {"kind": kind, "name": self.name(name), "content": content})
        require(isinstance(material, dict) and material.get("id"), "创建资料未返回 ID")
        self.materials.append(material["id"])
        self.evidence["合成资料"].append(material)
        return material

    def name(self, label):
        return f"验收用{label} {self.run_id}"

    @staticmethod
    def multipart(fields, filename, content, mime):
        boundary = "----InterviewAcceptance" + uuid.uuid4().hex
        parts = []
        for name, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode("utf-8"))
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n'.encode("utf-8"))
        parts.append(content.encode("utf-8"))
        parts.append(f"\r\n--{boundary}--\r\n".encode("ascii"))
        return b"".join(parts), f"multipart/form-data; boundary={boundary}"

    def check_session(self, session):
        require(isinstance(session, dict) and session.get("id"), "会话缺少 ID")
        self.provider_marker(session, "会话")
        require(session.get("status") in {"CREATED", "IN_PROGRESS", "COMPLETED", "INTERRUPTED", "FAILED"}, "会话状态不合法")
        require(isinstance(session.get("messages"), list), "会话缺少原始对话")
        require(isinstance(session.get("followUpCount"), int) and 0 <= session["followUpCount"] <= 2, "会话追问计数超过两次或不合法")
        ids, followups = set(), {}
        for message in session["messages"]:
            require(message.get("id") and message["id"] not in ids, "消息 ID 缺失或重复")
            ids.add(message["id"])
            require(message.get("role") in {"ASSISTANT", "USER"}, "对话包含非公开角色")
            require(isinstance(message.get("content"), str) and message["content"].strip(), "对话内容为空")
            require(isinstance(message.get("questionIndex"), int) and message["questionIndex"] >= 0, "问题编号不合法")
            if message["role"] == "ASSISTANT" and message.get("followUp"):
                index = message["questionIndex"]
                followups[index] = followups.get(index, 0) + 1
        require(all(count <= 2 for count in followups.values()), "原始对话中存在超过两次追问的主问题")
        require("plan" not in session and "internalReason" not in session, "会话接口泄露内部规划或决策")
        self.evidence["会话"].setdefault(session["id"], {})["最新会话"] = session
        return session

    def get_session(self, session_id):
        return self.check_session(self.request("GET", f"/sessions/{session_id}"))

    def start(self, mode, **extra):
        payload = {"mode": mode, "role": "大模型应用 / Agent 开发工程师", "level": "JUNIOR", "durationMinutes": 5, "style": "NORMAL"}
        payload.update(extra)
        session = self.request("POST", "/sessions", payload)
        if isinstance(session, dict) and session.get("id"):
            self.sessions.append(session["id"])
        session = self.check_session(session)
        require(session["status"] == "IN_PROGRESS", f"{mode} 面试未成功进入进行中状态")
        require(len(session["messages"]) == 1 and session["messages"][0]["role"] == "ASSISTANT", "新面试未返回唯一首问")
        self.evidence["会话"][session["id"]]["创建参数"] = payload
        return session

    def answer(self, session, content, duplicate=False):
        require(session["status"] == "IN_PROGRESS", "面试提前结束，无法完成预定回答验收")
        previous = session["messages"]
        require(previous and previous[-1]["role"] == "ASSISTANT", "待回答会话末条消息不是面试官问题")
        payload = {"content": content, "requestId": str(uuid.uuid4()), "expectedMessageId": previous[-1]["id"]}
        self.request("POST", f"/sessions/{session['id']}/answers", payload)
        current = self.get_session(session["id"])
        users = [message["content"] for message in current["messages"] if message["role"] == "USER"]
        original_users = [message["content"] for message in previous if message["role"] == "USER"]
        require(users == original_users + [content], "回答未原样保存或发生重复提交")
        require(current["status"] in {"IN_PROGRESS", "COMPLETED"}, "回答后生成失败或进入中断状态")
        if duplicate:
            self.request("POST", f"/sessions/{session['id']}/answers", payload)
            repeated = self.get_session(session["id"])
            require(repeated["messages"] == current["messages"], "同一请求 ID 重复发送导致对话发生变化")
            self.passed("重复回答请求幂等")
        return current

    def finish(self, session):
        original_messages = session["messages"]
        self.request("POST", f"/sessions/{session['id']}/finish", {})
        completed = self.get_session(session["id"])
        require(completed["status"] == "COMPLETED", "主动结束后会话未完成")
        require(completed["messages"] == original_messages, "结束面试改变了原始问答")
        report = self.request("GET", f"/sessions/{session['id']}/report")
        require(isinstance(report, dict) and report.get("sessionId") == session["id"], "报告与会话不匹配")
        self.provider_marker(report, "报告")
        dimensions = report.get("dimensions")
        require(isinstance(dimensions, list) and len(dimensions) == 4, "报告未包含四个评价维度")
        require({dimension.get("name") for dimension in dimensions} == {"技术准确性", "理解深度", "表达清晰度", "追问应对能力"}, "报告评价维度名称不完整")
        for dimension in dimensions:
            require(type(dimension.get("score")) in {int, float} and 0 <= dimension["score"] <= 4, "报告评分不在 0～4 范围内")
            require(isinstance(dimension.get("reason"), str) and dimension["reason"].strip(), "报告评分缺少依据")
        require(isinstance(report.get("summary"), str) and report["summary"].strip(), "报告缺少整体总结")
        require(isinstance(report.get("questions"), list), "报告缺少逐题评价")
        answered = {message["questionIndex"] for message in completed["messages"] if message["role"] == "USER"}
        evaluated = {question.get("questionIndex") for question in report["questions"]}
        require(answered <= evaluated, "报告遗漏已回答主问题")
        for question in report["questions"]:
            for field in ("correct", "incorrect", "missing", "uncertain", "evidence"):
                require(isinstance(question.get(field), list), f"逐题报告缺少 {field} 数组")
            for field in ("question", "answerOutline", "knowledgeExplanation"):
                require(isinstance(question.get(field), str) and question[field].strip(), f"逐题报告缺少 {field}")
        for field in ("weaknesses", "suggestions"):
            require(isinstance(report.get(field), list), f"报告缺少 {field} 数组")
        self.evidence["会话"][session["id"]]["报告"] = report
        return completed, report

    def cleanup(self):
        for material_id in self.materials[:]:
            try:
                self.request("DELETE", f"/materials/{material_id}")
                self.materials.remove(material_id)
            except AcceptanceError as exc:
                self.cleanup_errors.append(str(exc))

    def run(self):
        health = self.request("GET", "/health")
        require(isinstance(health, dict), "健康接口未返回对象")
        require(str(health.get("status", "")).upper() in {"UP", "OK", "HEALTHY"}, "健康检查未通过")
        require(health.get("configured") is True, "模型未配置；请配置后端 API Key，或显式启用 Mock 后重试")
        provider = str(health.get("provider", "")).lower()
        require(bool(provider), "健康接口缺少模型 Provider")
        require((provider == "mock") is (not self.args.real), "服务 Provider 与运行模式不符；真实模型请使用 --real")
        require(not any(re.sub(r"[^a-z]", "", key.lower()) in {"apikey", "authorization", "baseurl"} for key in health), "健康接口暴露不应公开的配置")
        self.evidence["健康"] = health
        self.passed("健康检查与模型模式标识")

        jd_text = "验收合成岗位：初级 Java 与 AI Agent 开发，使用 Spring Boot、HTTP API、SQLite，关注幂等、超时和错误处理。"
        jd = self.material("JD", "岗位描述", jd_text)
        quick = self.start("QUICK", jd=jd["content"])
        initial_index = quick["questionIndex"]
        answers = [
            "我会用 requestId 标识请求，在 SQLite 中保存状态并避免重复处理。\n外部模型可能超时，因此响应前先保存用户回答。",
            "客户端断开并不代表上游调用已经取消。我会标记取消状态，设置超时，并在结果返回时校验会话状态；不确定底层 SDK 是否支持取消。",
            "我会用事务约束状态更新，并通过同一请求 ID 重试获取已有结果。没有做过性能压测，不能给出吞吐指标。",
        ]
        for index, content in enumerate(answers):
            quick = self.answer(quick, content, duplicate=index == 0)
            if not self.args.real:
                expected_index = initial_index if index < 2 else initial_index + 1
                expected_followup = index + 1 if index < 2 else 0
                require(quick["questionIndex"] == expected_index and quick["followUpCount"] == expected_followup, "Mock 未遵循两次追问后切换主问题的验收脚本")
        self.passed("快速面试三次回答与追问上限")
        quick, quick_report = self.finish(quick)
        self.request("POST", f"/sessions/{quick['id']}/answers", {"content": "验收结束后禁止写入", "requestId": str(uuid.uuid4()), "expectedMessageId": quick["messages"][-1]["id"]}, expected_error=True)
        require(self.get_session(quick["id"])["messages"] == quick["messages"], "结束后的回答改变了历史对话")
        self.passed("主动结束、四维报告、原始问答与终态保护")

        resume_text = "# 验收合成简历\n\n目标：初级 Java / AI Agent 工程师。\n技能：Java、Spring Boot、SQLite、HTTP。\n项目：本地学习问答工具。负责 HTTP 接口、请求幂等、错误提示与 SQLite 持久化。\n未提供上线规模、性能指标、企业经历或获奖事实；这些内容需要补充。\n"
        resume = self.request("POST", "/materials/upload", multipart=self.multipart({"kind": "RESUME", "name": self.name("合成简历")}, "acceptance-resume.md", resume_text, "text/markdown"))
        require(isinstance(resume, dict) and resume.get("id"), "Markdown 上传未创建简历")
        self.materials.append(resume["id"])
        self.evidence["合成资料"].append(resume)
        require(resume.get("kind") == "RESUME" and resume.get("documents"), "上传简历缺少文档或类型错误")
        prediction = self.request("POST", "/predictions", {"resumeId": resume["id"], "role": "Java 后端工程师", "jd": jd_text})
        self.provider_marker(prediction, "押题")
        self.evidence["押题"].append(prediction)
        require(len(prediction.get("questions", [])) == 10, "简历押题未生成默认 10 题")
        for question in prediction["questions"]:
            for field in ("id", "question", "evidence", "knowledgePoint", "priority", "answerOutline"):
                require(question.get(field), f"押题缺少 {field}")
            require(isinstance(question.get("followUps"), list), "押题缺少进一步追问数组")
        persisted_predictions = self.request("GET", "/predictions?" + urllib.parse.urlencode({"resumeId": resume["id"]}))
        require(any(item.get("id") == prediction["id"] for item in persisted_predictions), "押题结果未持久保存")
        selected = prediction["questions"][0]
        practice = self.start("RESUME", materialId=resume["id"], predictionId=prediction["id"], questionId=selected["id"])
        require(practice["messages"][0]["content"] == selected["question"], "选题练习首问不是所选押题")
        practice = self.answer(practice, "我在合成项目中负责接口和幂等处理，使用请求 ID 避免重复写入；尚未提供部署和压测依据。")
        practice, _ = self.finish(practice)
        self.passed("Markdown 简历上传、十道押题、押题历史与选题练习")

        project_text = "验收合成项目：本地问答练习器。Java 21、Spring Boot、SQLite。个人负责 Controller、服务编排与数据库持久化。普通 HTTP 调用 OpenAI 兼容模型，未采用 SSE、Redis 或消息队列。"
        project = self.material("PROJECT", "本地问答练习器", project_text)
        document_text = "验收合成设计资料：收到回答后先保存消息，再调用外部模型。使用 requestId 防重复提交；模型超时后保留原回答并允许重试。SQLite 保存会话；尚未进行并发性能测试。"
        self.request("POST", f"/materials/{project['id']}/documents", multipart=self.multipart({}, "acceptance-design.txt", document_text, "text/plain"))
        listed_projects = self.request("GET", "/materials?kind=PROJECT")
        updated_project = next((item for item in listed_projects if item.get("id") == project["id"]), None)
        require(updated_project and any(document_text in document.get("text", "") for document in updated_project.get("documents", [])), "TXT 附件未提取并保存为项目文档")
        self.evidence["合成资料"] = [updated_project if item.get("id") == project["id"] else item for item in self.evidence["合成资料"]]
        project_session = self.start("PROJECT", materialId=project["id"])
        project_session = self.answer(project_session, "我负责接口、服务编排和持久化。请求先保存到 SQLite，再调用模型，超时保留原回答。requestId 用来防止重复提交；当前没有压测数据。")
        project_session, _ = self.finish(project_session)
        self.passed("项目资料、TXT 附件与项目面试报告")

        self.cleanup()
        require(not self.materials, "部分本次验收资料删除失败，详见清理提示")
        history = self.request("GET", "/sessions")
        require(isinstance(history, list) and set(self.sessions) <= {item.get("id") for item in history}, "历史列表缺少本次验收会话")
        for session_id in self.sessions:
            previous_session = self.evidence["会话"][session_id]["最新会话"]
            previous_report = self.evidence["会话"][session_id]["报告"]
            restored = self.get_session(session_id)
            require(restored["messages"] == previous_session["messages"], "资料删除后历史对话发生变化")
            restored_report = self.request("GET", f"/sessions/{session_id}/report")
            require(restored_report == previous_report, "资料删除后历史报告发生变化")
        self.passed("删除本次合成资料后历史问答与报告仍可重新读取")

    def execute(self):
        error = None
        try:
            self.run()
        except AcceptanceError as exc:
            error = str(exc)
        except (KeyError, TypeError, ValueError, AttributeError) as exc:
            error = f"接口响应不符合冻结契约（{type(exc).__name__}）；未伪造通过"
        finally:
            if self.materials:
                self.cleanup()
        summary = {
            "结果": "失败" if error else "通过",
            "运行标识": self.run_id,
            "模型模式": "真实模型" if self.args.real else "Mock",
            "通过检查": self.checks,
            "保留的验收会话": self.sessions,
            "未清理的验收资料": self.materials,
            "说明": "本次新增的验收会话保留在历史中；仅删除本次创建的合成资料。",
            "验证边界": "HTTP 结构、状态与持久化检查；未验证服务重启和浏览器交互。真实模型的相关性、事实依据及反馈质量需要人工判读。" if self.args.real else "Mock 仅验证 HTTP 业务链路，不代表真实模型质量；未验证服务重启和浏览器交互。",
        }
        if error:
            summary["错误"] = error
        if self.cleanup_errors:
            summary["清理提示"] = self.cleanup_errors
        self.evidence["总结"] = summary
        if self.args.output:
            try:
                path = Path(self.args.output).expanduser()
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(redact(self.evidence), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                summary["证据路径"] = str(path.resolve())
            except OSError:
                summary["结果"] = "失败"
                summary["证据错误"] = "无法写入指定证据路径"
                error = error or summary["证据错误"]
        print(json.dumps(redact(summary), ensure_ascii=False, indent=2))
        return 1 if error else 0


def main():
    parser = argparse.ArgumentParser(description="用合成资料验收 AI 面试助手；会保留验收会话，删除本次创建的资料。")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="后端根地址，默认 http://127.0.0.1:8080")
    parser.add_argument("--real", action="store_true", help="验收已配置的真实模型；不把结构通过视为语义质量通过")
    parser.add_argument("--output", help="将本次原始会话、押题和报告保存为 JSON 证据")
    args = parser.parse_args()
    parsed = urllib.parse.urlsplit(args.base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error("--base-url 必须是无用户名、密码、查询参数和片段的 HTTP(S) 地址")
    return Smoke(args).execute()


if __name__ == "__main__":
    sys.exit(main())
