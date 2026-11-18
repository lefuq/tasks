import subprocess
from pathlib import Path


root = Path(__file__).resolve().parents[3]
command = ["pnpm", "--dir", str(root / "frontend"), "test"]
raise SystemExit(subprocess.run(command, cwd=root).returncode)
