import argparse
import concurrent.futures
import http.client
import http.cookiejar
import json
import math
import os
import platform
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit


def build_opener(url, *handlers):
    if urlsplit(url).hostname in {"localhost", "127.0.0.1", "::1"}:
        return urllib.request.build_opener(urllib.request.ProxyHandler({}), *handlers)
    return urllib.request.build_opener(*handlers)


class PersistentSession:
    def __init__(self, base_url):
        target = urlsplit(base_url)
        connection_type = (
            http.client.HTTPSConnection
            if target.scheme == "https"
            else http.client.HTTPConnection
        )
        self.connection = connection_type(target.hostname, target.port, timeout=10)
        self.cookies = http.cookiejar.CookieJar()

    def open(self, request, timeout):
        self.cookies.add_cookie_header(request)
        self.connection.timeout = timeout
        try:
            self.connection.request(
                request.get_method(),
                request.selector,
                body=request.data,
                headers=dict(request.header_items()),
            )
            response = self.connection.getresponse()
            self.cookies.extract_cookies(response, request)
            return response
        except (OSError, http.client.HTTPException):
            self.close()
            raise

    def close(self):
        self.connection.close()


class Client:
    def __init__(self, base_url, username, password):
        self.base_url = base_url.rstrip("/")
        self.opener = PersistentSession(self.base_url)
        self.csrf = None
        self.request("GET", "/api/me")
        status, actor = self.request(
            "POST",
            "/api/session",
            {"username": username, "password": password},
        )
        if status != 200:
            raise RuntimeError(f"Login failed: HTTP {status}")
        self.actor = actor

    def request(self, method, path, body=None):
        headers = {"Accept": "application/json"}
        if self.csrf:
            headers["X-CSRF-TOKEN"] = self.csrf
        if body is not None:
            headers["Content-Type"] = "application/json"
        if method in {"POST", "PUT", "PATCH"} and path != "/api/session":
            headers["Idempotency-Key"] = str(uuid.uuid4())
        request = urllib.request.Request(
            self.base_url + path,
            data=None if body is None else json.dumps(body).encode(),
            headers=headers,
            method=method,
        )
        try:
            try:
                response = self.opener.open(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                self.csrf = response.headers.get("X-CSRF-TOKEN", self.csrf)
                payload = response.read()
                try:
                    return response.status, json.loads(payload) if payload else {}
                except (ValueError, UnicodeDecodeError):
                    return 0, {"code": f"InvalidResponse:HTTP{response.status}"}
        except (OSError, TimeoutError, http.client.HTTPException) as error:
            self.opener.close()
            return 0, {"code": f"ClientNetworkError:{type(error).__name__}:{error}"}



def create(client, name):
    return client.request(
        "POST",
        "/api/tasks",
        {"name": name, "description": "", "type": "process"},
    )


def percentile(samples):
    if not samples:
        return None
    return sorted(samples)[math.ceil(len(samples) * 0.95) - 1]


def execute_profile(args, password):
    barrier = threading.Barrier(20)
    timings = {}
    samples = []
    sample_lock = threading.Lock()

    def worker(index):
        try:
            client = Client(args.url, args.username, password)
            status, task = create(client, f"LoadClient-{uuid.uuid4()}")
            if status != 201:
                raise RuntimeError(f"Prepare failed: HTTP {status}")
        except Exception:
            barrier.abort()
            raise
        position = barrier.wait()
        if position == 0:
            timings["start"] = time.monotonic()
        barrier.wait()
        measure_at = timings["start"] + 30
        finish_at = measure_at + 120
        step = index % 4
        while time.monotonic() < finish_at:
            action = ("list", "card", "create", "edit")[step % 4]
            started = time.monotonic()
            if action == "list":
                status, body = client.request(
                    "GET",
                    "/api/tasks?type=process&page=0&size=20",
                )
            elif action == "card":
                status, body = client.request("GET", f"/api/tasks/{task['id']}")
            elif action == "create":
                status, body = create(client, f"Load-{uuid.uuid4()}")
                if status == 201:
                    task = body
            else:
                status, body = client.request(
                    "PATCH",
                    f"/api/tasks/{task['id']}",
                    {
                        "version": task["version"],
                        "description": f"Iteration {step}",
                        "type": "process",
                    },
                )
                if status == 200:
                    task = body
            elapsed = time.monotonic() - started
            if started >= measure_at:
                with sample_lock:
                    samples.append(
                        {
                            "action": action,
                            "seconds": elapsed,
                            "status": status,
                            "error": None if 200 <= status < 300 else body.get("code", "Unknown"),
                        },
                    )
            step += 1
        client.opener.close()

    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        list(pool.map(worker, range(20)))
    groups = {}
    for label, actions, limit in (
        ("read", {"list", "card"}, 2),
        ("write", {"create", "edit"}, 3),
    ):
        selected = [sample for sample in samples if sample["action"] in actions]
        p95 = percentile([sample["seconds"] for sample in selected])
        groups[label] = {
            "requests": len(selected),
            "successes": sum(sample["error"] is None for sample in selected),
            "errors": dict(
                Counter(
                    f"{sample['status']}:{sample['error']}"
                    for sample in selected
                    if sample["error"] is not None
                ),
            ),
            "p95_seconds_including_errors": p95,
            "limit_seconds": limit,
            "within_limit": p95 is not None and p95 <= limit,
        }
    return {
        "groups": groups,
        "operation_counts": dict(Counter(sample["action"] for sample in samples)),
        "samples": samples,
    }


def exchange_probe(args, password):
    author = Client(args.url, args.username, password)
    started = time.monotonic()
    status, task = create(author, f"Probe-{uuid.uuid4()}")
    if status != 201:
        return {"status": "failed", "reason": f"Create HTTP {status}"}
    operator_password = os.environ.get("LOAD_OPERATOR_PASSWORD", password)
    operator = None
    for username in args.operators.split(","):
        candidate = Client(args.url, username, operator_password)
        if candidate.actor["id"] == task["operatorId"]:
            operator = candidate
            break
    if operator is None:
        return {"status": "failed", "reason": "Assigned operator credentials missing"}
    status, workers = operator.request("GET", "/api/workers")
    if status != 200 or not workers:
        return {"status": "failed", "reason": "Workers unavailable"}
    status, task = operator.request(
        "PUT",
        f"/api/tasks/{task['id']}/worker",
        {"version": task["version"], "workerId": workers[0]["id"]},
    )
    if status != 200:
        return {"status": "failed", "reason": f"Worker selection HTTP {status}"}
    status, task = operator.request(
        "POST",
        f"/api/tasks/{task['id']}/send",
        {"version": task["version"]},
    )
    if status != 200:
        return {"status": "failed", "reason": f"Send HTTP {status}"}
    while time.monotonic() - started <= 180:
        status, body = author.request("GET", f"/api/tasks/{task['id']}")
        if status == 200 and body.get("result") is not None:
            observed_after = time.monotonic() - started
            history_status, history = author.request(
                "GET",
                f"/api/tasks/{task['id']}/history",
            )
            if history_status != 200:
                return {"status": "failed", "reason": "Creation audit unavailable"}
            created_at = next(event["at"] for event in history if event["action"] == "created")
            inspection_request = urllib.request.Request(
                args.emulator_url.rstrip("/") + f"/admin/operations/{body['externalId']}",
            )
            with build_opener(args.emulator_url).open(inspection_request, timeout=5) as response:
                inspection = json.loads(response.read())
            accepted = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
            finished = datetime.fromisoformat(inspection["finished_at"].replace("Z", "+00:00"))
            return {
                "status": "observed" if inspection["execution_count"] == 1 else "failed",
                "task_id": task["id"],
                "accepted_to_observed_result_seconds": observed_after,
                "worker_processing_seconds": body["result"]["processingTime"],
                "accepted_to_external_finished_seconds": (finished - accepted).total_seconds(),
                "local_created_at": created_at,
                "external_finished_at": inspection["finished_at"],
                "external_execution_count": inspection["execution_count"],
                "measurement": "Backend creation audit to independent emulator finished_at; observed-result latency additionally includes polling",
            }
        time.sleep(1)
    return {"status": "failed", "reason": "No valid result within 180 seconds"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--username", default="initiator1")
    parser.add_argument("--emulator-url", default="http://localhost:8090")
    parser.add_argument("--operators", default="operator1,operator2")
    parser.add_argument("--environment", required=True)
    parser.add_argument("--output", default="backend/load/report.json")
    args = parser.parse_args()
    password = os.environ["LOAD_PASSWORD"]
    report = execute_profile(args, password)
    report["external_execution"] = exchange_probe(args, password)
    report["at"] = datetime.now(timezone.utc).isoformat()
    report["environment"] = args.environment
    report["generator"] = platform.platform()
    report["profile"] = {
        "clients": 20,
        "http_connections": "One HTTP keep-alive connection per sequential client; protocol-requested reconnects allowed; no automatic request retries",
        "warmup_seconds": 30,
        "measurement_seconds": 120,
        "schedule": "Equal four-operation cycle per sequential client",
        "resource_budget": "Frontend + backend + DB + emulator + emulator DB: 2 CPU / 3456 MiB",
    }
    Path(args.output).write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "samples"}, indent=2))
    if (
        not all(group["within_limit"] and not group["errors"] for group in report["groups"].values())
        or report["external_execution"]["status"] != "observed"
    ):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
