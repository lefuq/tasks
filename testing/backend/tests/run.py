import subprocess
from pathlib import Path


root = Path(__file__).resolve().parents[3]
command = ["mvn", "-f", str(root / "backend" / "pom.xml"), "test"]
raise SystemExit(subprocess.run(command, cwd=root).returncode)
