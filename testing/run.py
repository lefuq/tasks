import argparse
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("suite", choices=("unit", "integration", "acceptance"))
    suite = parser.parse_args().suite
    commands = {
        "unit": [
            ["python3", "-m", "unittest", "discover", "-s", "testing/tests"],
            ["mvn", "-f", "backend/pom.xml", "test"],
            ["mvn", "-f", "emulator/pom.xml", "test"],
            ["pnpm", "--dir", "frontend", "test"],
            ["pnpm", "--dir", "frontend", "typecheck"],
            [
                "python3", "-m", "unittest", "discover", "-s", "backend/load",
                "-p", "test_*.py",
            ],
        ],
        "integration": [
            ["docker", "info"],
            ["mvn", "-f", "backend/pom.xml", "verify"],
            ["mvn", "-f", "emulator/pom.xml", "verify"],
            ["mvn", "-f", "backend/pom.xml", "-Pjoint", "verify"],
        ],
        "acceptance": [
            ["docker", "info"],
            ["pnpm", "--dir", "frontend", "test:e2e"],
        ],
    }
    for command in commands[suite]:
        subprocess.run(command, cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
