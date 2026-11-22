#!/usr/bin/env python3
"""
Automated static security, anti-pattern, and observability configuration verifier for HealOps.
Runs locally and in CI with zero external credentials or live containers.
"""

from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable, List, Tuple

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]

JAVA_MAIN_DIR = REPO_ROOT / "Openstack-ai-agent" / "src" / "main"
UI_APP_DIR = REPO_ROOT / "agentUI" / "src" / "app"
PYTHON_ADAPTER_DIRS = [
    REPO_ROOT / "mcp_docker_server",
    REPO_ROOT / "mcp_k8s_server",
    REPO_ROOT / "mcp_openstack_server",
    REPO_ROOT / "mcp-server-promethus",
    REPO_ROOT / "tracing_mcp",
    REPO_ROOT / "demo-workload",
]
OTEL_CONFIG_PATH = REPO_ROOT / "observability" / "otel-collector.yaml"

SKIP_DIR_NAMES = {
    ".git",
    "node_modules",
    "target",
    "dist",
    ".angular",
    "__pycache__",
    ".pytest_cache",
    ".venv",
    "venv",
}

Violation = Tuple[str, int, str]


def iter_files(root: Path, suffixes: tuple[str, ...] | None = None, skip_tests: bool = False) -> Iterable[Path]:
    if not root.exists():
        return
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [
            d for d in dirnames
            if d not in SKIP_DIR_NAMES and not (skip_tests and d == "tests")
        ]
        for filename in filenames:
            path = Path(dirpath) / filename
            if suffixes is None or path.suffix.lower() in suffixes or filename.startswith("Dockerfile"):
                yield path


def scan_pattern(files: Iterable[Path], pattern: re.Pattern[str], label: str) -> List[Violation]:
    violations: List[Violation] = []
    for file_path in files:
        try:
            content = file_path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for lineno, line in enumerate(content.splitlines(), start=1):
            if pattern.search(line):
                rel = file_path.relative_to(REPO_ROOT).as_posix()
                violations.append((rel, lineno, f"{label}: {line.strip()}"))
    return violations


def check_java_antipatterns() -> List[Violation]:
    java_files = list(iter_files(JAVA_MAIN_DIR, suffixes=(".java",)))
    violations: List[Violation] = []
    violations.extend(scan_pattern(java_files, re.compile(r"WebClient\.create\s*\("), "Forbidden WebClient.create("))
    violations.extend(scan_pattern(java_files, re.compile(r"WebClient\.builder\s*\(\s*\)"), "Forbidden unmanaged WebClient.builder()"))
    violations.extend(scan_pattern(java_files, re.compile(r"@CrossOrigin\s*\([^)]*\*"), "Forbidden wildcard @CrossOrigin"))
    violations.extend(scan_pattern(java_files, re.compile(r"/api/traces\b"), "Forbidden legacy Jaeger /api/traces endpoint (use /api/v3/traces)"))
    return violations


def check_python_antipatterns() -> List[Violation]:
    py_files: List[Path] = []
    for d in PYTHON_ADAPTER_DIRS:
        py_files.extend(iter_files(d, suffixes=(".py",), skip_tests=True))
    violations: List[Violation] = []
    violations.extend(scan_pattern(py_files, re.compile(r"shell\s*=\s*True"), "Forbidden shell=True execution"))
    violations.extend(scan_pattern(py_files, re.compile(r"allow_origins\s*=\s*\[\s*['\"]\*['\"]\s*\]"), "Forbidden wildcard CORS allow_origins=['*']"))
    violations.extend(scan_pattern(py_files, re.compile(r"/api/traces\b"), "Forbidden legacy Jaeger /api/traces endpoint (use /api/v3/traces)"))
    violations.extend(scan_pattern(py_files, re.compile(r"['\"](https?://)?localhost:\d+"), "Forbidden hardcoded inter-container localhost URL"))
    return violations


def check_frontend_antipatterns() -> List[Violation]:
    ts_files = list(iter_files(UI_APP_DIR, suffixes=(".ts",)))
    violations: List[Violation] = []
    violations.extend(scan_pattern(ts_files, re.compile(r"(:\s*any\b|<any>|\bas\s+any\b)"), "Forbidden TypeScript 'any' usage"))
    violations.extend(scan_pattern(ts_files, re.compile(r"chart\.js/auto"), "Forbidden chart.js/auto import (use tree-shakeable imports)"))
    violations.extend(scan_pattern(ts_files, re.compile(r"https?://localhost(:\d+)?"), "Forbidden absolute localhost API URL in frontend"))
    return violations


def check_pinned_container_images() -> List[Violation]:
    violations: List[Violation] = []
    targets: List[Path] = []
    for p in REPO_ROOT.rglob("*"):
        if any(part in SKIP_DIR_NAMES for part in p.parts):
            continue
        if p.is_file() and (p.name == "Dockerfile" or re.match(r"docker-compose.*\.ya?ml$", p.name)):
            targets.append(p)

    latest_pattern = re.compile(r"^\s*(FROM|image:)\s+[^\s#]+:latest(\s|$)", re.IGNORECASE)
    violations.extend(scan_pattern(targets, latest_pattern, "Unpinned ':latest' container image tag"))
    return violations


def check_committed_secrets() -> List[Violation]:
    violations: List[Violation] = []
    secret_patterns = [
        (re.compile(r"AIza[0-9A-Za-z_\-]{35}"), "Potential Google/Gemini API key"),
        (re.compile(r"sk-[A-Za-z0-9]{24,}"), "Potential OpenAI-style secret key"),
        (re.compile(r"ghp_[A-Za-z0-9]{36}"), "Potential GitHub personal access token"),
        (re.compile(r"-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"), "Committed private key"),
    ]

    # Check if .env is tracked in git (if git repo exists)
    try:
        tracked = subprocess.check_output(
            ["git", "ls-files", ".env"],
            cwd=REPO_ROOT,
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
        if tracked:
            violations.append((".env", 1, "Secret file .env is tracked by git"))
    except Exception:
        pass

    text_suffixes = (".java", ".py", ".ts", ".html", ".css", ".yml", ".yaml", ".properties", ".json", ".md", ".sql", ".sh", ".ps1", ".conf", ".example")
    scan_files = [
        p for p in iter_files(REPO_ROOT, suffixes=text_suffixes)
        if p.name != ".env" and p.name != "package-lock.json"
    ]
    for regex, label in secret_patterns:
        violations.extend(scan_pattern(scan_files, regex, label))
    return violations


def check_otel_collector_config() -> List[Violation]:
    violations: List[Violation] = []
    if not OTEL_CONFIG_PATH.exists():
        return [("observability/otel-collector.yaml", 1, "Missing OpenTelemetry Collector config")]

    try:
        cfg = yaml.safe_load(OTEL_CONFIG_PATH.read_text(encoding="utf-8"))
    except Exception as exc:
        return [("observability/otel-collector.yaml", 1, f"Invalid YAML: {exc}")]

    if not isinstance(cfg, dict):
        return [("observability/otel-collector.yaml", 1, "Collector config must be a YAML mapping")]

    receivers = cfg.get("receivers") or {}
    processors = cfg.get("processors") or {}
    exporters = cfg.get("exporters") or {}
    extensions = cfg.get("extensions") or {}
    service = cfg.get("service") or {}
    pipelines = service.get("pipelines") or {}

    for required_pipeline in ("traces", "logs", "metrics"):
        if required_pipeline not in pipelines:
            violations.append(("observability/otel-collector.yaml", 1, f"Missing required OTel pipeline '{required_pipeline}'"))
            continue
        pipe = pipelines[required_pipeline]
        for r in pipe.get("receivers", []):
            if r not in receivers:
                violations.append(("observability/otel-collector.yaml", 1, f"Undefined receiver '{r}' in pipeline '{required_pipeline}'"))
        for p in pipe.get("processors", []):
            if p not in processors:
                violations.append(("observability/otel-collector.yaml", 1, f"Undefined processor '{p}' in pipeline '{required_pipeline}'"))
        for e in pipe.get("exporters", []):
            if e not in exporters:
                violations.append(("observability/otel-collector.yaml", 1, f"Undefined exporter '{e}' in pipeline '{required_pipeline}'"))

    for ext in service.get("extensions", []):
        if ext not in extensions:
            violations.append(("observability/otel-collector.yaml", 1, f"Undefined extension '{ext}'"))

    return violations


def main() -> int:
    checks = [
        ("Java control-plane anti-patterns", check_java_antipatterns),
        ("Python adapter security & anti-patterns", check_python_antipatterns),
        ("Angular frontend anti-patterns", check_frontend_antipatterns),
        ("Pinned container image tags", check_pinned_container_images),
        ("Committed secret scan", check_committed_secrets),
        ("OpenTelemetry Collector config validation", check_otel_collector_config),
    ]

    all_violations: List[Violation] = []
    for name, fn in checks:
        found = fn()
        status = "PASS" if not found else f"FAIL ({len(found)} issue(s))"
        print(f"[{status}] {name}")
        all_violations.extend(found)

    if all_violations:
        print("\nViolations detected:")
        for rel, lineno, msg in all_violations:
            print(f"  - {rel}:{lineno}: {msg}")
        return 1

    print("\nAll security, anti-pattern, and OpenTelemetry configuration checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
