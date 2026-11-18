import argparse
import http.client
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--grep")
    parser.add_argument("--no-build", action="store_true")
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    compose = [
        "docker",
        "compose",
        "-f",
        str(root / "testing/compose.yaml"),
        "-p",
        "frontend-test-" + uuid.uuid4().hex[:10],
    ]
    subprocess.run(["docker", "info"], check=True, stdout=subprocess.DEVNULL)
    override_path = None
    if arguments.no_build:
        images = {
            service: {"image": os.environ[key]}
            for service, key in [
                ("backend", "TEST_BACKEND_IMAGE"),
                ("emulator", "TEST_EMULATOR_IMAGE"),
                ("contradiction-proxy", "TEST_PROXY_IMAGE"),
            ]
        }
        with tempfile.NamedTemporaryFile(
            mode="w",
            suffix=".json",
            delete=False,
        ) as override:
            json.dump({"services": images}, override)
            override_path = Path(override.name)
        compose.extend(["-f", str(override_path)])
    environment_saved = False
    try:
        subprocess.run(
            compose + [
                "up",
                "-d",
                "--no-build" if arguments.no_build else "--build",
                "--wait",
                "postgres",
                "emulator",
                "contradiction-proxy",
            ],
            check=True,
        )
        if not arguments.no_build:
            subprocess.run(compose + ["build", "backend"], check=True)
        subprocess.run(
            compose + [
                "run",
                "--rm",
                "-e",
                "APP_EXCHANGE_ENABLED=false",
                "backend",
                "--server.port=0",
                "--app.command=bootstrap",
                "--app.worker-one-url=http://contradiction-proxy:8091/workers/worker-1",
                "--app.worker-two-url=http://contradiction-proxy:8091/workers/worker-2",
            ],
            check=True,
        )
        subprocess.run(compose + ["up", "-d", "--no-build", "backend"], check=True)
        environment = os.environ.copy()
        for service, port, key in [
            ("backend", "8080", "TEST_API_URL"),
            ("emulator", "8090", "TEST_EMULATOR_URL"),
            ("contradiction-proxy", "8091", "TEST_PROXY_URL"),
        ]:
            address = subprocess.check_output(
                compose + ["port", service, port],
                text=True,
            ).strip()
            environment[key] = "http://" + address
        environment["TEST_ISOLATED"] = "1"
        environment["API_TARGET"] = environment["TEST_API_URL"]
        for _ in range(90):
            try:
                with urllib.request.urlopen(
                    environment["TEST_API_URL"] + "/actuator/health",
                    timeout=2,
                ) as response:
                    if response.status == 200:
                        break
            except (urllib.error.URLError, OSError, http.client.HTTPException):
                time.sleep(1)
        else:
            raise RuntimeError("Isolated backend failed to start")
        node = shutil.which("node")
        if node is None:
            raise RuntimeError("Node.js is required")
        if arguments.keep:
            keys = [
                "TEST_API_URL",
                "TEST_EMULATOR_URL",
                "TEST_PROXY_URL",
                "TEST_ISOLATED",
                "API_TARGET",
            ]
            metadata = {
                "compose": compose,
                "environment": {key: environment[key] for key in keys},
            }
            (root / "test-environment.json").write_text(
                json.dumps(metadata, indent=2),
            )
            environment_saved = True
        command = [node, str(root / "node_modules/@playwright/test/cli.js"), "test"]
        if arguments.grep:
            command.extend(["--grep", arguments.grep])
        subprocess.run(
            command,
            cwd=root,
            env=environment,
            check=True,
        )
    finally:
        if not arguments.keep or not environment_saved:
            subprocess.run(compose + ["down", "-v", "--remove-orphans"], check=True)
            if override_path is not None:
                override_path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
