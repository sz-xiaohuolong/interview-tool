"""在专用合成浏览器及临时库中验证岗位收藏、JD 和任务的进程重启边界。"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-root", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    args = parser.parse_args()
    root = args.test_root.resolve()
    # 本脚本不接受日常数据目录，只使用 e2e-job-search.sh 刚创建的临时空间。
    if not root.name.startswith("job-search-e2e.") or not (root / "fixture-ready").is_file():
        raise SystemExit("只能使用岗位搜索隔离验收创建的临时空间。")
    active_port = root / "browser-profile/DevToolsActivePort"
    if not active_port.is_file() or not args.jar.is_file():
        raise SystemExit("隔离浏览器或已构建后端不可用。")
    with socket.socket() as port_probe:
        port_probe.bind(("127.0.0.1", 18182))
    env = dict(os.environ, PORT="18182", DATA_DIR=str(root / "data"),
               TRAINING_ENABLED="true", LLM_PROVIDER="mock", LLM_API_KEY="",
               LLM_MODEL="mock-functional-test", DEMO_MODE="false",
               INTERVIEW_JOB_BROWSER_ACTIVE_PORT_FILE=str(active_port))
    process = None

    def call(path, body=None):
        payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        req = urllib.request.Request("http://127.0.0.1:18182" + path, data=payload,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response) if response.status != 204 else None

    def launch():
        log = (root / "restart-backend.log").open("ab")
        launched = subprocess.Popen(["java", "-jar", str(args.jar.resolve()),
                                     "--app.knowledge.network-enabled=false"],
                                    env=env, stdout=log, stderr=subprocess.STDOUT)
        log.close()
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if launched.poll() is not None:
                raise RuntimeError("隔离后端提前退出，请检查临时日志。")
            try:
                if call("/api/health")["status"] == "ok":
                    return launched
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.2)
        launched.terminate()
        launched.wait(timeout=15)
        raise RuntimeError("隔离后端健康检查超时。")

    def stop(owned):
        if owned and owned.poll() is None:
            owned.terminate()
            try:
                owned.wait(timeout=15)
            except subprocess.TimeoutExpired:
                owned.kill()
                owned.wait(timeout=5)

    prefix = "/api/job-search"
    try:
        process = launch()
        first_pid = process.pid
        task = call(prefix + "/tasks", {"keyword": "合成持久化验收", "cityId": "shenzhen", "sources": ["BOSS"]})
        deadline = time.monotonic() + 35
        while task["status"] == "RUNNING" and time.monotonic() < deadline:
            time.sleep(.1)
            task = call(prefix + "/tasks/" + task["id"])
        assert task["status"] == "IDLE" and task["jobs"], "合成来源未返回可用岗位"
        key = task["jobs"][0]["key"]
        call(prefix + "/tasks/" + task["id"] + "/jobs/" + key + "/detail", {})
        favorite = call(prefix + "/favorites", {"taskId": task["id"], "jobKeys": [key]})
        jd = call(prefix + "/jd", {"favoriteId": favorite["id"], "jobKey": key,
                                  "name": "合成搜索验收重启 JD", "content": "合成持久化验收：进程重启后保留用户编辑。"})
        material_before = next(x for x in call("/api/materials") if x["id"] == jd["materialId"])
        call(prefix + "/tasks/" + task["id"] + "/stop", {})
        stop(process)
        process = launch()
        assert process.pid != first_pid
        assert next(x for x in call(prefix + "/favorites") if x["id"] == favorite["id"]) == favorite
        assert next(x for x in call("/api/materials") if x["id"] == jd["materialId"]) == material_before
        link = call(prefix + "/jd-links/lookup", {"keys": [key]})[key]
        assert link["materialId"] == jd["materialId"] and link["contentEdited"] is True
        try:
            call(prefix + "/tasks/" + task["id"])
            raise AssertionError("搜索临时任务不应跨进程保留")
        except urllib.error.HTTPError as error:
            assert error.code == 404
        print(json.dumps({"状态": "通过", "重启前进程": first_pid, "重启后进程": process.pid,
                          "收藏快照不变": True, "JD正文及来源不变": True,
                          "来源关联不变": True, "临时搜索任务已清理": True}, ensure_ascii=False))
    finally:
        stop(process)


if __name__ == "__main__":
    main()
