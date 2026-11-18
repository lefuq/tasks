import argparse
import json
import os
import sys
import time
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from load.profile import Client, build_opener


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--emulator-url", default="http://localhost:8090")
    parser.add_argument("--output", default="backend/demo-report.json")
    args = parser.parse_args()
    password = os.environ["DEMO_PASSWORD"]
    authors = {
        name: Client(args.url, name, password)
        for name in ("initiator1", "initiator2", "initiator3")
    }
    operators = {
        client.actor["id"]: client
        for client in (
            Client(args.url, "operator1", password),
            Client(args.url, "operator2", password),
        )
    }
    prefix = uuid.uuid4().hex[:8]

    def admin(path, body=None):
        headers = {} if body is None else {"Content-Type": "application/json"}
        request = urllib.request.Request(
            args.emulator_url.rstrip("/") + path,
            data=None if body is None else json.dumps(body).encode(),
            headers=headers,
            method="GET" if body is None else "PUT",
        )
        with build_opener(args.emulator_url).open(request, timeout=5) as response:
            return json.loads(response.read())

    def configure(worker, fault, outcome):
        admin(
            f"/admin/workers/{worker}",
            {"fault": fault, "outcome": outcome},
        )

    def create(author, name):
        status, task = author.request(
            "POST",
            "/api/tasks",
            {"name": f"{prefix}-{name}", "description": "", "type": "process"},
        )
        if status != 201:
            raise RuntimeError(f"Create {name}: {status} {task}")
        return task

    def command(task, action, worker=None):
        operator = operators[task["operatorId"]]
        body = {"version": task["version"]}
        method = "POST"
        if action == "worker":
            body["workerId"] = worker
            method = "PUT"
        status, response = operator.request(
            method,
            f"/api/tasks/{task['id']}/{action}",
            body,
        )
        if status != 200:
            raise RuntimeError(f"Command {action}: {status} {response}")
        return response

    def send(task, worker):
        return command(command(task, "worker", worker), "send")

    def wait_task(task, predicate):
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            status, response = operators[task["operatorId"]].request(
                "GET",
                f"/api/tasks/{task['id']}",
            )
            if status == 200 and predicate(response):
                return response
            time.sleep(1)
        raise RuntimeError(f"Task {task['id']} did not reach the expected state")

    configure("worker-1", "normal", "success")
    configure("worker-2", "normal", "fail")
    report = {"tasks": {}, "inspections": {}}
    try:
        print("Creating new and waiting tasks", flush=True)
        report["tasks"]["new"] = create(authors["initiator3"], "New")
        report["tasks"]["waiting"] = create(authors["initiator1"], "Waiting")
        success = send(create(authors["initiator1"], "Success"), 1)
        failure = send(create(authors["initiator2"], "Fail"), 2)
        print("Waiting for real success and fail executions", flush=True)
        for name, task, expected in (
            ("success", success, "success"),
            ("fail", failure, "fail"),
        ):
            current = wait_task(task, lambda value: value.get("result") is not None)
            if current["result"]["status"] != expected:
                raise RuntimeError(f"Wrong outcome for {name}")
            report["tasks"][name] = current
            report["inspections"][name] = admin(
                f"/admin/operations/{current['externalId']}",
            )
        configure("worker-1", "invalid_json", "success")
        returned = send(create(authors["initiator1"], "Returned"), 1)
        print("Waiting for invalid delivery and returning the task", flush=True)
        invalid = wait_task(
            returned,
            lambda value: value.get("problem") == "INVALID_RESULT",
        )
        report["tasks"]["returned"] = command(invalid, "return")
        report["inspections"]["returned"] = admin(
            f"/admin/operations/{invalid['externalId']}",
        )
        configure("worker-1", "normal", "success")
        executing = send(create(authors["initiator2"], "Executing"), 1)
        current = wait_task(executing, lambda value: value.get("externalId") is not None)
        report["tasks"]["executing"] = current
        report["inspections"]["executing"] = admin(
            f"/admin/operations/{current['externalId']}",
        )
        if report["tasks"]["new"]["state"] != "NEW":
            raise RuntimeError("Service B already has an operator; use the initial demo instance")
        if current["state"] != "EXECUTING":
            raise RuntimeError("Executing task already finished before the demo snapshot")
        for inspection in report["inspections"].values():
            if inspection["execution_count"] != 1:
                raise RuntimeError("Unexpected external execution count")
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(f"Created six linked demo tasks; report: {args.output}", flush=True)
    finally:
        configure("worker-1", "normal", "random")
        configure("worker-2", "normal", "random")


if __name__ == "__main__":
    main()
