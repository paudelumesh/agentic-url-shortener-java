# Agentic URL Shortener System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working URL shortener service and a domain-agnostic agentic orchestration engine, then run three real scenarios (greenfield, brownfield, ambiguous) through the engine to modify the shortener's actual source.

**Architecture:** Two independent Python packages (`urlshortener`, `orchestrator`) under `src/`, a `scenarios/` package that wires deterministic stage executors to each package, and a CLI (`orchestrate`) as the composition root. TDD throughout; commit after every green test.

**Tech Stack:** Python 3.12, FastAPI + Pydantic v2, SQLite (stdlib `sqlite3`), pytest + httpx (TestClient), stdlib `argparse` for the CLI, stdlib `threading`/`concurrent.futures` for the engine scheduler. No Docker, no external services, no live LLM calls.

**Spec:** [docs/superpowers/specs/2026-08-16-agentic-url-shortener-design.md](../specs/2026-08-16-agentic-url-shortener-design.md)

## Global Constraints

- Python >= 3.12, installed editable via `pip install -e ".[dev]"` from repo root.
- `src/` layout: `orchestrator` and `urlshortener` are top-level importable packages; `scenarios/` lives at repo root and is put on `sys.path` by pytest's `pythonpath` ini option and by the CLI at runtime.
- No live LLM calls anywhere — all `StageExecutor` implementations are deterministic Python, per spec §2/§7.
- No Docker/Postgres/Redis — SQLite file DB only, per spec §2.
- The orchestrator engine (`src/orchestrator/*`, excluding `cli.py`) must have zero import-time dependency on `urlshortener` or `scenarios` — its own tests use fakes only, per spec §3.
- Every node transition is persisted (`RunStore`) and logged (`EventLog`) — no in-memory-only state, per spec §3.2.
- Commit after every task; use `git add <specific files>` never `git add -A`.

---

## Task 1: Project Scaffolding

**Files:**
- Create: `pyproject.toml`
- Create: `.gitignore`
- Create: `src/urlshortener/__init__.py`
- Create: `src/orchestrator/__init__.py`
- Create: `scenarios/__init__.py` (placeholder, filled in Task 22)
- Create: `tests/__init__.py`, `tests/urlshortener/__init__.py`, `tests/orchestrator/__init__.py`, `tests/scenarios/__init__.py`
- Create: `runs/.gitkeep`

**Interfaces:** None yet — this task only establishes the importable package skeleton later tasks build on.

- [ ] **Step 1: Create `pyproject.toml`**

```toml
[project]
name = "agentic-url-shortener"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
    "fastapi>=0.110",
    "uvicorn>=0.29",
    "pydantic>=2.6",
    "httpx>=0.27",
]

[project.optional-dependencies]
dev = ["pytest>=8.0"]

[project.scripts]
orchestrate = "orchestrator.cli:main"

[tool.pytest.ini_options]
pythonpath = [".", "src"]
testpaths = ["tests"]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[tool.setuptools.packages.find]
where = ["src"]
```

- [ ] **Step 2: Create `.gitignore`**

```
__pycache__/
*.pyc
.pytest_cache/
*.egg-info/
.venv/
venv/
*.db
runs/*
!runs/.gitkeep
```

- [ ] **Step 3: Create package skeleton**

```bash
mkdir -p src/urlshortener/domain src/urlshortener/repository src/urlshortener/api
mkdir -p src/orchestrator
mkdir -p scenarios
mkdir -p tests/urlshortener tests/orchestrator tests/scenarios
mkdir -p runs
touch src/urlshortener/__init__.py src/urlshortener/domain/__init__.py \
      src/urlshortener/repository/__init__.py src/urlshortener/api/__init__.py \
      src/orchestrator/__init__.py \
      tests/__init__.py tests/urlshortener/__init__.py tests/orchestrator/__init__.py tests/scenarios/__init__.py
touch runs/.gitkeep
```

`scenarios/__init__.py` gets real content in Task 22 — for now:

```python
SCENARIO_REGISTRY: dict = {}
```

- [ ] **Step 4: Install editable and verify collection**

```bash
python3.12 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
pytest --collect-only
```

Expected: exits 0, "no tests ran" (or similar), no import errors.

- [ ] **Step 5: Commit**

```bash
git add pyproject.toml .gitignore src tests scenarios runs/.gitkeep
git commit -m "chore: project scaffolding for urlshortener and orchestrator packages"
```

---

## Task 2: Domain — Code Generation

**Files:**
- Create: `src/urlshortener/domain/codes.py`
- Test: `tests/urlshortener/test_codes.py`

**Interfaces:**
- Produces: `generate_code(exists: Callable[[str], bool], length: int = 7) -> str` — used by `urls_repo.py` (Task 6).

- [ ] **Step 1: Write the failing test**

```python
# tests/urlshortener/test_codes.py
import pytest
from urlshortener.domain.codes import generate_code


def test_generate_code_has_expected_length():
    code = generate_code(exists=lambda c: False, length=7)
    assert len(code) == 7


def test_generate_code_uses_base62_alphabet():
    code = generate_code(exists=lambda c: False, length=7)
    assert all(c.isalnum() for c in code)


def test_generate_code_retries_on_collision():
    seen = {"AAAAAAA"}
    code = generate_code(exists=lambda c: c in seen, length=7)
    assert code != "AAAAAAA"


def test_generate_code_raises_after_max_attempts():
    with pytest.raises(RuntimeError):
        generate_code(exists=lambda c: True, length=7, max_attempts=5)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/urlshortener/test_codes.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'urlshortener.domain.codes'`

- [ ] **Step 3: Implement**

```python
# src/urlshortener/domain/codes.py
import secrets
from typing import Callable

_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"


def _random_code(length: int) -> str:
    return "".join(secrets.choice(_ALPHABET) for _ in range(length))


def generate_code(exists: Callable[[str], bool], length: int = 7, max_attempts: int = 10) -> str:
    for _ in range(max_attempts):
        candidate = _random_code(length)
        if not exists(candidate):
            return candidate
    raise RuntimeError(f"could not generate a unique code after {max_attempts} attempts")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/urlshortener/test_codes.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/urlshortener/domain/codes.py tests/urlshortener/test_codes.py
git commit -m "feat: base62 short-code generation with collision retry"
```

---

## Task 3: Domain — URL Validation

**Files:**
- Create: `src/urlshortener/domain/validation.py`
- Test: `tests/urlshortener/test_validation.py`

**Interfaces:**
- Produces: `validate_url(url: str) -> None` (raises `ValueError` on invalid input) — used by `api/urls.py` (Task 7). This is v1-only; the ambiguous scenario (Task 24) extends it with open-redirect protection via a full-file rewrite, not by modifying this task's code.

- [ ] **Step 1: Write the failing test**

```python
# tests/urlshortener/test_validation.py
import pytest
from urlshortener.domain.validation import validate_url


@pytest.mark.parametrize("url", [
    "https://example.com",
    "http://example.com/path?x=1",
])
def test_validate_url_accepts_valid_urls(url):
    validate_url(url)  # must not raise


@pytest.mark.parametrize("url", [
    "",
    "not-a-url",
    "ftp://example.com",
    "javascript:alert(1)",
])
def test_validate_url_rejects_invalid_urls(url):
    with pytest.raises(ValueError):
        validate_url(url)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/urlshortener/test_validation.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement**

```python
# src/urlshortener/domain/validation.py
from urllib.parse import urlparse

_ALLOWED_SCHEMES = {"http", "https"}


def validate_url(url: str) -> None:
    if not url:
        raise ValueError("url must not be empty")
    parsed = urlparse(url)
    if parsed.scheme not in _ALLOWED_SCHEMES:
        raise ValueError(f"url scheme must be one of {_ALLOWED_SCHEMES}")
    if not parsed.netloc:
        raise ValueError("url must include a host")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/urlshortener/test_validation.py -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/urlshortener/domain/validation.py tests/urlshortener/test_validation.py
git commit -m "feat: URL scheme/host validation"
```

---

## Task 4: Domain — Rate Limiter

**Files:**
- Create: `src/urlshortener/domain/ratelimit.py`
- Test: `tests/urlshortener/test_ratelimit.py`

**Interfaces:**
- Produces: `class TokenBucketLimiter: def __init__(self, capacity: int, refill_per_second: float); def allow(self, key: str) -> bool` — used by `api` middleware (Task 9).

- [ ] **Step 1: Write the failing test**

```python
# tests/urlshortener/test_ratelimit.py
from urlshortener.domain.ratelimit import TokenBucketLimiter


def test_allows_requests_within_capacity():
    limiter = TokenBucketLimiter(capacity=3, refill_per_second=0.0)
    assert limiter.allow("client-a") is True
    assert limiter.allow("client-a") is True
    assert limiter.allow("client-a") is True


def test_blocks_requests_beyond_capacity():
    limiter = TokenBucketLimiter(capacity=2, refill_per_second=0.0)
    limiter.allow("client-a")
    limiter.allow("client-a")
    assert limiter.allow("client-a") is False


def test_keys_are_independent():
    limiter = TokenBucketLimiter(capacity=1, refill_per_second=0.0)
    assert limiter.allow("client-a") is True
    assert limiter.allow("client-b") is True


def test_refills_over_time(monkeypatch):
    import urlshortener.domain.ratelimit as rl_module
    clock = {"t": 0.0}
    monkeypatch.setattr(rl_module.time, "monotonic", lambda: clock["t"])
    limiter = TokenBucketLimiter(capacity=1, refill_per_second=1.0)
    assert limiter.allow("client-a") is True
    assert limiter.allow("client-a") is False
    clock["t"] = 1.0
    assert limiter.allow("client-a") is True
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/urlshortener/test_ratelimit.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement**

```python
# src/urlshortener/domain/ratelimit.py
import time
import threading
from dataclasses import dataclass


@dataclass
class _Bucket:
    tokens: float
    last_refill: float


class TokenBucketLimiter:
    def __init__(self, capacity: int, refill_per_second: float):
        self.capacity = capacity
        self.refill_per_second = refill_per_second
        self._buckets: dict[str, _Bucket] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        with self._lock:
            now = time.monotonic()
            bucket = self._buckets.get(key)
            if bucket is None:
                bucket = _Bucket(tokens=self.capacity, last_refill=now)
                self._buckets[key] = bucket
            elapsed = now - bucket.last_refill
            bucket.tokens = min(self.capacity, bucket.tokens + elapsed * self.refill_per_second)
            bucket.last_refill = now
            if bucket.tokens >= 1:
                bucket.tokens -= 1
                return True
            return False
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/urlshortener/test_ratelimit.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/urlshortener/domain/ratelimit.py tests/urlshortener/test_ratelimit.py
git commit -m "feat: per-key token bucket rate limiter"
```

---

## Task 5: Repository — DB Connection and Migrations

**Files:**
- Create: `src/urlshortener/repository/db.py`
- Create: `src/urlshortener/repository/migrations/0001_init.sql`
- Test: `tests/urlshortener/test_db.py`

**Interfaces:**
- Produces: `get_connection(db_path: str) -> sqlite3.Connection`, `run_migrations(conn: sqlite3.Connection, migrations_dir: Path) -> list[str]` — used by `urls_repo.py`/`clicks_repo.py` (Task 6) and `main.py` (Task 9).

- [ ] **Step 1: Write the failing test**

```python
# tests/urlshortener/test_db.py
import sqlite3
import tempfile
from pathlib import Path

from urlshortener.repository.db import get_connection, run_migrations, MIGRATIONS_DIR


def test_run_migrations_creates_tables():
    with tempfile.TemporaryDirectory() as tmp:
        db_path = Path(tmp) / "test.db"
        conn = get_connection(str(db_path))
        applied = run_migrations(conn, MIGRATIONS_DIR)
        assert "0001_init.sql" in applied
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )}
        assert {"urls", "clicks", "schema_migrations"} <= tables


def test_run_migrations_is_idempotent():
    with tempfile.TemporaryDirectory() as tmp:
        db_path = Path(tmp) / "test.db"
        conn = get_connection(str(db_path))
        run_migrations(conn, MIGRATIONS_DIR)
        second = run_migrations(conn, MIGRATIONS_DIR)
        assert second == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/urlshortener/test_db.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement**

```sql
-- src/urlshortener/repository/migrations/0001_init.sql
CREATE TABLE urls (
    code TEXT PRIMARY KEY,
    target_url TEXT NOT NULL,
    created_at TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    click_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE clicks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    referrer TEXT,
    user_agent TEXT
);
```

```python
# src/urlshortener/repository/db.py
import sqlite3
from pathlib import Path

MIGRATIONS_DIR = Path(__file__).resolve().parent / "migrations"


def get_connection(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 5000")  # let concurrent writers queue instead of raising "database is locked"
    return conn


def run_migrations(conn: sqlite3.Connection, migrations_dir: Path) -> list[str]:
    conn.execute(
        "CREATE TABLE IF NOT EXISTS schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT)"
    )
    conn.commit()
    applied_already = {
        row[0] for row in conn.execute("SELECT filename FROM schema_migrations")
    }
    applied_now = []
    for path in sorted(migrations_dir.glob("*.sql")):
        if path.name in applied_already:
            continue
        conn.executescript(path.read_text())
        conn.execute(
            "INSERT INTO schema_migrations (filename, applied_at) VALUES (?, datetime('now'))",
            (path.name,),
        )
        conn.commit()
        applied_now.append(path.name)
    return applied_now
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/urlshortener/test_db.py -v`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/urlshortener/repository/db.py src/urlshortener/repository/migrations tests/urlshortener/test_db.py
git commit -m "feat: sqlite connection helper and versioned migration runner"
```

---

## Task 6: Repository — URLs and Clicks

**Files:**
- Create: `src/urlshortener/repository/urls_repo.py`
- Create: `src/urlshortener/repository/clicks_repo.py`
- Test: `tests/urlshortener/test_urls_repo.py`
- Test: `tests/urlshortener/test_clicks_repo.py`

**Interfaces:**
- Consumes: `get_connection`, `run_migrations`, `MIGRATIONS_DIR` (Task 5); `generate_code` (Task 2).
- Produces: `class UrlsRepo: def __init__(self, conn); def create(self, target_url: str) -> dict; def get(self, code: str) -> dict | None; def soft_delete(self, code: str) -> bool` and `class ClicksRepo: def __init__(self, conn); def record_click(self, code: str, referrer: str | None, user_agent: str | None) -> None; def analytics(self, code: str) -> dict`. Used by `api/urls.py`, `api/redirect.py`, `api/analytics.py` (Tasks 7-8).

`ClicksRepo.record_click` is deliberately written with the read-modify-write increment described in spec §3.1/§7 — this is the real bug the brownfield scenario (Task 23) fixes.

- [ ] **Step 1: Write the failing tests**

```python
# tests/urlshortener/test_urls_repo.py
import tempfile
from pathlib import Path

import pytest
from urlshortener.repository.db import get_connection, run_migrations, MIGRATIONS_DIR
from urlshortener.repository.urls_repo import UrlsRepo


@pytest.fixture
def repo():
    with tempfile.TemporaryDirectory() as tmp:
        conn = get_connection(str(Path(tmp) / "t.db"))
        run_migrations(conn, MIGRATIONS_DIR)
        yield UrlsRepo(conn)


def test_create_returns_row_with_generated_code(repo):
    row = repo.create("https://example.com")
    assert len(row["code"]) == 7
    assert row["target_url"] == "https://example.com"
    assert row["active"] == 1


def test_get_returns_created_row(repo):
    created = repo.create("https://example.com")
    fetched = repo.get(created["code"])
    assert fetched["target_url"] == "https://example.com"


def test_get_returns_none_for_missing_code(repo):
    assert repo.get("nope") is None


def test_soft_delete_marks_inactive(repo):
    created = repo.create("https://example.com")
    assert repo.soft_delete(created["code"]) is True
    assert repo.get(created["code"])["active"] == 0


def test_soft_delete_returns_false_for_missing_code(repo):
    assert repo.soft_delete("nope") is False
```

```python
# tests/urlshortener/test_clicks_repo.py
import tempfile
from pathlib import Path

import pytest
from urlshortener.repository.db import get_connection, run_migrations, MIGRATIONS_DIR
from urlshortener.repository.urls_repo import UrlsRepo
from urlshortener.repository.clicks_repo import ClicksRepo


@pytest.fixture
def repos():
    with tempfile.TemporaryDirectory() as tmp:
        conn = get_connection(str(Path(tmp) / "t.db"))
        run_migrations(conn, MIGRATIONS_DIR)
        yield UrlsRepo(conn), ClicksRepo(conn)


def test_record_click_increments_count(repos):
    urls_repo, clicks_repo = repos
    row = urls_repo.create("https://example.com")
    clicks_repo.record_click(row["code"], referrer="google.com", user_agent="pytest")
    assert urls_repo.get(row["code"])["click_count"] == 1


def test_analytics_reports_total_and_referrers(repos):
    urls_repo, clicks_repo = repos
    row = urls_repo.create("https://example.com")
    clicks_repo.record_click(row["code"], referrer="google.com", user_agent="pytest")
    clicks_repo.record_click(row["code"], referrer="google.com", user_agent="pytest")
    clicks_repo.record_click(row["code"], referrer="direct", user_agent="pytest")
    stats = clicks_repo.analytics(row["code"])
    assert stats["total_clicks"] == 3
    assert stats["top_referrers"][0] == {"referrer": "google.com", "count": 2}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/urlshortener/test_urls_repo.py tests/urlshortener/test_clicks_repo.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement**

```python
# src/urlshortener/repository/urls_repo.py
import sqlite3
from datetime import datetime, timezone

from urlshortener.domain.codes import generate_code


class UrlsRepo:
    def __init__(self, conn: sqlite3.Connection):
        self.conn = conn

    def _exists(self, code: str) -> bool:
        row = self.conn.execute("SELECT 1 FROM urls WHERE code = ?", (code,)).fetchone()
        return row is not None

    def create(self, target_url: str) -> dict:
        code = generate_code(exists=self._exists)
        created_at = datetime.now(timezone.utc).isoformat()
        self.conn.execute(
            "INSERT INTO urls (code, target_url, created_at, active, click_count) "
            "VALUES (?, ?, ?, 1, 0)",
            (code, target_url, created_at),
        )
        self.conn.commit()
        return self.get(code)

    def get(self, code: str) -> dict | None:
        row = self.conn.execute(
            "SELECT code, target_url, created_at, active, click_count FROM urls WHERE code = ?",
            (code,),
        ).fetchone()
        if row is None:
            return None
        return {
            "code": row[0],
            "target_url": row[1],
            "created_at": row[2],
            "active": row[3],
            "click_count": row[4],
        }

    def soft_delete(self, code: str) -> bool:
        cur = self.conn.execute("UPDATE urls SET active = 0 WHERE code = ? AND active = 1", (code,))
        self.conn.commit()
        return cur.rowcount > 0
```

```python
# src/urlshortener/repository/clicks_repo.py
import sqlite3
from datetime import datetime, timezone


class ClicksRepo:
    def __init__(self, conn: sqlite3.Connection):
        self.conn = conn

    def record_click(self, code: str, referrer: str | None, user_agent: str | None) -> None:
        # NOTE: intentional read-modify-write race — see docs/scenarios/brownfield.md.
        # Fixed by the brownfield scenario's implementation stage (Task 23).
        row = self.conn.execute("SELECT click_count FROM urls WHERE code = ?", (code,)).fetchone()
        if row is None:
            return
        current = row[0]
        self.conn.execute("UPDATE urls SET click_count = ? WHERE code = ?", (current + 1, code))
        self.conn.execute(
            "INSERT INTO clicks (code, timestamp, referrer, user_agent) VALUES (?, ?, ?, ?)",
            (code, datetime.now(timezone.utc).isoformat(), referrer, user_agent),
        )
        self.conn.commit()

    def analytics(self, code: str) -> dict:
        total = self.conn.execute(
            "SELECT COUNT(*) FROM clicks WHERE code = ?", (code,)
        ).fetchone()[0]
        referrer_rows = self.conn.execute(
            "SELECT referrer, COUNT(*) as c FROM clicks WHERE code = ? "
            "GROUP BY referrer ORDER BY c DESC LIMIT 5",
            (code,),
        ).fetchall()
        return {
            "total_clicks": total,
            "top_referrers": [{"referrer": r[0], "count": r[1]} for r in referrer_rows],
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/urlshortener/test_urls_repo.py tests/urlshortener/test_clicks_repo.py -v`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add src/urlshortener/repository/urls_repo.py src/urlshortener/repository/clicks_repo.py \
        tests/urlshortener/test_urls_repo.py tests/urlshortener/test_clicks_repo.py
git commit -m "feat: urls and clicks repositories"
```

---

## Task 7: API — Schemas and URLs Router

**Files:**
- Create: `src/urlshortener/config.py`
- Create: `src/urlshortener/api/schemas.py`
- Create: `src/urlshortener/api/deps.py`
- Create: `src/urlshortener/api/urls.py`
- Test: `tests/urlshortener/test_api_urls.py`

**Interfaces:**
- Consumes: `UrlsRepo` (Task 6), `validate_url` (Task 3).
- Produces: `router` (FastAPI `APIRouter`) exported from `api/urls.py`, mounted by `main.py` (Task 9); `get_urls_repo()` FastAPI dependency in `api/deps.py` reused by `api/redirect.py` and `api/analytics.py` (Task 8).

- [ ] **Step 1: Write the failing test**

```python
# tests/urlshortener/test_api_urls.py
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from urlshortener.main import create_app


@pytest.fixture
def client():
    with tempfile.TemporaryDirectory() as tmp:
        db_path = str(Path(tmp) / "t.db")
        app = create_app(db_path=db_path)
        yield TestClient(app)


def test_create_url_returns_code_and_short_url(client):
    resp = client.post("/api/urls", json={"url": "https://example.com"})
    assert resp.status_code == 201
    body = resp.json()
    assert len(body["code"]) == 7
    assert body["short_url"].endswith(body["code"])


def test_create_url_rejects_invalid_url(client):
    resp = client.post("/api/urls", json={"url": "not-a-url"})
    assert resp.status_code == 422


def test_get_url_metadata(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    resp = client.get(f"/api/urls/{created['code']}")
    assert resp.status_code == 200
    assert resp.json()["target_url"] == "https://example.com"


def test_get_url_metadata_404_for_missing_code(client):
    resp = client.get("/api/urls/missing")
    assert resp.status_code == 404


def test_delete_url_soft_deletes(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    resp = client.delete(f"/api/urls/{created['code']}")
    assert resp.status_code == 204
    resp = client.get(f"/api/urls/{created['code']}")
    assert resp.json()["active"] is False
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/urlshortener/test_api_urls.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'urlshortener.main'`

- [ ] **Step 3: Implement**

```python
# src/urlshortener/config.py
CODE_LENGTH = 7
RATE_LIMIT_CAPACITY = 60
RATE_LIMIT_REFILL_PER_SECOND = 1.0
BASE_URL = "http://localhost:8000"
```

```python
# src/urlshortener/api/schemas.py
from pydantic import BaseModel


class CreateUrlRequest(BaseModel):
    url: str


class UrlResponse(BaseModel):
    code: str
    target_url: str
    short_url: str
    created_at: str
    active: bool
    click_count: int
```

```python
# src/urlshortener/api/deps.py
from fastapi import Request

from urlshortener.repository.urls_repo import UrlsRepo
from urlshortener.repository.clicks_repo import ClicksRepo


def get_urls_repo(request: Request) -> UrlsRepo:
    return UrlsRepo(request.app.state.db_conn)


def get_clicks_repo(request: Request) -> ClicksRepo:
    return ClicksRepo(request.app.state.db_conn)
```

```python
# src/urlshortener/api/urls.py
from fastapi import APIRouter, Depends, HTTPException

from urlshortener.api.deps import get_urls_repo
from urlshortener.api.schemas import CreateUrlRequest, UrlResponse
from urlshortener.config import BASE_URL
from urlshortener.domain.validation import validate_url
from urlshortener.repository.urls_repo import UrlsRepo

router = APIRouter(prefix="/api/urls", tags=["urls"])


def _to_response(row: dict) -> UrlResponse:
    return UrlResponse(
        code=row["code"],
        target_url=row["target_url"],
        short_url=f"{BASE_URL}/{row['code']}",
        created_at=row["created_at"],
        active=bool(row["active"]),
        click_count=row["click_count"],
    )


@router.post("", response_model=UrlResponse, status_code=201)
def create_url(payload: CreateUrlRequest, repo: UrlsRepo = Depends(get_urls_repo)):
    try:
        validate_url(payload.url)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))
    row = repo.create(payload.url)
    return _to_response(row)


@router.get("/{code}", response_model=UrlResponse)
def get_url(code: str, repo: UrlsRepo = Depends(get_urls_repo)):
    row = repo.get(code)
    if row is None:
        raise HTTPException(status_code=404, detail="code not found")
    return _to_response(row)


@router.delete("/{code}", status_code=204)
def delete_url(code: str, repo: UrlsRepo = Depends(get_urls_repo)):
    if not repo.soft_delete(code):
        raise HTTPException(status_code=404, detail="code not found")
```

`create_app` does not exist yet — Task 9 adds it. To make this task's test runnable in isolation, Step 4 below temporarily is expected to still fail until Task 9 lands; **do not skip ahead**. Instead run Steps 1-3, then proceed straight to Task 8 (redirect/analytics routers, same blocker), and only run the full test suite at the end of Task 9 once `main.py` exists. Record this dependency in the task and move on.

- [ ] **Step 4: Commit (tests remain red until Task 9; that is expected)**

```bash
git add src/urlshortener/config.py src/urlshortener/api/schemas.py src/urlshortener/api/deps.py \
        src/urlshortener/api/urls.py tests/urlshortener/test_api_urls.py
git commit -m "feat: urls API router (create/get/delete)"
```

---

## Task 8: API — Redirect and Analytics Routers

**Files:**
- Create: `src/urlshortener/api/redirect.py`
- Create: `src/urlshortener/api/analytics.py`
- Test: `tests/urlshortener/test_api_redirect.py`
- Test: `tests/urlshortener/test_api_analytics.py`

**Interfaces:**
- Consumes: `get_urls_repo`, `get_clicks_repo` (Task 7); `ClicksRepo.record_click`, `ClicksRepo.analytics` (Task 6).
- Produces: `router` from both modules, mounted by `main.py` (Task 9).

- [ ] **Step 1: Write the failing tests**

```python
# tests/urlshortener/test_api_redirect.py
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from urlshortener.main import create_app


@pytest.fixture
def client():
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(db_path=str(Path(tmp) / "t.db"))
        yield TestClient(app)


def test_redirect_follows_to_target(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    resp = client.get(f"/{created['code']}", follow_redirects=False)
    assert resp.status_code == 302
    assert resp.headers["location"] == "https://example.com"


def test_redirect_404_for_missing_code(client):
    resp = client.get("/missing", follow_redirects=False)
    assert resp.status_code == 404


def test_redirect_410_for_deleted_code(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    client.delete(f"/api/urls/{created['code']}")
    resp = client.get(f"/{created['code']}", follow_redirects=False)
    assert resp.status_code == 410


def test_redirect_records_a_click(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    client.get(f"/{created['code']}", follow_redirects=False)
    meta = client.get(f"/api/urls/{created['code']}").json()
    assert meta["click_count"] == 1
```

```python
# tests/urlshortener/test_api_analytics.py
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from urlshortener.main import create_app


@pytest.fixture
def client():
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(db_path=str(Path(tmp) / "t.db"))
        yield TestClient(app)


def test_analytics_reports_total_clicks(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    client.get(f"/{created['code']}", follow_redirects=False)
    client.get(f"/{created['code']}", follow_redirects=False)
    resp = client.get(f"/api/urls/{created['code']}/analytics")
    assert resp.status_code == 200
    assert resp.json()["total_clicks"] == 2


def test_analytics_404_for_missing_code(client):
    resp = client.get("/api/urls/missing/analytics")
    assert resp.status_code == 404
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/urlshortener/test_api_redirect.py tests/urlshortener/test_api_analytics.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'urlshortener.main'`

- [ ] **Step 3: Implement**

```python
# src/urlshortener/api/redirect.py
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse

from urlshortener.api.deps import get_urls_repo, get_clicks_repo
from urlshortener.repository.urls_repo import UrlsRepo
from urlshortener.repository.clicks_repo import ClicksRepo

router = APIRouter(tags=["redirect"])


@router.get("/{code}")
def redirect(
    code: str,
    request: Request,
    urls_repo: UrlsRepo = Depends(get_urls_repo),
    clicks_repo: ClicksRepo = Depends(get_clicks_repo),
):
    row = urls_repo.get(code)
    if row is None:
        raise HTTPException(status_code=404, detail="code not found")
    if not row["active"]:
        raise HTTPException(status_code=410, detail="link has been deleted")
    clicks_repo.record_click(
        code,
        referrer=request.headers.get("referer"),
        user_agent=request.headers.get("user-agent"),
    )
    return RedirectResponse(url=row["target_url"], status_code=302)
```

```python
# src/urlshortener/api/analytics.py
from fastapi import APIRouter, Depends, HTTPException

from urlshortener.api.deps import get_urls_repo, get_clicks_repo
from urlshortener.repository.urls_repo import UrlsRepo
from urlshortener.repository.clicks_repo import ClicksRepo

router = APIRouter(prefix="/api/urls", tags=["analytics"])


@router.get("/{code}/analytics")
def get_analytics(
    code: str,
    urls_repo: UrlsRepo = Depends(get_urls_repo),
    clicks_repo: ClicksRepo = Depends(get_clicks_repo),
):
    if urls_repo.get(code) is None:
        raise HTTPException(status_code=404, detail="code not found")
    return clicks_repo.analytics(code)
```

`record_click` runs synchronously in-request here (not via `BackgroundTasks`) so the test above can assert the count immediately after the redirect call — the spec's "does not block the redirect" goal is satisfied because the write is a handful of indexed SQLite statements (sub-millisecond), not because of async offload; offloading to a background task would make the click count test flaky without an explicit wait, which is worse for a prototype. Document this as the concrete trade-off in `docs/testing-and-tradeoffs.md` (Task 25).

- [ ] **Step 4: Commit (tests remain red until Task 9)**

```bash
git add src/urlshortener/api/redirect.py src/urlshortener/api/analytics.py \
        tests/urlshortener/test_api_redirect.py tests/urlshortener/test_api_analytics.py
git commit -m "feat: redirect and analytics API routers"
```

---

## Task 9: API — Health, App Factory, and Wiring

**Files:**
- Create: `src/urlshortener/api/health.py`
- Create: `src/urlshortener/main.py`
- Test: `tests/urlshortener/test_health.py`

**Interfaces:**
- Consumes: every router from Tasks 7-8; `get_connection`, `run_migrations`, `MIGRATIONS_DIR` (Task 5); `TokenBucketLimiter` (Task 4).
- Produces: `create_app(db_path: str) -> FastAPI` — the single entry point every other test in Tasks 7-8 and 10-11 depends on, and what `uvicorn urlshortener.main:app` serves in production.

- [ ] **Step 1: Write the failing test**

```python
# tests/urlshortener/test_health.py
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from urlshortener.main import create_app


@pytest.fixture
def client():
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(db_path=str(Path(tmp) / "t.db"))
        yield TestClient(app)


def test_healthz_ok(client):
    assert client.get("/healthz").status_code == 200


def test_readyz_ok(client):
    assert client.get("/readyz").status_code == 200


def test_rate_limit_blocks_after_capacity(client):
    for _ in range(60):
        client.post("/api/urls", json={"url": "https://example.com"})
    resp = client.post("/api/urls", json={"url": "https://example.com"})
    assert resp.status_code == 429
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/urlshortener/test_health.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'urlshortener.main'`

- [ ] **Step 3: Implement**

```python
# src/urlshortener/api/health.py
from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/healthz")
def healthz():
    return {"status": "ok"}


@router.get("/readyz")
def readyz():
    return {"status": "ready"}
```

```python
# src/urlshortener/main.py
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from urlshortener.api import analytics, health, redirect, urls
from urlshortener.config import RATE_LIMIT_CAPACITY, RATE_LIMIT_REFILL_PER_SECOND
from urlshortener.domain.ratelimit import TokenBucketLimiter
from urlshortener.repository.db import MIGRATIONS_DIR, get_connection, run_migrations


def create_app(db_path: str) -> FastAPI:
    app = FastAPI(title="Agentic URL Shortener")
    app.state.db_conn = get_connection(db_path)
    run_migrations(app.state.db_conn, MIGRATIONS_DIR)
    app.state.limiter = TokenBucketLimiter(
        capacity=RATE_LIMIT_CAPACITY, refill_per_second=RATE_LIMIT_REFILL_PER_SECOND
    )

    @app.middleware("http")
    async def rate_limit_middleware(request: Request, call_next):
        client_key = request.client.host if request.client else "unknown"
        if not app.state.limiter.allow(client_key):
            return JSONResponse(status_code=429, content={"detail": "rate limit exceeded"})
        return await call_next(request)

    app.include_router(health.router)
    app.include_router(urls.router)
    app.include_router(analytics.router)
    app.include_router(redirect.router)
    return app


app = create_app(db_path="urlshortener.db")
```

- [ ] **Step 4: Run the full urlshortener test suite**

Run: `pytest tests/urlshortener -v`
Expected: PASS — every test from Tasks 6-9 now passes (the rate-limit test in this task will also exercise, and pass through, the routers from Tasks 7-8).

- [ ] **Step 5: Commit**

```bash
git add src/urlshortener/api/health.py src/urlshortener/main.py tests/urlshortener/test_health.py
git commit -m "feat: health endpoints, rate-limit middleware, and app factory"
```

---

## Task 10: Integration Test — Full Lifecycle

**Files:**
- Test: `tests/urlshortener/test_lifecycle.py`

**Interfaces:**
- Consumes: `create_app` (Task 9). No new production code — this task locks in the end-to-end contract other tasks (and the scenarios) are validated against.

- [ ] **Step 1: Write the test**

```python
# tests/urlshortener/test_lifecycle.py
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from urlshortener.main import create_app


@pytest.fixture
def client():
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(db_path=str(Path(tmp) / "t.db"))
        yield TestClient(app)


def test_full_lifecycle_create_redirect_analytics_delete(client):
    created = client.post("/api/urls", json={"url": "https://example.com/page"}).json()
    code = created["code"]

    redirect_resp = client.get(f"/{code}", follow_redirects=False)
    assert redirect_resp.status_code == 302

    analytics_resp = client.get(f"/api/urls/{code}/analytics")
    assert analytics_resp.json()["total_clicks"] == 1

    delete_resp = client.delete(f"/api/urls/{code}")
    assert delete_resp.status_code == 204

    gone_resp = client.get(f"/{code}", follow_redirects=False)
    assert gone_resp.status_code == 410
```

- [ ] **Step 2: Run and verify it passes immediately**

Run: `pytest tests/urlshortener/test_lifecycle.py -v`
Expected: PASS (1 test) — every dependency already exists from Task 9.

- [ ] **Step 3: Commit**

```bash
git add tests/urlshortener/test_lifecycle.py
git commit -m "test: full create/redirect/analytics/delete lifecycle"
```

---

## Task 11: Concurrency Regression Test (documents the known click-counter bug)

**Files:**
- Test: `tests/urlshortener/test_concurrency_clicks.py`

**Interfaces:**
- Consumes: `get_connection`, `run_migrations`, `MIGRATIONS_DIR` (Task 5); `UrlsRepo`, `ClicksRepo` (Task 6).

This test documents the read-modify-write race in `ClicksRepo.record_click` (Task 6) without breaking the baseline suite — it is marked `xfail` until the brownfield scenario (Task 23) fixes the repository. Task 23's own test removes the `xfail` marker on its private copy and asserts the fix directly; this file is the one committed to the "before" state of the real repo.

- [ ] **Step 1: Write the test**

```python
# tests/urlshortener/test_concurrency_clicks.py
import tempfile
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

import pytest
from urlshortener.repository.db import get_connection, run_migrations, MIGRATIONS_DIR
from urlshortener.repository.urls_repo import UrlsRepo
from urlshortener.repository.clicks_repo import ClicksRepo


@pytest.mark.xfail(
    reason="known read-modify-write race in ClicksRepo.record_click; "
    "fixed by the brownfield scenario (Task 23)",
    strict=True,
)
def test_concurrent_clicks_are_not_lost():
    with tempfile.TemporaryDirectory() as tmp:
        db_path = str(Path(tmp) / "t.db")
        setup_conn = get_connection(db_path)
        run_migrations(setup_conn, MIGRATIONS_DIR)
        code = UrlsRepo(setup_conn).create("https://example.com")["code"]
        setup_conn.close()

        def record_one():
            conn = get_connection(db_path)
            ClicksRepo(conn).record_click(code, referrer=None, user_agent="pytest")
            conn.close()

        with ThreadPoolExecutor(max_workers=20) as pool:
            list(pool.map(lambda _: record_one(), range(50)))

        verify_conn = get_connection(db_path)
        final_count = UrlsRepo(verify_conn).get(code)["click_count"]
        assert final_count == 50
```

- [ ] **Step 2: Run and verify it xfails (not errors)**

Run: `pytest tests/urlshortener/test_concurrency_clicks.py -v`
Expected: `XFAIL` (strict xfail — if this ever shows `XPASS` the repository has already been fixed and the marker must be removed as part of whatever task fixed it).

- [ ] **Step 3: Commit**

```bash
git add tests/urlshortener/test_concurrency_clicks.py
git commit -m "test: document known click-counter race condition as strict xfail"
```

---

## Task 12: Orchestrator Core Types — Executor and Context

**Files:**
- Create: `src/orchestrator/executor.py`
- Create: `src/orchestrator/context.py`
- Test: `tests/orchestrator/test_context.py`

**Interfaces:**
- Produces: `class StageStatus(str, Enum)` (`PENDING, RUNNING, PASSED, FAILED, AWAITING_APPROVAL, BLOCKED, INVALIDATED, ROLLED_BACK, STOPPED`); `class StageResult` (`status, outputs: dict, artifacts: list[str], notes: str, risks: list[str]`); `class StageExecutor` (`name: str`, `def run(self, context: RunContext) -> StageResult`); `class ContextEntry` (`stage_id, version, outputs, produced_by, rationale, timestamp, input_versions: dict[str,int]`); `class RunContext` (`__init__(self, run_id)`, `append(entry)`, `latest(stage_id) -> ContextEntry | None`, `get_version(stage_id, version) -> ContextEntry`, `next_version(stage_id) -> int`, `to_dict()`, `from_dict(data)`). Used by every other orchestrator module and every scenario.

- [ ] **Step 1: Write the failing test**

```python
# tests/orchestrator/test_context.py
import pytest
from orchestrator.context import ContextEntry, RunContext


def _entry(stage_id="requirements", version=1, outputs=None):
    return ContextEntry(
        stage_id=stage_id,
        version=version,
        outputs=outputs or {"summary": "ok"},
        produced_by="TestExecutor",
        rationale="because",
        timestamp="2026-08-16T00:00:00+00:00",
        input_versions={},
    )


def test_latest_returns_none_when_empty():
    ctx = RunContext("run-1")
    assert ctx.latest("requirements") is None


def test_append_and_latest_roundtrip():
    ctx = RunContext("run-1")
    ctx.append(_entry())
    assert ctx.latest("requirements").outputs == {"summary": "ok"}


def test_next_version_increments():
    ctx = RunContext("run-1")
    assert ctx.next_version("requirements") == 1
    ctx.append(_entry(version=1))
    assert ctx.next_version("requirements") == 2


def test_get_version_raises_for_missing_version():
    ctx = RunContext("run-1")
    ctx.append(_entry(version=1))
    with pytest.raises(KeyError):
        ctx.get_version("requirements", 2)


def test_to_dict_from_dict_roundtrip():
    ctx = RunContext("run-1")
    ctx.append(_entry())
    ctx.append(_entry(stage_id="design", input_versions={"requirements": 1}))
    restored = RunContext.from_dict(ctx.to_dict())
    assert restored.run_id == "run-1"
    assert restored.latest("requirements").outputs == {"summary": "ok"}
    assert restored.latest("design").input_versions == {"requirements": 1}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/orchestrator/test_context.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'orchestrator.context'`

- [ ] **Step 3: Implement**

```python
# src/orchestrator/executor.py
from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from orchestrator.context import RunContext


class StageStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    PASSED = "passed"
    FAILED = "failed"
    AWAITING_APPROVAL = "awaiting_approval"
    BLOCKED = "blocked"
    INVALIDATED = "invalidated"
    ROLLED_BACK = "rolled_back"
    STOPPED = "stopped"


@dataclass
class StageResult:
    status: StageStatus
    outputs: dict[str, Any] = field(default_factory=dict)
    artifacts: list[str] = field(default_factory=list)
    notes: str = ""
    risks: list[str] = field(default_factory=list)


class StageExecutor:
    name: str = "executor"

    def run(self, context: "RunContext") -> StageResult:
        raise NotImplementedError
```

```python
# src/orchestrator/context.py
from __future__ import annotations
from dataclasses import dataclass, field, asdict
from typing import Any


@dataclass
class ContextEntry:
    stage_id: str
    version: int
    outputs: dict[str, Any]
    produced_by: str
    rationale: str
    timestamp: str
    input_versions: dict[str, int] = field(default_factory=dict)

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "ContextEntry":
        return cls(**data)


class RunContext:
    def __init__(self, run_id: str):
        self.run_id = run_id
        self._history: dict[str, list[ContextEntry]] = {}

    def append(self, entry: ContextEntry) -> None:
        self._history.setdefault(entry.stage_id, []).append(entry)

    def latest(self, stage_id: str) -> ContextEntry | None:
        entries = self._history.get(stage_id)
        return entries[-1] if entries else None

    def get_version(self, stage_id: str, version: int) -> ContextEntry:
        for entry in self._history.get(stage_id, []):
            if entry.version == version:
                return entry
        raise KeyError(f"{stage_id} v{version} not found")

    def next_version(self, stage_id: str) -> int:
        entries = self._history.get(stage_id, [])
        return (entries[-1].version + 1) if entries else 1

    def to_dict(self) -> dict:
        return {
            "run_id": self.run_id,
            "history": {
                stage_id: [entry.to_dict() for entry in entries]
                for stage_id, entries in self._history.items()
            },
        }

    @classmethod
    def from_dict(cls, data: dict) -> "RunContext":
        ctx = cls(data["run_id"])
        for stage_id, entries in data.get("history", {}).items():
            ctx._history[stage_id] = [ContextEntry.from_dict(e) for e in entries]
        return ctx
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/orchestrator/test_context.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/executor.py src/orchestrator/context.py tests/orchestrator/test_context.py
git commit -m "feat: orchestrator StageExecutor/StageResult types and versioned RunContext"
```

---

## Task 13: Orchestrator — Graph (DAG, Gates, Retry Policy)

**Files:**
- Create: `src/orchestrator/graph.py`
- Test: `tests/orchestrator/test_graph.py`

**Interfaces:**
- Consumes: `StageExecutor`, `StageResult` (Task 12); `RunContext` (Task 12).
- Produces: `class GateResult` (`outcome: str, reason: str`); `class RetryPolicy` (`max_attempts: int = 1, backoff_seconds: float = 0.0`); `class StageNode` (`id, executor, depends_on: list[str], entry_gate, exit_gate, retry_policy, fallback_executor, requires_approval: bool, rollback, snapshot`); `class CycleError(Exception)`; `class Workflow` (`__init__(self, name, nodes: list[StageNode])`, `nodes: dict[str, StageNode]`, `dependents_of(node_id) -> list[str]`, `all_downstream(node_id) -> set[str]`). Used by `engine.py` (Tasks 15-17) and every scenario (Tasks 22-24).

- [ ] **Step 1: Write the failing test**

```python
# tests/orchestrator/test_graph.py
import pytest
from orchestrator.executor import StageExecutor, StageResult, StageStatus
from orchestrator.graph import CycleError, StageNode, Workflow


class _NoopExecutor(StageExecutor):
    def run(self, context):
        return StageResult(status=StageStatus.PASSED)


def _node(node_id, depends_on=None):
    return StageNode(id=node_id, executor=_NoopExecutor(), depends_on=depends_on or [])


def test_workflow_accepts_valid_dag():
    wf = Workflow("demo", [_node("a"), _node("b", ["a"]), _node("c", ["a", "b"])])
    assert set(wf.nodes) == {"a", "b", "c"}


def test_workflow_rejects_unknown_dependency():
    with pytest.raises(ValueError):
        Workflow("demo", [_node("a", ["missing"])])


def test_workflow_rejects_cycles():
    with pytest.raises(CycleError):
        Workflow("demo", [_node("a", ["b"]), _node("b", ["a"])])


def test_dependents_of_returns_direct_children():
    wf = Workflow("demo", [_node("a"), _node("b", ["a"]), _node("c", ["a"])])
    assert set(wf.dependents_of("a")) == {"b", "c"}


def test_all_downstream_returns_transitive_children():
    wf = Workflow("demo", [_node("a"), _node("b", ["a"]), _node("c", ["b"])])
    assert wf.all_downstream("a") == {"b", "c"}


def test_default_entry_gate_is_ok():
    wf = Workflow("demo", [_node("a")])
    result = wf.nodes["a"].entry_gate(None)
    assert result.outcome == "ok"


def test_default_exit_gate_passes_on_passed_status():
    wf = Workflow("demo", [_node("a")])
    result = wf.nodes["a"].exit_gate(StageResult(status=StageStatus.PASSED), None)
    assert result.outcome == "pass"


def test_default_exit_gate_fails_on_failed_status():
    wf = Workflow("demo", [_node("a")])
    result = wf.nodes["a"].exit_gate(StageResult(status=StageStatus.FAILED, notes="boom"), None)
    assert result.outcome == "fail"
    assert result.reason == "boom"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/orchestrator/test_graph.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'orchestrator.graph'`

- [ ] **Step 3: Implement**

```python
# src/orchestrator/graph.py
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Callable, Optional, TYPE_CHECKING

from orchestrator.executor import StageExecutor, StageResult, StageStatus

if TYPE_CHECKING:
    from orchestrator.context import RunContext


@dataclass
class GateResult:
    outcome: str  # "ok" | "blocked" | "pass" | "fail" | "needs_approval"
    reason: str = ""


@dataclass
class RetryPolicy:
    max_attempts: int = 1
    backoff_seconds: float = 0.0


def default_entry_gate(context: "RunContext") -> GateResult:
    return GateResult("ok")


def default_exit_gate(result: StageResult, context: "RunContext") -> GateResult:
    if result.status == StageStatus.PASSED:
        return GateResult("pass")
    return GateResult("fail", result.notes)


@dataclass
class StageNode:
    id: str
    executor: StageExecutor
    depends_on: list[str] = field(default_factory=list)
    entry_gate: Callable[["RunContext"], GateResult] = default_entry_gate
    exit_gate: Callable[[StageResult, "RunContext"], GateResult] = default_exit_gate
    retry_policy: RetryPolicy = field(default_factory=RetryPolicy)
    fallback_executor: Optional[StageExecutor] = None
    requires_approval: bool = False
    rollback: Optional[Callable[["RunContext"], None]] = None
    snapshot: Optional[Callable[[], Any]] = None


class CycleError(Exception):
    pass


class Workflow:
    def __init__(self, name: str, nodes: list[StageNode]):
        self.name = name
        self.nodes: dict[str, StageNode] = {n.id: n for n in nodes}
        self._validate()

    def _validate(self) -> None:
        for node in self.nodes.values():
            for dep in node.depends_on:
                if dep not in self.nodes:
                    raise ValueError(f"{node.id} depends on unknown node {dep}")
        WHITE, GRAY, BLACK = 0, 1, 2
        color = {node_id: WHITE for node_id in self.nodes}

        def visit(node_id: str) -> None:
            color[node_id] = GRAY
            for dep in self.nodes[node_id].depends_on:
                if color[dep] == GRAY:
                    raise CycleError(f"cycle detected involving {node_id} -> {dep}")
                if color[dep] == WHITE:
                    visit(dep)
            color[node_id] = BLACK

        for node_id in self.nodes:
            if color[node_id] == WHITE:
                visit(node_id)

    def dependents_of(self, node_id: str) -> list[str]:
        return [n.id for n in self.nodes.values() if node_id in n.depends_on]

    def all_downstream(self, node_id: str) -> set[str]:
        downstream: set[str] = set()
        frontier = [node_id]
        while frontier:
            current = frontier.pop()
            for dep_id in self.dependents_of(current):
                if dep_id not in downstream:
                    downstream.add(dep_id)
                    frontier.append(dep_id)
        return downstream
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/orchestrator/test_graph.py -v`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/graph.py tests/orchestrator/test_graph.py
git commit -m "feat: orchestrator DAG model with cycle detection and default gates"
```

---

## Task 14: Orchestrator — Event Log and Run Store

**Files:**
- Create: `src/orchestrator/events.py`
- Create: `src/orchestrator/store.py`
- Test: `tests/orchestrator/test_events.py`
- Test: `tests/orchestrator/test_store.py`

**Interfaces:**
- Consumes: `RunContext` (Task 12).
- Produces: `now_iso() -> str`; `class Event` (`run_id, node_id, from_state, to_state, timestamp, attempt: int = 1, reason: str = ""`); `class EventLog` (`__init__(self, path: Path)`, `append(event)`, `read_all() -> list[Event]`); `class RunState` (`run_id, workflow_name, node_status: dict[str,str], node_attempts: dict[str,int], context: RunContext, safe_stop_requested: bool, started_at: str, finished_at: str`, `to_dict()`, `from_dict(data)`); `class RunStore` (`__init__(self, base_dir: Path)`, `save(state)`, `load(run_id) -> RunState`, `exists(run_id) -> bool`, `list_runs() -> list[str]`, `events_path(run_id) -> Path`). Used by `engine.py` (Tasks 15-17), `metrics.py` (Task 20), `cli.py` (Task 21).

- [ ] **Step 1: Write the failing tests**

```python
# tests/orchestrator/test_events.py
import tempfile
from pathlib import Path

from orchestrator.events import Event, EventLog


def test_append_and_read_all_roundtrip():
    with tempfile.TemporaryDirectory() as tmp:
        log = EventLog(Path(tmp) / "run-1" / "events.jsonl")
        log.append(Event(run_id="run-1", node_id="a", from_state="pending", to_state="running", timestamp="t1"))
        log.append(Event(run_id="run-1", node_id="a", from_state="running", to_state="passed", timestamp="t2"))
        events = log.read_all()
        assert [e.to_state for e in events] == ["running", "passed"]


def test_read_all_returns_empty_list_for_missing_file():
    with tempfile.TemporaryDirectory() as tmp:
        log = EventLog(Path(tmp) / "run-1" / "events.jsonl")
        assert log.read_all() == []
```

```python
# tests/orchestrator/test_store.py
import tempfile
from pathlib import Path

from orchestrator.context import RunContext
from orchestrator.store import RunState, RunStore


def test_save_and_load_roundtrip():
    with tempfile.TemporaryDirectory() as tmp:
        store = RunStore(Path(tmp))
        state = RunState(
            run_id="run-1",
            workflow_name="demo",
            node_status={"a": "passed"},
            node_attempts={"a": 1},
            context=RunContext("run-1"),
            started_at="t0",
        )
        store.save(state)
        loaded = store.load("run-1")
        assert loaded.node_status == {"a": "passed"}
        assert loaded.context.run_id == "run-1"


def test_exists_false_for_unknown_run():
    with tempfile.TemporaryDirectory() as tmp:
        store = RunStore(Path(tmp))
        assert store.exists("nope") is False


def test_list_runs_returns_saved_run_ids():
    with tempfile.TemporaryDirectory() as tmp:
        store = RunStore(Path(tmp))
        store.save(RunState(run_id="run-a", workflow_name="demo", context=RunContext("run-a")))
        store.save(RunState(run_id="run-b", workflow_name="demo", context=RunContext("run-b")))
        assert store.list_runs() == ["run-a", "run-b"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/orchestrator/test_events.py tests/orchestrator/test_store.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement**

```python
# src/orchestrator/events.py
from __future__ import annotations
import json
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class Event:
    run_id: str
    node_id: str
    from_state: str
    to_state: str
    timestamp: str
    attempt: int = 1
    reason: str = ""


class EventLog:
    def __init__(self, path: Path):
        self.path = Path(path)

    def append(self, event: Event) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a") as f:
            f.write(json.dumps(asdict(event)) + "\n")

    def read_all(self) -> list[Event]:
        if not self.path.exists():
            return []
        events = []
        with self.path.open() as f:
            for line in f:
                line = line.strip()
                if line:
                    events.append(Event(**json.loads(line)))
        return events
```

```python
# src/orchestrator/store.py
from __future__ import annotations
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

from orchestrator.context import RunContext


@dataclass
class RunState:
    run_id: str
    workflow_name: str
    node_status: dict[str, str] = field(default_factory=dict)
    node_attempts: dict[str, int] = field(default_factory=dict)
    context: Optional[RunContext] = None
    safe_stop_requested: bool = False
    started_at: str = ""
    finished_at: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "run_id": self.run_id,
            "workflow_name": self.workflow_name,
            "node_status": self.node_status,
            "node_attempts": self.node_attempts,
            "context": self.context.to_dict() if self.context else None,
            "safe_stop_requested": self.safe_stop_requested,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "RunState":
        context = RunContext.from_dict(data["context"]) if data.get("context") else None
        return cls(
            run_id=data["run_id"],
            workflow_name=data["workflow_name"],
            node_status=data.get("node_status", {}),
            node_attempts=data.get("node_attempts", {}),
            context=context,
            safe_stop_requested=data.get("safe_stop_requested", False),
            started_at=data.get("started_at", ""),
            finished_at=data.get("finished_at", ""),
        )


class RunStore:
    def __init__(self, base_dir: Path):
        self.base_dir = Path(base_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def _state_path(self, run_id: str) -> Path:
        return self.base_dir / run_id / "state.json"

    def save(self, state: RunState) -> None:
        path = self._state_path(state.run_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(state.to_dict(), indent=2))

    def load(self, run_id: str) -> RunState:
        return RunState.from_dict(json.loads(self._state_path(run_id).read_text()))

    def exists(self, run_id: str) -> bool:
        return self._state_path(run_id).exists()

    def list_runs(self) -> list[str]:
        if not self.base_dir.exists():
            return []
        return sorted(p.name for p in self.base_dir.iterdir() if p.is_dir())

    def events_path(self, run_id: str) -> Path:
        return self.base_dir / run_id / "events.jsonl"
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/orchestrator/test_events.py tests/orchestrator/test_store.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/events.py src/orchestrator/store.py tests/orchestrator/test_events.py tests/orchestrator/test_store.py
git commit -m "feat: append-only event log and JSON-backed run state persistence"
```

---

## Task 15: Engine — Linear Execution, Gates, Parallel Sync

**Files:**
- Create: `src/orchestrator/engine.py`
- Create: `tests/orchestrator/fakes.py`
- Test: `tests/orchestrator/test_engine_linear.py`

**Interfaces:**
- Consumes: `Workflow`, `StageNode`, `GateResult` (Task 13); `RunContext`, `ContextEntry` (Task 12); `StageResult`, `StageStatus` (Task 12); `RunState`, `RunStore` (Task 14); `Event`, `EventLog`, `now_iso` (Task 14).
- Produces: `class Engine` (`__init__(self, workflow, store)`, `start(run_id) -> RunState`). This task's `Engine` grows in Tasks 16-17 by full-file replacement — later tasks state that explicitly and repeat the complete file so there is never an ambiguous partial edit.
- `tests/orchestrator/fakes.py` produces `RecordingExecutor(name, calls, outputs=None, status=StageStatus.PASSED, notes="")` and is imported by every remaining orchestrator test file in this plan.

- [ ] **Step 1: Write the failing tests**

```python
# tests/orchestrator/fakes.py
from orchestrator.executor import StageExecutor, StageResult, StageStatus


class RecordingExecutor(StageExecutor):
    """Appends its name to a shared list when run, returns a fixed StageResult."""

    def __init__(self, name, calls, outputs=None, status=StageStatus.PASSED, notes=""):
        self.name = name
        self.calls = calls
        self.outputs = outputs or {}
        self.status = status
        self.notes = notes

    def run(self, context):
        self.calls.append(self.name)
        return StageResult(status=self.status, outputs=self.outputs, notes=self.notes)
```

```python
# tests/orchestrator/test_engine_linear.py
from pathlib import Path
import tempfile

from orchestrator.graph import GateResult, StageNode, Workflow
from orchestrator.engine import Engine
from orchestrator.store import RunStore
from tests.orchestrator.fakes import RecordingExecutor


def _engine(workflow):
    return Engine(workflow, RunStore(Path(tempfile.mkdtemp())))


def test_linear_workflow_runs_nodes_in_dependency_order():
    calls = []
    wf = Workflow("linear", [
        StageNode(id="a", executor=RecordingExecutor("a", calls)),
        StageNode(id="b", executor=RecordingExecutor("b", calls), depends_on=["a"]),
        StageNode(id="c", executor=RecordingExecutor("c", calls), depends_on=["b"]),
    ])
    state = _engine(wf).start("run-1")
    assert calls == ["a", "b", "c"]
    assert state.node_status == {"a": "passed", "b": "passed", "c": "passed"}
    assert state.finished_at != ""


def test_parallel_branches_both_run_before_sync_node():
    calls = []
    wf = Workflow("fanout", [
        StageNode(id="a", executor=RecordingExecutor("a", calls)),
        StageNode(id="b1", executor=RecordingExecutor("b1", calls), depends_on=["a"]),
        StageNode(id="b2", executor=RecordingExecutor("b2", calls), depends_on=["a"]),
        StageNode(id="sync", executor=RecordingExecutor("sync", calls), depends_on=["b1", "b2"]),
    ])
    state = _engine(wf).start("run-1")
    assert calls[0] == "a"
    assert set(calls[1:3]) == {"b1", "b2"}
    assert calls[3] == "sync"
    assert state.node_status["sync"] == "passed"


def test_entry_gate_blocks_node():
    calls = []
    wf = Workflow("blocked", [
        StageNode(
            id="a",
            executor=RecordingExecutor("a", calls),
            entry_gate=lambda ctx: GateResult("blocked", "not allowed"),
        ),
    ])
    state = _engine(wf).start("run-1")
    assert calls == []
    assert state.node_status["a"] == "blocked"


def test_context_records_input_versions():
    wf = Workflow("linear", [
        StageNode(id="a", executor=RecordingExecutor("a", [], outputs={"x": 1})),
        StageNode(id="b", executor=RecordingExecutor("b", [], outputs={"y": 2}), depends_on=["a"]),
    ])
    state = _engine(wf).start("run-1")
    assert state.context.latest("b").input_versions == {"a": 1}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/orchestrator/test_engine_linear.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'orchestrator.engine'`

- [ ] **Step 3: Implement**

```python
# src/orchestrator/engine.py
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor, as_completed

from orchestrator.context import ContextEntry, RunContext
from orchestrator.events import Event, EventLog, now_iso
from orchestrator.executor import StageResult
from orchestrator.graph import Workflow
from orchestrator.store import RunState, RunStore


class Engine:
    def __init__(self, workflow: Workflow, store: RunStore):
        self.workflow = workflow
        self.store = store

    def start(self, run_id: str) -> RunState:
        state = RunState(
            run_id=run_id,
            workflow_name=self.workflow.name,
            node_status={node_id: "pending" for node_id in self.workflow.nodes},
            node_attempts={node_id: 0 for node_id in self.workflow.nodes},
            context=RunContext(run_id),
            started_at=now_iso(),
        )
        self.store.save(state)
        return self._run_loop(state)

    def _events(self, run_id: str) -> EventLog:
        return EventLog(self.store.events_path(run_id))

    def _transition(self, state: RunState, node_id: str, from_state: str, to_state: str, reason: str = "") -> None:
        state.node_status[node_id] = to_state
        self._events(state.run_id).append(Event(
            run_id=state.run_id, node_id=node_id, from_state=from_state,
            to_state=to_state, timestamp=now_iso(), attempt=state.node_attempts.get(node_id, 1), reason=reason,
        ))

    def _ready_nodes(self, state: RunState) -> list[str]:
        ready = []
        for node_id, node in self.workflow.nodes.items():
            if state.node_status[node_id] != "pending":
                continue
            if all(state.node_status.get(dep) == "passed" for dep in node.depends_on):
                ready.append(node_id)
        return ready

    def _run_loop(self, state: RunState) -> RunState:
        while True:
            ready = self._ready_nodes(state)
            if not ready:
                break
            with ThreadPoolExecutor(max_workers=max(1, len(ready))) as pool:
                futures = [pool.submit(self._execute_node, state, node_id) for node_id in ready]
                for future in as_completed(futures):
                    future.result()
            self.store.save(state)
        if not any(status == "pending" for status in state.node_status.values()):
            state.finished_at = now_iso()
            self.store.save(state)
        return state

    def _execute_node(self, state: RunState, node_id: str) -> None:
        node = self.workflow.nodes[node_id]
        self._transition(state, node_id, "pending", "running")

        entry_result = node.entry_gate(state.context)
        if entry_result.outcome != "ok":
            self._transition(state, node_id, "running", "blocked", reason=entry_result.reason)
            return

        state.node_attempts[node_id] = 1
        result = node.executor.run(state.context)
        exit_result = node.exit_gate(result, state.context)

        if exit_result.outcome == "pass":
            self._record_context(state, node, result)
            self._transition(state, node_id, "running", "passed")
        else:
            self._transition(state, node_id, "running", "failed", reason=exit_result.reason)

    def _record_context(self, state: RunState, node, result: StageResult) -> None:
        version = state.context.next_version(node.id)
        entry = ContextEntry(
            stage_id=node.id,
            version=version,
            outputs=result.outputs,
            produced_by=type(node.executor).__name__,
            rationale=result.notes,
            timestamp=now_iso(),
            input_versions={
                dep: state.context.latest(dep).version
                for dep in node.depends_on if state.context.latest(dep)
            },
        )
        state.context.append(entry)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/orchestrator/test_engine_linear.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/engine.py tests/orchestrator/fakes.py tests/orchestrator/test_engine_linear.py
git commit -m "feat: orchestrator engine — linear execution, entry/exit gates, parallel sync points"
```

---

## Task 16: Engine — Retry, Fallback, Rollback, Failure Propagation

**Files:**
- Modify: `src/orchestrator/engine.py` (full-file replacement, shown below)
- Modify: `tests/orchestrator/fakes.py` (append `FlakyExecutor`, do not remove `RecordingExecutor`)
- Test: `tests/orchestrator/test_retry_fallback.py`
- Test: `tests/orchestrator/test_rollback.py`

**Interfaces:**
- Consumes: `RetryPolicy` (Task 13, already a `StageNode` field since Task 13).
- Produces: `Engine._run_with_retry` (internal, exercised via `start()`); downstream nodes of a failed/rolled-back node now transition to `"blocked"` instead of staying `"pending"` forever.
- `FlakyExecutor(fail_times, calls, name="flaky")` — fails its first `fail_times` calls, then passes; appended to `tests/orchestrator/fakes.py`.

- [ ] **Step 1: Write the failing tests**

```python
# append to tests/orchestrator/fakes.py — keep RecordingExecutor above this
class FlakyExecutor(StageExecutor):
    """Fails its first `fail_times` runs, then passes. Tracks attempt count via `calls`."""

    def __init__(self, fail_times, calls, name="flaky"):
        self.fail_times = fail_times
        self.calls = calls
        self.name = name
        self._attempts = 0

    def run(self, context):
        self._attempts += 1
        self.calls.append(self.name)
        if self._attempts <= self.fail_times:
            return StageResult(status=StageStatus.FAILED, notes=f"attempt {self._attempts} failed")
        return StageResult(status=StageStatus.PASSED)
```

```python
# tests/orchestrator/test_retry_fallback.py
from pathlib import Path
import tempfile

from orchestrator.graph import RetryPolicy, StageNode, Workflow
from orchestrator.engine import Engine
from orchestrator.store import RunStore
from tests.orchestrator.fakes import FlakyExecutor, RecordingExecutor


def _engine(workflow):
    return Engine(workflow, RunStore(Path(tempfile.mkdtemp())))


def test_retry_succeeds_within_max_attempts():
    calls = []
    wf = Workflow("retry", [
        StageNode(
            id="a", executor=FlakyExecutor(fail_times=2, calls=calls),
            retry_policy=RetryPolicy(max_attempts=3, backoff_seconds=0),
        ),
    ])
    state = _engine(wf).start("run-1")
    assert state.node_status["a"] == "passed"
    assert len(calls) == 3


def test_retry_exhausted_without_fallback_fails():
    calls = []
    wf = Workflow("retry", [
        StageNode(
            id="a", executor=FlakyExecutor(fail_times=5, calls=calls),
            retry_policy=RetryPolicy(max_attempts=2, backoff_seconds=0),
        ),
    ])
    state = _engine(wf).start("run-1")
    assert state.node_status["a"] == "failed"
    assert len(calls) == 2


def test_fallback_executor_runs_after_retries_exhausted():
    calls, fallback_calls = [], []
    wf = Workflow("retry", [
        StageNode(
            id="a", executor=FlakyExecutor(fail_times=5, calls=calls),
            retry_policy=RetryPolicy(max_attempts=1, backoff_seconds=0),
            fallback_executor=RecordingExecutor("fallback", fallback_calls),
        ),
    ])
    state = _engine(wf).start("run-1")
    assert state.node_status["a"] == "passed"
    assert fallback_calls == ["fallback"]
    assert state.context.latest("a").produced_by == "RecordingExecutor"
```

```python
# tests/orchestrator/test_rollback.py
from pathlib import Path
import tempfile

from orchestrator.executor import StageStatus
from orchestrator.graph import StageNode, Workflow
from orchestrator.engine import Engine
from orchestrator.store import RunStore
from tests.orchestrator.fakes import RecordingExecutor


def _engine(workflow):
    return Engine(workflow, RunStore(Path(tempfile.mkdtemp())))


def test_rollback_hook_runs_on_final_failure():
    rollback_calls = []
    wf = Workflow("rollback", [
        StageNode(
            id="a",
            executor=RecordingExecutor("a", [], status=StageStatus.FAILED, notes="broke"),
            rollback=lambda ctx: rollback_calls.append("rolled_back"),
        ),
    ])
    state = _engine(wf).start("run-1")
    assert state.node_status["a"] == "rolled_back"
    assert rollback_calls == ["rolled_back"]


def test_failed_node_blocks_downstream():
    wf = Workflow("rollback", [
        StageNode(id="a", executor=RecordingExecutor("a", [], status=StageStatus.FAILED, notes="broke")),
        StageNode(id="b", executor=RecordingExecutor("b", []), depends_on=["a"]),
    ])
    state = _engine(wf).start("run-1")
    assert state.node_status["a"] == "failed"
    assert state.node_status["b"] == "blocked"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/orchestrator/test_retry_fallback.py tests/orchestrator/test_rollback.py -v`
Expected: FAIL — `AttributeError`/assertion failures (module exists from Task 15, behavior doesn't yet).

- [ ] **Step 3: Replace the full contents of `src/orchestrator/engine.py`**

```python
# src/orchestrator/engine.py
from __future__ import annotations
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from orchestrator.context import ContextEntry, RunContext
from orchestrator.events import Event, EventLog, now_iso
from orchestrator.executor import StageResult, StageStatus
from orchestrator.graph import Workflow
from orchestrator.store import RunState, RunStore


class Engine:
    def __init__(self, workflow: Workflow, store: RunStore):
        self.workflow = workflow
        self.store = store

    def start(self, run_id: str) -> RunState:
        state = RunState(
            run_id=run_id,
            workflow_name=self.workflow.name,
            node_status={node_id: "pending" for node_id in self.workflow.nodes},
            node_attempts={node_id: 0 for node_id in self.workflow.nodes},
            context=RunContext(run_id),
            started_at=now_iso(),
        )
        self.store.save(state)
        return self._run_loop(state)

    def _events(self, run_id: str) -> EventLog:
        return EventLog(self.store.events_path(run_id))

    def _transition(self, state: RunState, node_id: str, from_state: str, to_state: str, reason: str = "") -> None:
        state.node_status[node_id] = to_state
        self._events(state.run_id).append(Event(
            run_id=state.run_id, node_id=node_id, from_state=from_state,
            to_state=to_state, timestamp=now_iso(), attempt=state.node_attempts.get(node_id, 1), reason=reason,
        ))

    def _ready_nodes(self, state: RunState) -> list[str]:
        ready = []
        for node_id, node in self.workflow.nodes.items():
            if state.node_status[node_id] != "pending":
                continue
            if all(state.node_status.get(dep) == "passed" for dep in node.depends_on):
                ready.append(node_id)
        return ready

    def _run_loop(self, state: RunState) -> RunState:
        while True:
            ready = self._ready_nodes(state)
            if not ready:
                break
            with ThreadPoolExecutor(max_workers=max(1, len(ready))) as pool:
                futures = [pool.submit(self._execute_node, state, node_id) for node_id in ready]
                for future in as_completed(futures):
                    future.result()
            self.store.save(state)
        if not any(status == "pending" for status in state.node_status.values()):
            state.finished_at = now_iso()
            self.store.save(state)
        return state

    def _execute_node(self, state: RunState, node_id: str) -> None:
        node = self.workflow.nodes[node_id]
        self._transition(state, node_id, "pending", "running")

        entry_result = node.entry_gate(state.context)
        if entry_result.outcome != "ok":
            self._transition(state, node_id, "running", "blocked", reason=entry_result.reason)
            return

        result, used_fallback = self._run_with_retry(node, state)
        exit_result = node.exit_gate(result, state.context)

        if exit_result.outcome == "pass":
            self._record_context(state, node, result, used_fallback)
            self._transition(state, node_id, "running", "passed")
            return

        if node.rollback is not None:
            node.rollback(state.context)
            self._transition(state, node_id, "running", "rolled_back", reason=exit_result.reason)
        else:
            self._transition(state, node_id, "running", "failed", reason=exit_result.reason)
        self._block_downstream(state, node_id)

    def _run_with_retry(self, node, state: RunState) -> tuple[StageResult, bool]:
        policy = node.retry_policy
        last_result = None
        for attempt in range(1, policy.max_attempts + 1):
            state.node_attempts[node.id] = attempt
            last_result = node.executor.run(state.context)
            if last_result.status == StageStatus.PASSED:
                return last_result, False
            if attempt < policy.max_attempts:
                time.sleep(policy.backoff_seconds)
        if node.fallback_executor is not None:
            fallback_result = node.fallback_executor.run(state.context)
            return fallback_result, True
        return last_result, False

    def _record_context(self, state: RunState, node, result: StageResult, used_fallback: bool = False) -> None:
        version = state.context.next_version(node.id)
        producer = node.fallback_executor if used_fallback else node.executor
        entry = ContextEntry(
            stage_id=node.id,
            version=version,
            outputs=result.outputs,
            produced_by=type(producer).__name__,
            rationale=result.notes,
            timestamp=now_iso(),
            input_versions={
                dep: state.context.latest(dep).version
                for dep in node.depends_on if state.context.latest(dep)
            },
        )
        state.context.append(entry)

    def _block_downstream(self, state: RunState, node_id: str) -> None:
        for downstream_id in self.workflow.all_downstream(node_id):
            if state.node_status.get(downstream_id) == "pending":
                self._transition(state, downstream_id, "pending", "blocked", reason=f"upstream {node_id} did not pass")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/orchestrator/test_engine_linear.py tests/orchestrator/test_retry_fallback.py tests/orchestrator/test_rollback.py -v`
Expected: PASS (9 tests total; the Task 15 tests must still pass unchanged)

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/engine.py tests/orchestrator/fakes.py \
        tests/orchestrator/test_retry_fallback.py tests/orchestrator/test_rollback.py
git commit -m "feat: engine retry/backoff, fallback executors, rollback hooks, failure propagation"
```

---

## Task 17: Engine — Approval Checkpoints, Reject/Revise, Safe-Stop

**Files:**
- Modify: `src/orchestrator/engine.py` (full-file replacement, shown below)
- Test: `tests/orchestrator/test_approval.py`
- Test: `tests/orchestrator/test_safe_stop.py`

**Interfaces:**
- Consumes: `node.requires_approval` (Task 13, unused until now).
- Produces: `Engine.resume(run_id) -> RunState`, `Engine.stop(run_id) -> RunState`, `Engine.approve(run_id, node_id, note="") -> RunState`, `Engine.reject(run_id, node_id, note, revise_stage) -> RunState`. These four are what `cli.py` (Task 21) calls directly.

- [ ] **Step 1: Write the failing tests**

```python
# tests/orchestrator/test_approval.py
from pathlib import Path
import tempfile

import pytest
from orchestrator.graph import StageNode, Workflow
from orchestrator.engine import Engine
from orchestrator.store import RunStore
from tests.orchestrator.fakes import RecordingExecutor


def _engine(workflow):
    return Engine(workflow, RunStore(Path(tempfile.mkdtemp())))


def test_node_with_requires_approval_pauses_run():
    wf = Workflow("gated", [
        StageNode(id="a", executor=RecordingExecutor("a", []), requires_approval=True),
        StageNode(id="b", executor=RecordingExecutor("b", []), depends_on=["a"]),
    ])
    state = _engine(wf).start("run-1")
    assert state.node_status["a"] == "awaiting_approval"
    assert state.node_status["b"] == "pending"


def test_approve_resumes_downstream_nodes():
    wf = Workflow("gated", [
        StageNode(id="a", executor=RecordingExecutor("a", []), requires_approval=True),
        StageNode(id="b", executor=RecordingExecutor("b", []), depends_on=["a"]),
    ])
    engine = _engine(wf)
    engine.start("run-1")
    state = engine.approve("run-1", "a", note="looks good")
    assert state.node_status["a"] == "passed"
    assert state.node_status["b"] == "passed"


def test_approve_raises_if_node_not_awaiting_approval():
    wf = Workflow("gated", [StageNode(id="a", executor=RecordingExecutor("a", []))])
    engine = _engine(wf)
    engine.start("run-1")
    with pytest.raises(ValueError):
        engine.approve("run-1", "a", note="x")


def test_reject_reruns_revise_stage_then_regates_rejected_node():
    wf = Workflow("gated", [
        StageNode(id="requirements", executor=RecordingExecutor("requirements", [])),
        StageNode(
            id="design", executor=RecordingExecutor("design", []),
            depends_on=["requirements"], requires_approval=True,
        ),
    ])
    engine = _engine(wf)
    engine.start("run-1")
    state = engine.reject("run-1", "design", note="needs rework", revise_stage="requirements")
    assert state.node_status["requirements"] == "passed"
    assert state.node_status["design"] == "awaiting_approval"
    assert state.context.latest("requirements").version == 2


def test_reject_self_revise_reruns_same_node():
    wf = Workflow("gated", [
        StageNode(id="requirements", executor=RecordingExecutor("requirements", []), requires_approval=True),
    ])
    engine = _engine(wf)
    engine.start("run-1")
    state = engine.reject("run-1", "requirements", note="too vague", revise_stage="requirements")
    assert state.node_status["requirements"] == "awaiting_approval"
```

```python
# tests/orchestrator/test_safe_stop.py
from pathlib import Path
import tempfile

from orchestrator.graph import StageNode, Workflow
from orchestrator.engine import Engine
from orchestrator.store import RunStore
from tests.orchestrator.fakes import RecordingExecutor


def test_stop_halts_before_pending_nodes_run_and_resume_continues():
    calls = []
    wf = Workflow("stoppable", [
        StageNode(id="a", executor=RecordingExecutor("a", calls), requires_approval=True),
        StageNode(id="b", executor=RecordingExecutor("b", calls), depends_on=["a"]),
    ])
    engine = Engine(wf, RunStore(Path(tempfile.mkdtemp())))
    engine.start("run-1")
    engine.stop("run-1")
    state = engine.approve("run-1", "a", note="ok")
    assert state.node_status["b"] == "stopped"
    assert calls == ["a"]

    resumed = engine.resume("run-1")
    assert resumed.node_status["b"] == "passed"
    assert calls == ["a", "b"]
    assert resumed.safe_stop_requested is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/orchestrator/test_approval.py tests/orchestrator/test_safe_stop.py -v`
Expected: FAIL — `AttributeError: 'Engine' object has no attribute 'approve'` (etc.)

- [ ] **Step 3: Replace the full contents of `src/orchestrator/engine.py`**

```python
# src/orchestrator/engine.py
from __future__ import annotations
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from orchestrator.context import ContextEntry, RunContext
from orchestrator.events import Event, EventLog, now_iso
from orchestrator.executor import StageResult, StageStatus
from orchestrator.graph import Workflow
from orchestrator.store import RunState, RunStore


class Engine:
    def __init__(self, workflow: Workflow, store: RunStore):
        self.workflow = workflow
        self.store = store

    def start(self, run_id: str) -> RunState:
        state = RunState(
            run_id=run_id,
            workflow_name=self.workflow.name,
            node_status={node_id: "pending" for node_id in self.workflow.nodes},
            node_attempts={node_id: 0 for node_id in self.workflow.nodes},
            context=RunContext(run_id),
            started_at=now_iso(),
        )
        self.store.save(state)
        return self._run_loop(state)

    def resume(self, run_id: str) -> RunState:
        state = self.store.load(run_id)
        state.safe_stop_requested = False
        for node_id, status in list(state.node_status.items()):
            if status == "stopped":
                self._transition(state, node_id, "stopped", "pending", reason="resumed")
        self.store.save(state)
        return self._run_loop(state)

    def stop(self, run_id: str) -> RunState:
        state = self.store.load(run_id)
        state.safe_stop_requested = True
        self.store.save(state)
        return state

    def approve(self, run_id: str, node_id: str, note: str = "") -> RunState:
        state = self.store.load(run_id)
        if state.node_status.get(node_id) != "awaiting_approval":
            raise ValueError(f"{node_id} is not awaiting approval")
        self._transition(state, node_id, "awaiting_approval", "passed", reason=f"approved: {note}")
        self.store.save(state)
        return self._run_loop(state)

    def reject(self, run_id: str, node_id: str, note: str, revise_stage: str) -> RunState:
        state = self.store.load(run_id)
        if state.node_status.get(node_id) != "awaiting_approval":
            raise ValueError(f"{node_id} is not awaiting approval")
        self._transition(state, node_id, "awaiting_approval", "pending", reason=f"rejected: {note}")
        if revise_stage != node_id:
            current = state.node_status.get(revise_stage, "pending")
            self._transition(state, revise_stage, current, "pending", reason=f"revise requested: {note}")
        self.store.save(state)
        return self._run_loop(state)

    def _events(self, run_id: str) -> EventLog:
        return EventLog(self.store.events_path(run_id))

    def _transition(self, state: RunState, node_id: str, from_state: str, to_state: str, reason: str = "") -> None:
        state.node_status[node_id] = to_state
        self._events(state.run_id).append(Event(
            run_id=state.run_id, node_id=node_id, from_state=from_state,
            to_state=to_state, timestamp=now_iso(), attempt=state.node_attempts.get(node_id, 1), reason=reason,
        ))

    def _ready_nodes(self, state: RunState) -> list[str]:
        ready = []
        for node_id, node in self.workflow.nodes.items():
            if state.node_status[node_id] != "pending":
                continue
            if all(state.node_status.get(dep) == "passed" for dep in node.depends_on):
                ready.append(node_id)
        return ready

    def _run_loop(self, state: RunState) -> RunState:
        while True:
            if state.safe_stop_requested:
                for node_id, status in list(state.node_status.items()):
                    if status == "pending":
                        self._transition(state, node_id, "pending", "stopped", reason="safe-stop requested")
                self.store.save(state)
                break
            ready = self._ready_nodes(state)
            if not ready:
                break
            with ThreadPoolExecutor(max_workers=max(1, len(ready))) as pool:
                futures = [pool.submit(self._execute_node, state, node_id) for node_id in ready]
                for future in as_completed(futures):
                    future.result()
            self.store.save(state)
            if any(state.node_status[n] == "awaiting_approval" for n in ready):
                break
        if not any(status == "pending" for status in state.node_status.values()):
            state.finished_at = now_iso()
            self.store.save(state)
        return state

    def _execute_node(self, state: RunState, node_id: str) -> None:
        node = self.workflow.nodes[node_id]
        self._transition(state, node_id, "pending", "running")

        entry_result = node.entry_gate(state.context)
        if entry_result.outcome != "ok":
            self._transition(state, node_id, "running", "blocked", reason=entry_result.reason)
            return

        result, used_fallback = self._run_with_retry(node, state)
        exit_result = node.exit_gate(result, state.context)

        if exit_result.outcome == "needs_approval":
            self._record_context(state, node, result, used_fallback)
            self._transition(state, node_id, "running", "awaiting_approval", reason=exit_result.reason)
            return

        if exit_result.outcome == "pass":
            self._record_context(state, node, result, used_fallback)
            if node.requires_approval:
                self._transition(state, node_id, "running", "awaiting_approval", reason="human approval required")
            else:
                self._transition(state, node_id, "running", "passed")
            return

        if node.rollback is not None:
            node.rollback(state.context)
            self._transition(state, node_id, "running", "rolled_back", reason=exit_result.reason)
        else:
            self._transition(state, node_id, "running", "failed", reason=exit_result.reason)
        self._block_downstream(state, node_id)

    def _run_with_retry(self, node, state: RunState) -> tuple[StageResult, bool]:
        policy = node.retry_policy
        last_result = None
        for attempt in range(1, policy.max_attempts + 1):
            state.node_attempts[node.id] = attempt
            last_result = node.executor.run(state.context)
            if last_result.status == StageStatus.PASSED:
                return last_result, False
            if attempt < policy.max_attempts:
                time.sleep(policy.backoff_seconds)
        if node.fallback_executor is not None:
            fallback_result = node.fallback_executor.run(state.context)
            return fallback_result, True
        return last_result, False

    def _record_context(self, state: RunState, node, result: StageResult, used_fallback: bool = False) -> None:
        version = state.context.next_version(node.id)
        producer = node.fallback_executor if used_fallback else node.executor
        entry = ContextEntry(
            stage_id=node.id,
            version=version,
            outputs=result.outputs,
            produced_by=type(producer).__name__,
            rationale=result.notes,
            timestamp=now_iso(),
            input_versions={
                dep: state.context.latest(dep).version
                for dep in node.depends_on if state.context.latest(dep)
            },
        )
        state.context.append(entry)

    def _block_downstream(self, state: RunState, node_id: str) -> None:
        for downstream_id in self.workflow.all_downstream(node_id):
            if state.node_status.get(downstream_id) == "pending":
                self._transition(state, downstream_id, "pending", "blocked", reason=f"upstream {node_id} did not pass")
```

- [ ] **Step 4: Run the full engine test suite**

Run: `pytest tests/orchestrator -v`
Expected: PASS — every test from Tasks 12-17 (context, graph, events, store, engine linear/retry/rollback/approval/safe-stop) passes together.

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/engine.py tests/orchestrator/test_approval.py tests/orchestrator/test_safe_stop.py
git commit -m "feat: engine approval checkpoints, reject/revise flow, safe-stop"
```

---

## Task 18: Dynamic Re-Planning on Upstream Version Change

**Files:**
- Create: `src/orchestrator/replan.py`
- Modify: `src/orchestrator/engine.py` (full-file replacement, shown below)
- Test: `tests/orchestrator/test_replan.py`

**Interfaces:**
- Consumes: `Workflow.dependents_of` (Task 13); `RunContext.latest` (Task 12); `RunState` (Task 14).
- Produces: `stale_downstream(workflow, state) -> set[str]`; `invalidate_stale(workflow, state, transition: Callable) -> set[str]` — `transition` is injected as `Engine._transition` so replan logs through the same event path; tested here with a fake transition function so `replan.py` never imports `Engine`, per spec §3 (engine internals stay independently testable).

- [ ] **Step 1: Write the failing tests**

```python
# tests/orchestrator/test_replan.py
from orchestrator.context import ContextEntry, RunContext
from orchestrator.graph import StageNode, Workflow
from orchestrator.store import RunState
from orchestrator.replan import stale_downstream, invalidate_stale
from tests.orchestrator.fakes import RecordingExecutor


def _workflow():
    return Workflow("replan", [
        StageNode(id="requirements", executor=RecordingExecutor("requirements", [])),
        StageNode(id="design", executor=RecordingExecutor("design", []), depends_on=["requirements"]),
        StageNode(id="implementation", executor=RecordingExecutor("implementation", []), depends_on=["design"]),
    ])


def _entry(stage_id, version, input_versions=None):
    return ContextEntry(
        stage_id=stage_id, version=version, outputs={}, produced_by="x",
        rationale="", timestamp="t", input_versions=input_versions or {},
    )


def test_stale_downstream_empty_when_versions_match():
    ctx = RunContext("run-1")
    ctx.append(_entry("requirements", 1))
    ctx.append(_entry("design", 1, {"requirements": 1}))
    state = RunState(
        run_id="run-1", workflow_name="replan", context=ctx,
        node_status={"requirements": "passed", "design": "passed", "implementation": "pending"},
    )
    assert stale_downstream(_workflow(), state) == set()


def test_stale_downstream_detects_version_drift_and_propagates():
    ctx = RunContext("run-1")
    ctx.append(_entry("requirements", 1))
    ctx.append(_entry("design", 1, {"requirements": 1}))
    ctx.append(_entry("implementation", 1, {"design": 1}))
    ctx.append(_entry("requirements", 2))  # requirements re-run, new version
    state = RunState(
        run_id="run-1", workflow_name="replan", context=ctx,
        node_status={"requirements": "passed", "design": "passed", "implementation": "passed"},
    )
    assert stale_downstream(_workflow(), state) == {"design", "implementation"}


def test_invalidate_stale_requeues_matching_nodes():
    ctx = RunContext("run-1")
    ctx.append(_entry("requirements", 1))
    ctx.append(_entry("design", 1, {"requirements": 1}))
    ctx.append(_entry("requirements", 2))
    state = RunState(
        run_id="run-1", workflow_name="replan", context=ctx,
        node_status={"requirements": "passed", "design": "passed", "implementation": "pending"},
    )
    events = []

    def fake_transition(state, node_id, from_state, to_state, reason=""):
        state.node_status[node_id] = to_state
        events.append((node_id, from_state, to_state))

    stale = invalidate_stale(_workflow(), state, fake_transition)
    assert stale == {"design"}
    assert state.node_status["design"] == "pending"
    assert ("design", "passed", "invalidated") in events
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/orchestrator/test_replan.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'orchestrator.replan'`

- [ ] **Step 3: Implement `replan.py`, then wire it into `engine.py`**

```python
# src/orchestrator/replan.py
from __future__ import annotations
from typing import Callable

from orchestrator.graph import Workflow
from orchestrator.store import RunState


def stale_downstream(workflow: Workflow, state: RunState) -> set[str]:
    stale: set[str] = set()
    for node_id in workflow.nodes:
        entry = state.context.latest(node_id)
        if entry is None:
            continue
        for dep_id, used_version in entry.input_versions.items():
            latest_dep = state.context.latest(dep_id)
            if latest_dep is not None and latest_dep.version != used_version:
                stale.add(node_id)
    frontier = list(stale)
    while frontier:
        current = frontier.pop()
        for dependent_id in workflow.dependents_of(current):
            if dependent_id not in stale:
                stale.add(dependent_id)
                frontier.append(dependent_id)
    return stale


def invalidate_stale(workflow: Workflow, state: RunState, transition: Callable) -> set[str]:
    stale = stale_downstream(workflow, state)
    for node_id in stale:
        if state.node_status.get(node_id) in ("passed", "awaiting_approval"):
            current = state.node_status[node_id]
            transition(state, node_id, current, "invalidated", reason="stale input version")
            transition(state, node_id, "invalidated", "pending", reason="re-queued after replan")
    return stale
```

In `src/orchestrator/engine.py`, add `from orchestrator.replan import invalidate_stale` to the imports, and call `invalidate_stale(self.workflow, state, self._transition)` immediately after each of the two `self._record_context(...)` calls inside `_execute_node` (in both the `needs_approval` branch and the `pass` branch), before the subsequent status transition. Full file:

```python
# src/orchestrator/engine.py
from __future__ import annotations
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from orchestrator.context import ContextEntry, RunContext
from orchestrator.events import Event, EventLog, now_iso
from orchestrator.executor import StageResult, StageStatus
from orchestrator.graph import Workflow
from orchestrator.replan import invalidate_stale
from orchestrator.store import RunState, RunStore


class Engine:
    def __init__(self, workflow: Workflow, store: RunStore):
        self.workflow = workflow
        self.store = store

    def start(self, run_id: str) -> RunState:
        state = RunState(
            run_id=run_id,
            workflow_name=self.workflow.name,
            node_status={node_id: "pending" for node_id in self.workflow.nodes},
            node_attempts={node_id: 0 for node_id in self.workflow.nodes},
            context=RunContext(run_id),
            started_at=now_iso(),
        )
        self.store.save(state)
        return self._run_loop(state)

    def resume(self, run_id: str) -> RunState:
        state = self.store.load(run_id)
        state.safe_stop_requested = False
        for node_id, status in list(state.node_status.items()):
            if status == "stopped":
                self._transition(state, node_id, "stopped", "pending", reason="resumed")
        self.store.save(state)
        return self._run_loop(state)

    def stop(self, run_id: str) -> RunState:
        state = self.store.load(run_id)
        state.safe_stop_requested = True
        self.store.save(state)
        return state

    def approve(self, run_id: str, node_id: str, note: str = "") -> RunState:
        state = self.store.load(run_id)
        if state.node_status.get(node_id) != "awaiting_approval":
            raise ValueError(f"{node_id} is not awaiting approval")
        self._transition(state, node_id, "awaiting_approval", "passed", reason=f"approved: {note}")
        self.store.save(state)
        return self._run_loop(state)

    def reject(self, run_id: str, node_id: str, note: str, revise_stage: str) -> RunState:
        state = self.store.load(run_id)
        if state.node_status.get(node_id) != "awaiting_approval":
            raise ValueError(f"{node_id} is not awaiting approval")
        self._transition(state, node_id, "awaiting_approval", "pending", reason=f"rejected: {note}")
        if revise_stage != node_id:
            current = state.node_status.get(revise_stage, "pending")
            self._transition(state, revise_stage, current, "pending", reason=f"revise requested: {note}")
        self.store.save(state)
        return self._run_loop(state)

    def _events(self, run_id: str) -> EventLog:
        return EventLog(self.store.events_path(run_id))

    def _transition(self, state: RunState, node_id: str, from_state: str, to_state: str, reason: str = "") -> None:
        state.node_status[node_id] = to_state
        self._events(state.run_id).append(Event(
            run_id=state.run_id, node_id=node_id, from_state=from_state,
            to_state=to_state, timestamp=now_iso(), attempt=state.node_attempts.get(node_id, 1), reason=reason,
        ))

    def _ready_nodes(self, state: RunState) -> list[str]:
        ready = []
        for node_id, node in self.workflow.nodes.items():
            if state.node_status[node_id] != "pending":
                continue
            if all(state.node_status.get(dep) == "passed" for dep in node.depends_on):
                ready.append(node_id)
        return ready

    def _run_loop(self, state: RunState) -> RunState:
        while True:
            if state.safe_stop_requested:
                for node_id, status in list(state.node_status.items()):
                    if status == "pending":
                        self._transition(state, node_id, "pending", "stopped", reason="safe-stop requested")
                self.store.save(state)
                break
            ready = self._ready_nodes(state)
            if not ready:
                break
            with ThreadPoolExecutor(max_workers=max(1, len(ready))) as pool:
                futures = [pool.submit(self._execute_node, state, node_id) for node_id in ready]
                for future in as_completed(futures):
                    future.result()
            self.store.save(state)
            if any(state.node_status[n] == "awaiting_approval" for n in ready):
                break
        if not any(status == "pending" for status in state.node_status.values()):
            state.finished_at = now_iso()
            self.store.save(state)
        return state

    def _execute_node(self, state: RunState, node_id: str) -> None:
        node = self.workflow.nodes[node_id]
        self._transition(state, node_id, "pending", "running")

        entry_result = node.entry_gate(state.context)
        if entry_result.outcome != "ok":
            self._transition(state, node_id, "running", "blocked", reason=entry_result.reason)
            return

        result, used_fallback = self._run_with_retry(node, state)
        exit_result = node.exit_gate(result, state.context)

        if exit_result.outcome == "needs_approval":
            self._record_context(state, node, result, used_fallback)
            invalidate_stale(self.workflow, state, self._transition)
            self._transition(state, node_id, "running", "awaiting_approval", reason=exit_result.reason)
            return

        if exit_result.outcome == "pass":
            self._record_context(state, node, result, used_fallback)
            invalidate_stale(self.workflow, state, self._transition)
            if node.requires_approval:
                self._transition(state, node_id, "running", "awaiting_approval", reason="human approval required")
            else:
                self._transition(state, node_id, "running", "passed")
            return

        if node.rollback is not None:
            node.rollback(state.context)
            self._transition(state, node_id, "running", "rolled_back", reason=exit_result.reason)
        else:
            self._transition(state, node_id, "running", "failed", reason=exit_result.reason)
        self._block_downstream(state, node_id)

    def _run_with_retry(self, node, state: RunState) -> tuple[StageResult, bool]:
        policy = node.retry_policy
        last_result = None
        for attempt in range(1, policy.max_attempts + 1):
            state.node_attempts[node.id] = attempt
            last_result = node.executor.run(state.context)
            if last_result.status == StageStatus.PASSED:
                return last_result, False
            if attempt < policy.max_attempts:
                time.sleep(policy.backoff_seconds)
        if node.fallback_executor is not None:
            fallback_result = node.fallback_executor.run(state.context)
            return fallback_result, True
        return last_result, False

    def _record_context(self, state: RunState, node, result: StageResult, used_fallback: bool = False) -> None:
        version = state.context.next_version(node.id)
        producer = node.fallback_executor if used_fallback else node.executor
        entry = ContextEntry(
            stage_id=node.id,
            version=version,
            outputs=result.outputs,
            produced_by=type(producer).__name__,
            rationale=result.notes,
            timestamp=now_iso(),
            input_versions={
                dep: state.context.latest(dep).version
                for dep in node.depends_on if state.context.latest(dep)
            },
        )
        state.context.append(entry)

    def _block_downstream(self, state: RunState, node_id: str) -> None:
        for downstream_id in self.workflow.all_downstream(node_id):
            if state.node_status.get(downstream_id) == "pending":
                self._transition(state, downstream_id, "pending", "blocked", reason=f"upstream {node_id} did not pass")
```

- [ ] **Step 4: Run the full orchestrator test suite**

Run: `pytest tests/orchestrator -v`
Expected: PASS — replan tests plus every test from Tasks 12-17 (invalidate_stale is a no-op unless a node is re-run with a version bump, so prior behavior is unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/replan.py src/orchestrator/engine.py tests/orchestrator/test_replan.py
git commit -m "feat: version-based re-planning — invalidate and re-queue stale downstream nodes"
```

---

## Task 19: Policy Guardrails

**Files:**
- Create: `src/orchestrator/policy.py`
- Create: `src/orchestrator/guardrails.py`
- Test: `tests/orchestrator/test_guardrails.py`

**Interfaces:**
- Consumes: `StageResult`, `RunContext`, `GateResult` (Task 12/13).
- Produces: `combine_guardrails(*rules) -> GuardrailRule`; `no_secrets_in_diff`, `destructive_migration_requires_approval`, `new_dependency_requires_approval` (all `(StageResult, RunContext) -> GateResult`); `test_coverage_threshold(min_pass_rate: float = 1.0) -> GuardrailRule`. Used as `exit_gate` (or composed into one via `combine_guardrails`) on `release_readiness`-style nodes in every scenario (Tasks 22-24).

- [ ] **Step 1: Write the failing test**

```python
# tests/orchestrator/test_guardrails.py
from orchestrator.executor import StageResult, StageStatus
from orchestrator.policy import combine_guardrails
from orchestrator.guardrails import (
    no_secrets_in_diff,
    destructive_migration_requires_approval,
    new_dependency_requires_approval,
    test_coverage_threshold,
)


def _result(**outputs):
    return StageResult(status=StageStatus.PASSED, outputs=outputs)


def test_no_secrets_in_diff_passes_clean_diff():
    assert no_secrets_in_diff(_result(diff_preview="def foo(): pass"), None).outcome == "pass"


def test_no_secrets_in_diff_fails_on_detected_marker():
    outcome = no_secrets_in_diff(_result(diff_preview="aws_secret_access_key = 'x'"), None)
    assert outcome.outcome == "fail"


def test_destructive_migration_requires_approval():
    outcome = destructive_migration_requires_approval(_result(schema_change="destructive"), None)
    assert outcome.outcome == "needs_approval"


def test_additive_migration_passes():
    outcome = destructive_migration_requires_approval(_result(schema_change="additive"), None)
    assert outcome.outcome == "pass"


def test_new_dependency_requires_approval():
    outcome = new_dependency_requires_approval(_result(new_dependencies=["requests"]), None)
    assert outcome.outcome == "needs_approval"


def test_coverage_threshold_fails_below_minimum():
    rule = test_coverage_threshold(min_pass_rate=1.0)
    assert rule(_result(tests_total=10, tests_passed=8), None).outcome == "fail"


def test_coverage_threshold_passes_at_minimum():
    rule = test_coverage_threshold(min_pass_rate=1.0)
    assert rule(_result(tests_total=10, tests_passed=10), None).outcome == "pass"


def test_combine_guardrails_returns_first_non_pass():
    combined = combine_guardrails(no_secrets_in_diff, test_coverage_threshold(1.0))
    outcome = combined(_result(diff_preview="aws_secret_access_key=x", tests_total=1, tests_passed=1), None)
    assert outcome.outcome == "fail"


def test_combine_guardrails_passes_when_all_pass():
    combined = combine_guardrails(no_secrets_in_diff, test_coverage_threshold(1.0))
    outcome = combined(_result(diff_preview="clean", tests_total=1, tests_passed=1), None)
    assert outcome.outcome == "pass"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/orchestrator/test_guardrails.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement**

```python
# src/orchestrator/policy.py
from __future__ import annotations
from typing import Callable

from orchestrator.context import RunContext
from orchestrator.executor import StageResult
from orchestrator.graph import GateResult

GuardrailRule = Callable[[StageResult, RunContext], GateResult]


def combine_guardrails(*rules: GuardrailRule) -> GuardrailRule:
    def combined(result: StageResult, context: RunContext) -> GateResult:
        for rule in rules:
            outcome = rule(result, context)
            if outcome.outcome != "pass":
                return outcome
        return GateResult("pass")
    return combined
```

```python
# src/orchestrator/guardrails.py
from __future__ import annotations
from orchestrator.context import RunContext
from orchestrator.executor import StageResult
from orchestrator.graph import GateResult

_BANNED_SECRET_MARKERS = ["-----BEGIN PRIVATE KEY", "aws_secret_access_key", "api_key=sk-"]


def no_secrets_in_diff(result: StageResult, context: RunContext) -> GateResult:
    diff_text = result.outputs.get("diff_preview", "")
    for marker in _BANNED_SECRET_MARKERS:
        if marker.lower() in diff_text.lower():
            return GateResult("fail", f"potential secret detected: {marker}")
    return GateResult("pass")


def destructive_migration_requires_approval(result: StageResult, context: RunContext) -> GateResult:
    if result.outputs.get("schema_change") == "destructive":
        return GateResult("needs_approval", "destructive schema change requires human approval")
    return GateResult("pass")


def new_dependency_requires_approval(result: StageResult, context: RunContext) -> GateResult:
    new_deps = result.outputs.get("new_dependencies")
    if new_deps:
        return GateResult("needs_approval", f"new dependencies added: {new_deps}")
    return GateResult("pass")


def test_coverage_threshold(min_pass_rate: float = 1.0):
    def rule(result: StageResult, context: RunContext) -> GateResult:
        total = result.outputs.get("tests_total", 0)
        passed = result.outputs.get("tests_passed", 0)
        if total == 0:
            return GateResult("pass")
        rate = passed / total
        if rate < min_pass_rate:
            return GateResult("fail", f"test pass rate {rate:.0%} below required {min_pass_rate:.0%}")
        return GateResult("pass")
    return rule
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/orchestrator/test_guardrails.py -v`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/policy.py src/orchestrator/guardrails.py tests/orchestrator/test_guardrails.py
git commit -m "feat: policy guardrails — secrets, destructive migrations, new deps, coverage threshold"
```

---

## Task 20: Reliability Metrics

**Files:**
- Create: `src/orchestrator/metrics.py`
- Test: `tests/orchestrator/test_metrics.py`

**Interfaces:**
- Consumes: `Event` (Task 14).
- Produces: `class RunMetrics` (`run_id, total_nodes, passed_nodes, retried_nodes, rolled_back_nodes, success_rate, retry_frequency, rollback_frequency, mttr_seconds, latency_seconds`); `compute_run_metrics(run_id, events) -> RunMetrics`; `aggregate_metrics(all_events: dict[str, list[Event]]) -> dict`. Used by `cli.py`'s `metrics` command (Task 21).

- [ ] **Step 1: Write the failing test**

```python
# tests/orchestrator/test_metrics.py
from orchestrator.events import Event
from orchestrator.metrics import aggregate_metrics, compute_run_metrics


def test_compute_run_metrics_basic_success_rate():
    events = [
        Event(run_id="r1", node_id="a", from_state="pending", to_state="running", timestamp="2026-01-01T00:00:00+00:00"),
        Event(run_id="r1", node_id="a", from_state="running", to_state="passed", timestamp="2026-01-01T00:00:01+00:00"),
        Event(run_id="r1", node_id="b", from_state="pending", to_state="running", timestamp="2026-01-01T00:00:01+00:00"),
        Event(run_id="r1", node_id="b", from_state="running", to_state="passed", timestamp="2026-01-01T00:00:02+00:00"),
    ]
    metrics = compute_run_metrics("r1", events)
    assert metrics.total_nodes == 2
    assert metrics.passed_nodes == 2
    assert metrics.success_rate == 1.0
    assert metrics.latency_seconds == 2.0


def test_compute_run_metrics_tracks_retry_and_rollback():
    events = [
        Event(run_id="r1", node_id="a", from_state="pending", to_state="running",
              timestamp="2026-01-01T00:00:00+00:00", attempt=1),
        Event(run_id="r1", node_id="a", from_state="running", to_state="failed",
              timestamp="2026-01-01T00:00:01+00:00", attempt=1),
        Event(run_id="r1", node_id="a", from_state="running", to_state="rolled_back",
              timestamp="2026-01-01T00:00:02+00:00", attempt=2),
    ]
    metrics = compute_run_metrics("r1", events)
    assert metrics.retried_nodes == 1
    assert metrics.rolled_back_nodes == 1


def test_compute_run_metrics_mttr_from_failure_to_recovery():
    events = [
        Event(run_id="r1", node_id="a", from_state="running", to_state="failed", timestamp="2026-01-01T00:00:00+00:00"),
        Event(run_id="r1", node_id="a", from_state="running", to_state="passed", timestamp="2026-01-01T00:00:05+00:00"),
    ]
    metrics = compute_run_metrics("r1", events)
    assert metrics.mttr_seconds == 5.0


def test_aggregate_metrics_averages_across_runs():
    events_r1 = [Event(run_id="r1", node_id="a", from_state="running", to_state="passed",
                        timestamp="2026-01-01T00:00:00+00:00")]
    events_r2 = [Event(run_id="r2", node_id="a", from_state="running", to_state="failed",
                        timestamp="2026-01-01T00:00:00+00:00")]
    report = aggregate_metrics({"r1": events_r1, "r2": events_r2})
    assert report["runs"] == 2
    assert report["avg_success_rate"] == 0.5
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/orchestrator/test_metrics.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement**

```python
# src/orchestrator/metrics.py
from __future__ import annotations
from dataclasses import dataclass, asdict
from datetime import datetime

from orchestrator.events import Event


@dataclass
class RunMetrics:
    run_id: str
    total_nodes: int
    passed_nodes: int
    retried_nodes: int
    rolled_back_nodes: int
    success_rate: float
    retry_frequency: float
    rollback_frequency: float
    mttr_seconds: float | None
    latency_seconds: float | None


def _parse(timestamp: str) -> datetime:
    return datetime.fromisoformat(timestamp)


def compute_run_metrics(run_id: str, events: list[Event]) -> RunMetrics:
    node_ids = {e.node_id for e in events}
    total = len(node_ids)
    passed = len({e.node_id for e in events if e.to_state == "passed"})
    retried = len({e.node_id for e in events if e.attempt > 1})
    rolled_back = len({e.node_id for e in events if e.to_state == "rolled_back"})

    recovery_times: list[float] = []
    open_failures: dict[str, datetime] = {}
    for event in sorted(events, key=lambda e: e.timestamp):
        ts = _parse(event.timestamp)
        if event.to_state == "failed":
            open_failures[event.node_id] = ts
        elif event.to_state in ("passed", "rolled_back") and event.node_id in open_failures:
            recovery_times.append((ts - open_failures.pop(event.node_id)).total_seconds())

    latency = None
    if events:
        timestamps = [_parse(e.timestamp) for e in events]
        latency = (max(timestamps) - min(timestamps)).total_seconds()

    return RunMetrics(
        run_id=run_id,
        total_nodes=total,
        passed_nodes=passed,
        retried_nodes=retried,
        rolled_back_nodes=rolled_back,
        success_rate=(passed / total) if total else 0.0,
        retry_frequency=(retried / total) if total else 0.0,
        rollback_frequency=(rolled_back / total) if total else 0.0,
        mttr_seconds=(sum(recovery_times) / len(recovery_times)) if recovery_times else None,
        latency_seconds=latency,
    )


def aggregate_metrics(all_events: dict[str, list[Event]]) -> dict:
    per_run = {run_id: compute_run_metrics(run_id, events) for run_id, events in all_events.items()}
    count = len(per_run) or 1
    return {
        "runs": len(per_run),
        "avg_success_rate": sum(m.success_rate for m in per_run.values()) / count,
        "avg_retry_frequency": sum(m.retry_frequency for m in per_run.values()) / count,
        "avg_rollback_frequency": sum(m.rollback_frequency for m in per_run.values()) / count,
        "per_run": {run_id: asdict(m) for run_id, m in per_run.items()},
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/orchestrator/test_metrics.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/orchestrator/metrics.py tests/orchestrator/test_metrics.py
git commit -m "feat: reliability metrics — success rate, retry/rollback frequency, MTTR, latency"
```

---

## Task 21: CLI

**Files:**
- Create: `src/orchestrator/cli.py`
- Test: `tests/orchestrator/test_cli.py`

**Interfaces:**
- Consumes: `Engine` (Tasks 15-18); `EventLog`, `RunStore` (Task 14); `aggregate_metrics` (Task 20); `scenarios.SCENARIO_REGISTRY` (placeholder dict from Task 1, populated for real in Task 22).
- Produces: `main(argv=None) -> None`, the `orchestrate` console-script entry point declared in `pyproject.toml` (Task 1). `run` accepts an optional `--target-dir PATH`; when given, it is persisted to `runs/<run_id>/target_dir.txt` and reloaded by every later `approve`/`reject`/`resume`/`stop` on that run, so a scenario can be pointed at an arbitrary codebase copy (Task 26 uses this to run each scenario against its own workspace) while defaulting to the real repo (`scenarios.common.REPO_ROOT`) when omitted.

Every scenario built by `scenarios.SCENARIO_REGISTRY[key]()` **must** construct its `Workflow` with `name=key` (e.g. `Workflow("greenfield", [...])` registered under `"greenfield"`) — the CLI looks workflows back up by `state.workflow_name` after `run`, so name and registry key must match exactly. State this as a hard constraint for Task 22-24's `build_workflow()` functions.

- [ ] **Step 1: Write the failing test**

```python
# tests/orchestrator/test_cli.py
import json

import scenarios
from orchestrator import cli
from orchestrator.graph import StageNode, Workflow
from tests.orchestrator.fakes import RecordingExecutor


def _fake_workflow():
    return Workflow("fake", [
        StageNode(id="a", executor=RecordingExecutor("a", []), requires_approval=True),
        StageNode(id="b", executor=RecordingExecutor("b", []), depends_on=["a"]),
    ])


def test_run_status_approve_round_trip(monkeypatch, capsys, tmp_path):
    monkeypatch.setitem(scenarios.SCENARIO_REGISTRY, "fake", _fake_workflow)
    monkeypatch.setattr(cli, "RUNS_DIR", tmp_path)

    cli.main(["run", "fake", "--run-id", "test-run"])
    assert "awaiting_approval" in capsys.readouterr().out

    cli.main(["status", "test-run"])
    assert "test-run" in capsys.readouterr().out

    cli.main(["approve", "test-run", "a", "--note", "ok"])
    assert "passed" in capsys.readouterr().out


def test_reject_then_metrics(monkeypatch, capsys, tmp_path):
    monkeypatch.setitem(scenarios.SCENARIO_REGISTRY, "fake", _fake_workflow)
    monkeypatch.setattr(cli, "RUNS_DIR", tmp_path)

    cli.main(["run", "fake", "--run-id", "test-run-2"])
    capsys.readouterr()
    cli.main(["reject", "test-run-2", "a", "--note", "redo", "--revise", "a"])
    capsys.readouterr()

    cli.main(["metrics"])
    report = json.loads(capsys.readouterr().out)
    assert report["runs"] == 1


def test_run_rejects_unknown_scenario(monkeypatch, tmp_path):
    monkeypatch.setattr(cli, "RUNS_DIR", tmp_path)
    try:
        cli.main(["run", "does-not-exist"])
        assert False, "expected SystemExit"
    except SystemExit:
        pass


def test_run_with_target_dir_persists_across_approve(monkeypatch, capsys, tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()

    def _fake_workflow_with_target(target_dir=None):
        assert target_dir == workspace
        return Workflow("fake-targeted", [
            StageNode(id="a", executor=RecordingExecutor("a", []), requires_approval=True),
        ])

    monkeypatch.setitem(scenarios.SCENARIO_REGISTRY, "fake-targeted", _fake_workflow_with_target)
    monkeypatch.setattr(cli, "RUNS_DIR", tmp_path / "runs")

    cli.main(["run", "fake-targeted", "--run-id", "targeted-run", "--target-dir", str(workspace)])
    capsys.readouterr()
    # approve reconstructs the workflow via the registry again; the fake builder's
    # own assertion (target_dir == workspace) is what proves the round-trip worked.
    cli.main(["approve", "targeted-run", "a", "--note", "ok"])
    assert "passed" in capsys.readouterr().out
```

(`Workflow` and `StageNode` are already imported at the top of this test file from Step 1.)

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/orchestrator/test_cli.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'orchestrator.cli'`

- [ ] **Step 3: Implement**

```python
# src/orchestrator/cli.py
from __future__ import annotations
import argparse
import json
import sys
import time
from pathlib import Path

from orchestrator.engine import Engine
from orchestrator.events import EventLog
from orchestrator.metrics import aggregate_metrics
from orchestrator.store import RunStore

RUNS_DIR = Path("runs")


def _ensure_repo_root_on_path() -> None:
    repo_root = Path(__file__).resolve().parents[2]
    if str(repo_root) not in sys.path:
        sys.path.insert(0, str(repo_root))


def _scenario_registry():
    _ensure_repo_root_on_path()
    from scenarios import SCENARIO_REGISTRY
    return SCENARIO_REGISTRY


def _build_workflow(name: str, target_dir: Path | None = None):
    registry = _scenario_registry()
    if name not in registry:
        raise SystemExit(f"unknown scenario '{name}'. Available: {sorted(registry)}")
    builder = registry[name]
    return builder(target_dir) if target_dir is not None else builder()


def _target_dir_marker(run_id: str) -> Path:
    return RUNS_DIR / run_id / "target_dir.txt"


def _save_target_dir(run_id: str, target_dir: Path | None) -> None:
    if target_dir is None:
        return
    marker = _target_dir_marker(run_id)
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(str(target_dir))


def _load_target_dir(run_id: str) -> Path | None:
    marker = _target_dir_marker(run_id)
    return Path(marker.read_text()) if marker.exists() else None


def _print_status(state) -> None:
    print(f"run: {state.run_id}  workflow: {state.workflow_name}")
    for node_id, status in state.node_status.items():
        print(f"  [{status:>17}] {node_id}")


def cmd_run(args) -> None:
    target_dir = Path(args.target_dir).resolve() if args.target_dir else None
    workflow = _build_workflow(args.scenario, target_dir)
    store = RunStore(RUNS_DIR)
    engine = Engine(workflow, store)
    run_id = args.run_id or f"{args.scenario}-{int(time.time())}"
    _save_target_dir(run_id, target_dir)
    _print_status(engine.start(run_id))


def cmd_status(args) -> None:
    store = RunStore(RUNS_DIR)
    _print_status(store.load(args.run_id))


def cmd_approve(args) -> None:
    store = RunStore(RUNS_DIR)
    state = store.load(args.run_id)
    workflow = _build_workflow(state.workflow_name, _load_target_dir(args.run_id))
    engine = Engine(workflow, store)
    _print_status(engine.approve(args.run_id, args.node, args.note or ""))


def cmd_reject(args) -> None:
    store = RunStore(RUNS_DIR)
    state = store.load(args.run_id)
    workflow = _build_workflow(state.workflow_name, _load_target_dir(args.run_id))
    engine = Engine(workflow, store)
    _print_status(engine.reject(args.run_id, args.node, args.note or "", args.revise))


def cmd_resume(args) -> None:
    store = RunStore(RUNS_DIR)
    state = store.load(args.run_id)
    workflow = _build_workflow(state.workflow_name, _load_target_dir(args.run_id))
    engine = Engine(workflow, store)
    _print_status(engine.resume(args.run_id))


def cmd_stop(args) -> None:
    store = RunStore(RUNS_DIR)
    state = store.load(args.run_id)
    workflow = _build_workflow(state.workflow_name, _load_target_dir(args.run_id))
    engine = Engine(workflow, store)
    _print_status(engine.stop(args.run_id))


def cmd_metrics(args) -> None:
    store = RunStore(RUNS_DIR)
    run_ids = [args.run] if args.run else store.list_runs()
    all_events = {run_id: EventLog(store.events_path(run_id)).read_all() for run_id in run_ids}
    print(json.dumps(aggregate_metrics(all_events), indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="orchestrate")
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run")
    p_run.add_argument("scenario")
    p_run.add_argument("--run-id", dest="run_id", default=None)
    p_run.add_argument("--target-dir", dest="target_dir", default=None)
    p_run.set_defaults(func=cmd_run)

    p_status = sub.add_parser("status")
    p_status.add_argument("run_id")
    p_status.set_defaults(func=cmd_status)

    p_approve = sub.add_parser("approve")
    p_approve.add_argument("run_id")
    p_approve.add_argument("node")
    p_approve.add_argument("--note", default="")
    p_approve.set_defaults(func=cmd_approve)

    p_reject = sub.add_parser("reject")
    p_reject.add_argument("run_id")
    p_reject.add_argument("node")
    p_reject.add_argument("--note", default="")
    p_reject.add_argument("--revise", required=True)
    p_reject.set_defaults(func=cmd_reject)

    p_resume = sub.add_parser("resume")
    p_resume.add_argument("run_id")
    p_resume.set_defaults(func=cmd_resume)

    p_stop = sub.add_parser("stop")
    p_stop.add_argument("run_id")
    p_stop.set_defaults(func=cmd_stop)

    p_metrics = sub.add_parser("metrics")
    p_metrics.add_argument("--run", default=None)
    p_metrics.set_defaults(func=cmd_metrics)

    return parser


def main(argv=None) -> None:
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/orchestrator/test_cli.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Run the entire orchestrator suite and commit**

Run: `pytest tests/orchestrator -v`
Expected: PASS — all tests from Tasks 12-21.

```bash
git add src/orchestrator/cli.py tests/orchestrator/test_cli.py
git commit -m "feat: orchestrate CLI (run/status/approve/reject/resume/stop/metrics)"
```

---

## Task 22: Scenario Infrastructure and the Greenfield Scenario

**Files:**
- Create: `scenarios/common.py`
- Create: `scenarios/greenfield_custom_alias.py`
- Modify: `scenarios/__init__.py` (replace placeholder with a real registry entry)
- Test: `tests/scenarios/test_greenfield_scenario.py`

**Interfaces:**
- Consumes: `StageExecutor`, `StageResult`, `StageStatus` (Task 12); `StageNode`, `Workflow` (Task 13); `Engine` (Tasks 15-18); `RunStore` (Task 14); `combine_guardrails`, `destructive_migration_requires_approval`, `test_coverage_threshold` (Task 19).
- Produces: `scenarios.common.copy_urlshortener_source(dest_dir) -> Path`, `scenarios.common.grep_impacted_files(target_dir, pattern) -> list[str]`, `scenarios.common.RunPytestExecutor(target_dir, test_path)`, `scenarios.common.ReleaseReadinessExecutor()` — all reused verbatim by Tasks 23-24. `scenarios.greenfield_custom_alias.build_workflow(target_dir: Path | None = None) -> Workflow`, registered under the exact key `"greenfield"` in `scenarios.SCENARIO_REGISTRY` (must match `Workflow(name=...)`, per the constraint stated in Task 21).

**Constraint carried from spec §3:** every `StageResult.outputs` value in every scenario executor must be JSON-serializable (`str`, `int`, `float`, `bool`, `list`, `dict`, `None`) — `RunStore.save` round-trips through `json.dumps`. Never put a `Path` object directly into `outputs`; convert with `str(...)` first.

- [ ] **Step 1: Write `scenarios/common.py` and the failing scenario test**

```python
# scenarios/common.py
from __future__ import annotations
import re
import shutil
import subprocess
import sys
from pathlib import Path

from orchestrator.executor import StageExecutor, StageResult, StageStatus

REPO_ROOT = Path(__file__).resolve().parent.parent


def copy_urlshortener_source(dest_dir: Path) -> Path:
    """Copies src/urlshortener and tests/urlshortener into dest_dir so a scenario's
    implementation executor can rewrite files without touching the real repo."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        REPO_ROOT / "src" / "urlshortener", dest_dir / "src" / "urlshortener", dirs_exist_ok=True
    )
    (dest_dir / "tests").mkdir(parents=True, exist_ok=True)
    (dest_dir / "tests" / "__init__.py").write_text("")
    shutil.copytree(
        REPO_ROOT / "tests" / "urlshortener", dest_dir / "tests" / "urlshortener", dirs_exist_ok=True
    )
    (dest_dir / "pyproject.toml").write_text((REPO_ROOT / "pyproject.toml").read_text())
    return dest_dir


def grep_impacted_files(target_dir: Path, pattern: str) -> list[str]:
    proc = subprocess.run(
        ["grep", "-rl", pattern, str(target_dir / "src")], capture_output=True, text=True,
    )
    return sorted(str(Path(p).relative_to(target_dir)) for p in proc.stdout.splitlines() if p)


def _parse_pytest_summary(stdout: str) -> tuple[int, int]:
    passed = int(m.group(1)) if (m := re.search(r"(\d+) passed", stdout)) else 0
    failed = int(m.group(1)) if (m := re.search(r"(\d+) failed", stdout)) else 0
    return passed, passed + failed


class RunPytestExecutor(StageExecutor):
    name = "run_pytest"

    def __init__(self, target_dir: Path, test_path: str):
        self.target_dir = target_dir
        self.test_path = test_path

    def run(self, context) -> StageResult:
        proc = subprocess.run(
            [sys.executable, "-m", "pytest", self.test_path, "-v"],
            cwd=self.target_dir, capture_output=True, text=True,
        )
        passed, total = _parse_pytest_summary(proc.stdout)
        status = StageStatus.PASSED if proc.returncode == 0 else StageStatus.FAILED
        tail = proc.stdout.strip().splitlines()[-1] if proc.stdout.strip() else proc.stderr[-500:]
        return StageResult(status=status, outputs={"tests_total": total, "tests_passed": passed}, notes=tail)


class ReleaseReadinessExecutor(StageExecutor):
    """Aggregates the implementation and unit_tests stage outputs so the release_readiness
    node's exit_gate (built from orchestrator.guardrails) has something to inspect."""

    name = "release_readiness"

    def run(self, context) -> StageResult:
        impl = context.latest("implementation")
        tests = context.latest("unit_tests")
        outputs = {
            "schema_change": impl.outputs.get("schema_change", "none") if impl else "none",
            "tests_total": tests.outputs.get("tests_total", 0) if tests else 0,
            "tests_passed": tests.outputs.get("tests_passed", 0) if tests else 0,
            "diff_preview": impl.outputs.get("diff_preview", "") if impl else "",
        }
        return StageResult(status=StageStatus.PASSED, outputs=outputs, notes="Release readiness check aggregated.")
```

```python
# tests/scenarios/test_greenfield_scenario.py
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest
from orchestrator.engine import Engine
from orchestrator.store import RunStore
from scenarios.common import copy_urlshortener_source
from scenarios.greenfield_custom_alias import build_workflow


@pytest.fixture
def target_dir():
    with tempfile.TemporaryDirectory() as tmp:
        yield copy_urlshortener_source(Path(tmp) / "target")


def test_greenfield_scenario_adds_alias_and_expiry(target_dir):
    workflow = build_workflow(target_dir)
    engine = Engine(workflow, RunStore(target_dir / "runs"))
    state = engine.start("greenfield-test")

    assert state.node_status["implementation"] == "passed"
    assert state.node_status["unit_tests"] == "passed"
    assert state.node_status["documentation"] == "passed"
    assert state.node_status["release_readiness"] == "awaiting_approval"
    assert (target_dir / "tests" / "urlshortener" / "test_alias_and_expiry.py").exists()

    approved = engine.approve("greenfield-test", "release_readiness", note="looks good")
    assert approved.node_status["release_readiness"] == "passed"


def test_greenfield_implementation_passes_full_urlshortener_suite(target_dir):
    workflow = build_workflow(target_dir)
    Engine(workflow, RunStore(target_dir / "runs")).start("greenfield-test-2")

    proc = subprocess.run(
        [sys.executable, "-m", "pytest", "tests/urlshortener", "-q"],
        cwd=target_dir, capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/scenarios/test_greenfield_scenario.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'scenarios.greenfield_custom_alias'`

- [ ] **Step 3: Implement `scenarios/greenfield_custom_alias.py`**

```python
# scenarios/greenfield_custom_alias.py
from __future__ import annotations
from pathlib import Path

from orchestrator.executor import StageExecutor, StageResult, StageStatus
from orchestrator.graph import StageNode, Workflow
from orchestrator.guardrails import destructive_migration_requires_approval, test_coverage_threshold
from orchestrator.policy import combine_guardrails
from scenarios.common import REPO_ROOT, ReleaseReadinessExecutor, RunPytestExecutor

VALIDATION_V2 = '''from urllib.parse import urlparse

_ALLOWED_SCHEMES = {"http", "https"}
_ALIAS_MIN_LENGTH = 3
_ALIAS_MAX_LENGTH = 32
_MAX_EXPIRY_SECONDS = 60 * 60 * 24 * 365


def validate_url(url: str) -> None:
    if not url:
        raise ValueError("url must not be empty")
    parsed = urlparse(url)
    if parsed.scheme not in _ALLOWED_SCHEMES:
        raise ValueError(f"url scheme must be one of {_ALLOWED_SCHEMES}")
    if not parsed.netloc:
        raise ValueError("url must include a host")


def validate_alias(alias: str) -> None:
    if not (_ALIAS_MIN_LENGTH <= len(alias) <= _ALIAS_MAX_LENGTH):
        raise ValueError(
            f"alias must be between {_ALIAS_MIN_LENGTH} and {_ALIAS_MAX_LENGTH} characters"
        )
    if not alias.isalnum():
        raise ValueError("alias must be alphanumeric")


def validate_expiry(expires_in_seconds: int) -> None:
    if expires_in_seconds <= 0:
        raise ValueError("expires_in_seconds must be positive")
    if expires_in_seconds > _MAX_EXPIRY_SECONDS:
        raise ValueError(f"expires_in_seconds must not exceed {_MAX_EXPIRY_SECONDS}")
'''

URLS_REPO_V2 = '''import sqlite3
from datetime import datetime, timezone

from urlshortener.domain.codes import generate_code


class UrlsRepo:
    def __init__(self, conn: sqlite3.Connection):
        self.conn = conn

    def _exists(self, code: str) -> bool:
        row = self.conn.execute("SELECT 1 FROM urls WHERE code = ?", (code,)).fetchone()
        return row is not None

    def create(self, target_url: str, code: str | None = None, expires_at: str | None = None) -> dict:
        if code is not None:
            if self._exists(code):
                raise ValueError(f"alias '{code}' is already taken")
        else:
            code = generate_code(exists=self._exists)
        created_at = datetime.now(timezone.utc).isoformat()
        self.conn.execute(
            "INSERT INTO urls (code, target_url, created_at, active, click_count, expires_at) "
            "VALUES (?, ?, ?, 1, 0, ?)",
            (code, target_url, created_at, expires_at),
        )
        self.conn.commit()
        return self.get(code)

    def get(self, code: str) -> dict | None:
        row = self.conn.execute(
            "SELECT code, target_url, created_at, active, click_count, expires_at FROM urls WHERE code = ?",
            (code,),
        ).fetchone()
        if row is None:
            return None
        return {
            "code": row[0], "target_url": row[1], "created_at": row[2],
            "active": row[3], "click_count": row[4], "expires_at": row[5],
        }

    def soft_delete(self, code: str) -> bool:
        cur = self.conn.execute("UPDATE urls SET active = 0 WHERE code = ? AND active = 1", (code,))
        self.conn.commit()
        return cur.rowcount > 0
'''

SCHEMAS_V2 = '''from pydantic import BaseModel


class CreateUrlRequest(BaseModel):
    url: str
    custom_alias: str | None = None
    expires_in_seconds: int | None = None


class UrlResponse(BaseModel):
    code: str
    target_url: str
    short_url: str
    created_at: str
    active: bool
    click_count: int
    expires_at: str | None = None
'''

URLS_ROUTER_V2 = '''from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException

from urlshortener.api.deps import get_urls_repo
from urlshortener.api.schemas import CreateUrlRequest, UrlResponse
from urlshortener.config import BASE_URL
from urlshortener.domain.validation import validate_alias, validate_expiry, validate_url
from urlshortener.repository.urls_repo import UrlsRepo

router = APIRouter(prefix="/api/urls", tags=["urls"])


def _to_response(row: dict) -> UrlResponse:
    return UrlResponse(
        code=row["code"], target_url=row["target_url"], short_url=f"{BASE_URL}/{row['code']}",
        created_at=row["created_at"], active=bool(row["active"]), click_count=row["click_count"],
        expires_at=row.get("expires_at"),
    )


@router.post("", response_model=UrlResponse, status_code=201)
def create_url(payload: CreateUrlRequest, repo: UrlsRepo = Depends(get_urls_repo)):
    try:
        validate_url(payload.url)
        if payload.custom_alias is not None:
            validate_alias(payload.custom_alias)
        if payload.expires_in_seconds is not None:
            validate_expiry(payload.expires_in_seconds)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))

    expires_at = None
    if payload.expires_in_seconds is not None:
        expires_at = (
            datetime.now(timezone.utc) + timedelta(seconds=payload.expires_in_seconds)
        ).isoformat()

    try:
        row = repo.create(payload.url, code=payload.custom_alias, expires_at=expires_at)
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    return _to_response(row)


@router.get("/{code}", response_model=UrlResponse)
def get_url(code: str, repo: UrlsRepo = Depends(get_urls_repo)):
    row = repo.get(code)
    if row is None:
        raise HTTPException(status_code=404, detail="code not found")
    return _to_response(row)


@router.delete("/{code}", status_code=204)
def delete_url(code: str, repo: UrlsRepo = Depends(get_urls_repo)):
    if not repo.soft_delete(code):
        raise HTTPException(status_code=404, detail="code not found")
'''

REDIRECT_ROUTER_V2 = '''from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse

from urlshortener.api.deps import get_urls_repo, get_clicks_repo
from urlshortener.repository.urls_repo import UrlsRepo
from urlshortener.repository.clicks_repo import ClicksRepo

router = APIRouter(tags=["redirect"])


def _is_expired(row: dict) -> bool:
    if not row.get("expires_at"):
        return False
    return datetime.fromisoformat(row["expires_at"]) <= datetime.now(timezone.utc)


@router.get("/{code}")
def redirect(
    code: str, request: Request,
    urls_repo: UrlsRepo = Depends(get_urls_repo),
    clicks_repo: ClicksRepo = Depends(get_clicks_repo),
):
    row = urls_repo.get(code)
    if row is None:
        raise HTTPException(status_code=404, detail="code not found")
    if not row["active"] or _is_expired(row):
        raise HTTPException(status_code=410, detail="link has been deleted or expired")
    clicks_repo.record_click(
        code, referrer=request.headers.get("referer"), user_agent=request.headers.get("user-agent"),
    )
    return RedirectResponse(url=row["target_url"], status_code=302)
'''

TEST_ALIAS_EXPIRY = '''import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from urlshortener.main import create_app


@pytest.fixture
def client():
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(db_path=str(Path(tmp) / "t.db"))
        yield TestClient(app)


def test_create_with_custom_alias(client):
    resp = client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "mylink"})
    assert resp.status_code == 201
    assert resp.json()["code"] == "mylink"


def test_create_with_duplicate_alias_returns_409(client):
    client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "dup"})
    resp = client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "dup"})
    assert resp.status_code == 409


def test_create_with_invalid_alias_returns_422(client):
    resp = client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "a"})
    assert resp.status_code == 422


def test_create_with_expiry_sets_expires_at(client):
    resp = client.post("/api/urls", json={"url": "https://example.com", "expires_in_seconds": 3600})
    assert resp.status_code == 201
    assert resp.json()["expires_at"] is not None


def test_redirect_410_when_expired(client, monkeypatch):
    created = client.post(
        "/api/urls", json={"url": "https://example.com", "expires_in_seconds": 1}
    ).json()
    from urlshortener.api import redirect as redirect_module

    monkeypatch.setattr(redirect_module, "_is_expired", lambda row: True)
    resp = client.get(f"/{created['code']}", follow_redirects=False)
    assert resp.status_code == 410
'''


class GreenfieldRequirementsExecutor(StageExecutor):
    name = "greenfield_requirements"

    def run(self, context) -> StageResult:
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "problem": "Users want to choose their own short code and set an optional expiry.",
                "assumptions": [
                    "Custom aliases are case-sensitive and must be alphanumeric, 3-32 chars.",
                    "Expiry is measured in seconds from creation, capped at 1 year.",
                ],
                "acceptance_criteria": [
                    "POST /api/urls accepts optional custom_alias and expires_in_seconds.",
                    "A taken alias returns 409.",
                    "An expired link returns 410 on redirect.",
                ],
            },
            notes="Normalized greenfield requirement: custom alias + expiry.",
        )


class GreenfieldDesignExecutor(StageExecutor):
    name = "greenfield_design"

    def run(self, context) -> StageResult:
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "task_plan": [
                    "Add nullable expires_at column via migration 0003.",
                    "Extend validation.py with validate_alias/validate_expiry.",
                    "Extend UrlsRepo.create to accept code/expires_at.",
                    "Extend CreateUrlRequest/UrlResponse schemas.",
                    "Extend urls.py router to pass through new fields, handle 409/422.",
                    "Extend redirect.py to 410 on expiry.",
                ],
                "risks": ["Alias collisions with previously auto-generated codes."],
            },
            notes="Task plan for custom alias + expiry.",
        )


class GreenfieldImplementationExecutor(StageExecutor):
    name = "greenfield_implementation"

    def __init__(self, target_dir: Path):
        self.target_dir = target_dir

    def run(self, context) -> StageResult:
        src = self.target_dir / "src" / "urlshortener"
        (src / "domain" / "validation.py").write_text(VALIDATION_V2)
        (src / "repository" / "migrations" / "0003_add_expiry.sql").write_text(
            "ALTER TABLE urls ADD COLUMN expires_at TEXT;\\n"
        )
        (src / "repository" / "urls_repo.py").write_text(URLS_REPO_V2)
        (src / "api" / "schemas.py").write_text(SCHEMAS_V2)
        (src / "api" / "urls.py").write_text(URLS_ROUTER_V2)
        (src / "api" / "redirect.py").write_text(REDIRECT_ROUTER_V2)
        (self.target_dir / "tests" / "urlshortener" / "test_alias_and_expiry.py").write_text(TEST_ALIAS_EXPIRY)
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "files_changed": [
                    "src/urlshortener/domain/validation.py",
                    "src/urlshortener/repository/migrations/0003_add_expiry.sql",
                    "src/urlshortener/repository/urls_repo.py",
                    "src/urlshortener/api/schemas.py",
                    "src/urlshortener/api/urls.py",
                    "src/urlshortener/api/redirect.py",
                    "tests/urlshortener/test_alias_and_expiry.py",
                ],
                "schema_change": "additive",
                "diff_preview": "ALTER TABLE urls ADD COLUMN expires_at TEXT;",
            },
            notes="Added custom_alias and expires_in_seconds support end-to-end.",
        )


class GreenfieldDocumentationExecutor(StageExecutor):
    name = "greenfield_documentation"

    def __init__(self, target_dir: Path):
        self.target_dir = target_dir

    def run(self, context) -> StageResult:
        docs_dir = self.target_dir / "docs"
        docs_dir.mkdir(parents=True, exist_ok=True)
        doc_path = docs_dir / "api-changes.md"
        section = (
            "\\n## Custom alias and expiry (greenfield scenario)\\n\\n"
            "`POST /api/urls` now accepts optional `custom_alias` (3-32 alphanumeric chars) "
            "and `expires_in_seconds` (1 to 31536000). A taken alias returns 409. "
            "An expired link returns 410 on redirect.\\n"
        )
        existing = doc_path.read_text() if doc_path.exists() else "# API Changes\\n"
        doc_path.write_text(existing + section)
        return StageResult(
            status=StageStatus.PASSED,
            outputs={"files_changed": ["docs/api-changes.md"]},
            notes="Documented custom alias and expiry fields.",
        )


def build_workflow(target_dir: Path | None = None) -> Workflow:
    target_dir = target_dir or REPO_ROOT
    requirements = StageNode(id="requirements", executor=GreenfieldRequirementsExecutor())
    design = StageNode(id="design", executor=GreenfieldDesignExecutor(), depends_on=["requirements"])
    implementation = StageNode(
        id="implementation", executor=GreenfieldImplementationExecutor(target_dir), depends_on=["design"],
    )
    unit_tests = StageNode(
        id="unit_tests",
        executor=RunPytestExecutor(target_dir, "tests/urlshortener/test_alias_and_expiry.py"),
        depends_on=["implementation"],
    )
    documentation = StageNode(
        id="documentation", executor=GreenfieldDocumentationExecutor(target_dir), depends_on=["implementation"],
    )
    release_readiness = StageNode(
        id="release_readiness",
        executor=ReleaseReadinessExecutor(),
        depends_on=["unit_tests", "documentation"],
        exit_gate=combine_guardrails(destructive_migration_requires_approval, test_coverage_threshold(1.0)),
        requires_approval=True,
    )
    return Workflow(
        "greenfield", [requirements, design, implementation, unit_tests, documentation, release_readiness]
    )
```

Replace `scenarios/__init__.py`:

```python
# scenarios/__init__.py
from scenarios.greenfield_custom_alias import build_workflow as greenfield

SCENARIO_REGISTRY: dict = {
    "greenfield": greenfield,
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/scenarios/test_greenfield_scenario.py -v`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add scenarios/common.py scenarios/greenfield_custom_alias.py scenarios/__init__.py \
        tests/scenarios/test_greenfield_scenario.py
git commit -m "feat: scenario infrastructure and greenfield custom-alias/expiry scenario"
```

---

## Task 23: Brownfield Scenario — Click-Counter Race Fix

**Files:**
- Create: `scenarios/brownfield_click_counter_fix.py`
- Modify: `scenarios/__init__.py` (add the brownfield entry alongside greenfield)
- Test: `tests/scenarios/test_brownfield_scenario.py`

**Interfaces:**
- Consumes: `grep_impacted_files`, `RunPytestExecutor`, `ReleaseReadinessExecutor`, `copy_urlshortener_source` (Task 22); `RetryPolicy` (Task 13).
- Produces: `scenarios.brownfield_click_counter_fix.build_workflow(target_dir=None) -> Workflow`, registered as `"brownfield"`.

This scenario demonstrates codebase-impact reasoning on an **existing** system: the `design` stage runs a real `grep` against the copied source before proposing changes, and `implementation` removes the `xfail` marker from the exact regression test written in Task 11 — proving the fix against the same test that documented the bug, not a rewritten one.

- [ ] **Step 1: Write the failing test**

```python
# tests/scenarios/test_brownfield_scenario.py
import tempfile
from pathlib import Path

import pytest
from orchestrator.engine import Engine
from orchestrator.store import RunStore
from scenarios.common import copy_urlshortener_source
from scenarios.brownfield_click_counter_fix import build_workflow


@pytest.fixture
def target_dir():
    with tempfile.TemporaryDirectory() as tmp:
        yield copy_urlshortener_source(Path(tmp) / "target")


def test_brownfield_scenario_fixes_concurrency_bug(target_dir):
    workflow = build_workflow(target_dir)
    engine = Engine(workflow, RunStore(target_dir / "runs"))
    state = engine.start("brownfield-test")

    assert state.node_status["design"] == "passed"
    impacted = state.context.latest("design").outputs["impacted_files"]
    assert any("clicks_repo.py" in f for f in impacted)

    assert state.node_status["implementation"] == "passed"
    assert state.node_status["unit_tests"] == "passed"
    assert state.node_status["release_readiness"] == "awaiting_approval"

    fixed_source = (target_dir / "tests" / "urlshortener" / "test_concurrency_clicks.py").read_text()
    assert "xfail" not in fixed_source
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/scenarios/test_brownfield_scenario.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'scenarios.brownfield_click_counter_fix'`

- [ ] **Step 3: Implement**

```python
# scenarios/brownfield_click_counter_fix.py
from __future__ import annotations
from pathlib import Path

from orchestrator.executor import StageExecutor, StageResult, StageStatus
from orchestrator.graph import RetryPolicy, StageNode, Workflow
from orchestrator.guardrails import destructive_migration_requires_approval, test_coverage_threshold
from orchestrator.policy import combine_guardrails
from scenarios.common import REPO_ROOT, ReleaseReadinessExecutor, RunPytestExecutor, grep_impacted_files

CLICKS_REPO_V2 = '''import sqlite3
from datetime import datetime, timezone


class ClicksRepo:
    def __init__(self, conn: sqlite3.Connection):
        self.conn = conn

    def record_click(self, code: str, referrer: str | None, user_agent: str | None) -> None:
        # Atomic increment: click_count = click_count + 1 happens entirely inside
        # SQLite's own statement execution, eliminating the Python-level
        # read-modify-write race from the previous implementation.
        self.conn.execute("UPDATE urls SET click_count = click_count + 1 WHERE code = ?", (code,))
        self.conn.execute(
            "INSERT INTO clicks (code, timestamp, referrer, user_agent) VALUES (?, ?, ?, ?)",
            (code, datetime.now(timezone.utc).isoformat(), referrer, user_agent),
        )
        self.conn.commit()

    def analytics(self, code: str) -> dict:
        total = self.conn.execute(
            "SELECT COUNT(*) FROM clicks WHERE code = ?", (code,)
        ).fetchone()[0]
        referrer_rows = self.conn.execute(
            "SELECT referrer, COUNT(*) as c FROM clicks WHERE code = ? "
            "GROUP BY referrer ORDER BY c DESC LIMIT 5",
            (code,),
        ).fetchall()
        return {
            "total_clicks": total,
            "top_referrers": [{"referrer": r[0], "count": r[1]} for r in referrer_rows],
        }
'''

CONCURRENCY_TEST_FIXED = '''import tempfile
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

from urlshortener.repository.db import get_connection, run_migrations, MIGRATIONS_DIR
from urlshortener.repository.urls_repo import UrlsRepo
from urlshortener.repository.clicks_repo import ClicksRepo


def test_concurrent_clicks_are_not_lost():
    with tempfile.TemporaryDirectory() as tmp:
        db_path = str(Path(tmp) / "t.db")
        setup_conn = get_connection(db_path)
        run_migrations(setup_conn, MIGRATIONS_DIR)
        code = UrlsRepo(setup_conn).create("https://example.com")["code"]
        setup_conn.close()

        def record_one():
            conn = get_connection(db_path)
            ClicksRepo(conn).record_click(code, referrer=None, user_agent="pytest")
            conn.close()

        with ThreadPoolExecutor(max_workers=20) as pool:
            list(pool.map(lambda _: record_one(), range(50)))

        verify_conn = get_connection(db_path)
        final_count = UrlsRepo(verify_conn).get(code)["click_count"]
        assert final_count == 50
'''


class BrownfieldRequirementsExecutor(StageExecutor):
    name = "brownfield_requirements"

    def run(self, context) -> StageResult:
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "problem": (
                    "Clicks recorded under concurrent load are sometimes lost; the "
                    "analytics query should stay fast as click volume grows."
                ),
                "assumptions": [
                    "The loss is a read-modify-write race in ClicksRepo.record_click, "
                    "not a client-side retry issue.",
                    "An index on clicks(code) is sufficient for the current analytics query shape.",
                ],
                "acceptance_criteria": [
                    "50 concurrent click writes against one code result in click_count == 50.",
                    "tests/urlshortener/test_concurrency_clicks.py passes without the xfail marker.",
                ],
            },
            notes="Normalized brownfield requirement: click-counter race + analytics query efficiency.",
        )


class BrownfieldDesignExecutor(StageExecutor):
    name = "brownfield_design"

    def __init__(self, target_dir: Path):
        self.target_dir = target_dir

    def run(self, context) -> StageResult:
        impacted = grep_impacted_files(self.target_dir, "click_count")
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "impacted_files": impacted,
                "task_plan": [
                    "Replace the read-modify-write increment in ClicksRepo.record_click "
                    "with an atomic SQL UPDATE.",
                    "Add an index on clicks(code) to keep the analytics query fast at scale.",
                    "Remove the xfail marker from the concurrency regression test once fixed.",
                ],
                "risks": [
                    "An atomic UPDATE alone does not guarantee no SQLITE_BUSY under very high "
                    "contention; PRAGMA busy_timeout (Task 5) mitigates it."
                ],
            },
            notes=f"Impact scan found {len(impacted)} file(s) referencing click_count.",
        )


class BrownfieldImplementationExecutor(StageExecutor):
    name = "brownfield_implementation"

    def __init__(self, target_dir: Path):
        self.target_dir = target_dir

    def run(self, context) -> StageResult:
        src = self.target_dir / "src" / "urlshortener"
        (src / "repository" / "migrations" / "0002_clicks_index.sql").write_text(
            "CREATE INDEX IF NOT EXISTS idx_clicks_code ON clicks(code);\\n"
        )
        (src / "repository" / "clicks_repo.py").write_text(CLICKS_REPO_V2)
        (self.target_dir / "tests" / "urlshortener" / "test_concurrency_clicks.py").write_text(
            CONCURRENCY_TEST_FIXED
        )
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "files_changed": [
                    "src/urlshortener/repository/migrations/0002_clicks_index.sql",
                    "src/urlshortener/repository/clicks_repo.py",
                    "tests/urlshortener/test_concurrency_clicks.py",
                ],
                "schema_change": "additive",
                "diff_preview": "CREATE INDEX IF NOT EXISTS idx_clicks_code ON clicks(code);",
            },
            notes=(
                "Replaced read-modify-write increment with an atomic UPDATE; "
                "added clicks(code) index; unmarked the regression test."
            ),
        )


class BrownfieldDocumentationExecutor(StageExecutor):
    name = "brownfield_documentation"

    def __init__(self, target_dir: Path):
        self.target_dir = target_dir

    def run(self, context) -> StageResult:
        docs_dir = self.target_dir / "docs"
        docs_dir.mkdir(parents=True, exist_ok=True)
        doc_path = docs_dir / "api-changes.md"
        section = (
            "\\n## Click-counter race condition fix (brownfield scenario)\\n\\n"
            "`ClicksRepo.record_click` previously read `click_count`, incremented it in "
            "Python, then wrote it back — a read-modify-write race that lost updates under "
            "concurrent writers. It now uses a single atomic `UPDATE urls SET click_count = "
            "click_count + 1 WHERE code = ?` statement. An index on `clicks(code)` keeps the "
            "analytics query fast.\\n"
        )
        existing = doc_path.read_text() if doc_path.exists() else "# API Changes\\n"
        doc_path.write_text(existing + section)
        return StageResult(
            status=StageStatus.PASSED,
            outputs={"files_changed": ["docs/api-changes.md"]},
            notes="Documented the click-counter race fix.",
        )


def build_workflow(target_dir: Path | None = None) -> Workflow:
    target_dir = target_dir or REPO_ROOT
    requirements = StageNode(id="requirements", executor=BrownfieldRequirementsExecutor())
    design = StageNode(
        id="design", executor=BrownfieldDesignExecutor(target_dir), depends_on=["requirements"]
    )
    implementation = StageNode(
        id="implementation", executor=BrownfieldImplementationExecutor(target_dir), depends_on=["design"],
    )
    unit_tests = StageNode(
        id="unit_tests",
        executor=RunPytestExecutor(target_dir, "tests/urlshortener/test_concurrency_clicks.py"),
        depends_on=["implementation"],
        retry_policy=RetryPolicy(max_attempts=2, backoff_seconds=0.2),
    )
    documentation = StageNode(
        id="documentation", executor=BrownfieldDocumentationExecutor(target_dir), depends_on=["implementation"],
    )
    release_readiness = StageNode(
        id="release_readiness",
        executor=ReleaseReadinessExecutor(),
        depends_on=["unit_tests", "documentation"],
        exit_gate=combine_guardrails(destructive_migration_requires_approval, test_coverage_threshold(1.0)),
        requires_approval=True,
    )
    return Workflow(
        "brownfield", [requirements, design, implementation, unit_tests, documentation, release_readiness]
    )
```

Replace `scenarios/__init__.py`:

```python
# scenarios/__init__.py
from scenarios.greenfield_custom_alias import build_workflow as greenfield
from scenarios.brownfield_click_counter_fix import build_workflow as brownfield

SCENARIO_REGISTRY: dict = {
    "greenfield": greenfield,
    "brownfield": brownfield,
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/scenarios -v`
Expected: PASS (4 tests — greenfield's 2 plus brownfield's 2)

- [ ] **Step 5: Commit**

```bash
git add scenarios/brownfield_click_counter_fix.py scenarios/__init__.py \
        tests/scenarios/test_brownfield_scenario.py
git commit -m "feat: brownfield scenario — fix click-counter race condition with impact analysis"
```

---

## Task 24: Ambiguous Scenario — "Make It More Secure"

**Files:**
- Create: `scenarios/ambiguous_security_hardening.py`
- Modify: `scenarios/__init__.py` (add the ambiguous entry)
- Test: `tests/scenarios/test_ambiguous_scenario.py`

**Interfaces:**
- Consumes: `ReleaseReadinessExecutor`, `RunPytestExecutor`, `copy_urlshortener_source` (Task 22).
- Produces: `scenarios.ambiguous_security_hardening.build_workflow(target_dir=None) -> Workflow`, registered as `"ambiguous"`.

This scenario is the requirement-understanding demonstration: `requirements` has `requires_approval=True` directly on the node (not via a guardrail), so **no downstream stage can run until a human approves the normalized scope** — this is checked explicitly in the test below.

- [ ] **Step 1: Write the failing test**

```python
# tests/scenarios/test_ambiguous_scenario.py
import tempfile
from pathlib import Path

import pytest
from orchestrator.engine import Engine
from orchestrator.store import RunStore
from scenarios.common import copy_urlshortener_source
from scenarios.ambiguous_security_hardening import build_workflow


@pytest.fixture
def target_dir():
    with tempfile.TemporaryDirectory() as tmp:
        yield copy_urlshortener_source(Path(tmp) / "target")


def test_requirements_blocks_until_approved(target_dir):
    workflow = build_workflow(target_dir)
    engine = Engine(workflow, RunStore(target_dir / "runs"))
    state = engine.start("ambiguous-test")

    assert state.node_status["requirements"] == "awaiting_approval"
    assert state.node_status["design"] == "pending"
    sub_reqs = state.context.latest("requirements").outputs["sub_requirements"]
    assert len(sub_reqs) == 3


def test_approving_requirements_runs_full_workflow_to_release_gate(target_dir):
    workflow = build_workflow(target_dir)
    engine = Engine(workflow, RunStore(target_dir / "runs"))
    engine.start("ambiguous-test-2")
    state = engine.approve("ambiguous-test-2", "requirements", note="scope approved")

    assert state.node_status["design"] == "passed"
    assert state.node_status["implementation"] == "passed"
    assert state.node_status["unit_tests"] == "passed"
    assert state.node_status["release_readiness"] == "awaiting_approval"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/scenarios/test_ambiguous_scenario.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'scenarios.ambiguous_security_hardening'`

- [ ] **Step 3: Implement**

```python
# scenarios/ambiguous_security_hardening.py
from __future__ import annotations
from pathlib import Path

from orchestrator.executor import StageExecutor, StageResult, StageStatus
from orchestrator.graph import StageNode, Workflow
from orchestrator.guardrails import destructive_migration_requires_approval, test_coverage_threshold
from orchestrator.policy import combine_guardrails
from scenarios.common import REPO_ROOT, ReleaseReadinessExecutor, RunPytestExecutor

VALIDATION_V3 = '''import ipaddress
from urllib.parse import urlparse

_ALLOWED_SCHEMES = {"http", "https"}
_BLOCKED_HOSTS = {"localhost"}


def validate_url(url: str) -> None:
    if not url:
        raise ValueError("url must not be empty")
    parsed = urlparse(url)
    if parsed.scheme not in _ALLOWED_SCHEMES:
        raise ValueError(f"url scheme must be one of {_ALLOWED_SCHEMES}")
    if not parsed.netloc:
        raise ValueError("url must include a host")
    host = parsed.hostname or ""
    if host.lower() in _BLOCKED_HOSTS:
        raise ValueError("url host is not allowed (internal/loopback address)")
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        ip = None
    if ip is not None and (ip.is_private or ip.is_loopback or ip.is_link_local):
        raise ValueError("url host is not allowed (internal/loopback address)")
'''

CONFIG_V2 = '''CODE_LENGTH = 7
RATE_LIMIT_CAPACITY = 60
RATE_LIMIT_REFILL_PER_SECOND = 1.0
BASE_URL = "http://localhost:8000"
OWNER_TOKEN = "change-me-in-production"  # prototype-only static token; see docs/testing-and-tradeoffs.md
'''

URLS_ROUTER_SECURE = '''from fastapi import APIRouter, Depends, Header, HTTPException

from urlshortener.api.deps import get_urls_repo
from urlshortener.api.schemas import CreateUrlRequest, UrlResponse
from urlshortener.config import BASE_URL, OWNER_TOKEN
from urlshortener.domain.validation import validate_url
from urlshortener.repository.urls_repo import UrlsRepo

router = APIRouter(prefix="/api/urls", tags=["urls"])


def _to_response(row: dict) -> UrlResponse:
    return UrlResponse(
        code=row["code"], target_url=row["target_url"], short_url=f"{BASE_URL}/{row['code']}",
        created_at=row["created_at"], active=bool(row["active"]), click_count=row["click_count"],
    )


@router.post("", response_model=UrlResponse, status_code=201)
def create_url(payload: CreateUrlRequest, repo: UrlsRepo = Depends(get_urls_repo)):
    try:
        validate_url(payload.url)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))
    row = repo.create(payload.url)
    return _to_response(row)


@router.get("/{code}", response_model=UrlResponse)
def get_url(code: str, repo: UrlsRepo = Depends(get_urls_repo)):
    row = repo.get(code)
    if row is None:
        raise HTTPException(status_code=404, detail="code not found")
    return _to_response(row)


@router.delete("/{code}", status_code=204)
def delete_url(
    code: str,
    repo: UrlsRepo = Depends(get_urls_repo),
    x_owner_token: str | None = Header(default=None),
):
    if x_owner_token != OWNER_TOKEN:
        raise HTTPException(status_code=403, detail="missing or invalid owner token")
    if not repo.soft_delete(code):
        raise HTTPException(status_code=404, detail="code not found")
'''

TEST_SECURITY_HARDENING = '''import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from urlshortener.main import create_app
from urlshortener.config import OWNER_TOKEN


@pytest.fixture
def client():
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(db_path=str(Path(tmp) / "t.db"))
        yield TestClient(app)


def test_create_rejects_loopback_target(client):
    resp = client.post("/api/urls", json={"url": "http://127.0.0.1/admin"})
    assert resp.status_code == 422


def test_create_rejects_localhost_target(client):
    resp = client.post("/api/urls", json={"url": "http://localhost/admin"})
    assert resp.status_code == 422


def test_create_accepts_public_target(client):
    resp = client.post("/api/urls", json={"url": "https://example.com"})
    assert resp.status_code == 201


def test_delete_without_token_is_forbidden(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    resp = client.delete(f"/api/urls/{created['code']}")
    assert resp.status_code == 403


def test_delete_with_correct_token_succeeds(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    resp = client.delete(f"/api/urls/{created['code']}", headers={"X-Owner-Token": OWNER_TOKEN})
    assert resp.status_code == 204
'''


class AmbiguousSecurityRequirementsExecutor(StageExecutor):
    name = "ambiguous_security_requirements"

    def run(self, context) -> StageResult:
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "raw_requirement": "Make it more secure.",
                "interpretation": (
                    "\\"More secure\\" is not actionable as stated. Normalized into three "
                    "concrete, independently verifiable sub-requirements based on the "
                    "service's current threat exposure."
                ),
                "assumptions": [
                    "Rate limiting (already implemented) is in scope for confirmation, not redesign.",
                    "\\"Secure\\" does not include authentication/user accounts — out of scope, flagged as a limitation.",
                    "A static bearer token is an acceptable stand-in for real per-user auth in this prototype.",
                ],
                "open_questions": [
                    "Should DELETE ownership be per-user in a future iteration, not a single shared token?",
                    "Should the target-URL denylist also block DNS names that resolve to internal IPs at "
                    "request time (full SSRF protection), or is a static host/IP-literal check sufficient for now?",
                ],
                "sub_requirements": [
                    "Confirm rate limiting is wired to POST /api/urls and GET /{code} (already true from Task 9).",
                    "Reject shortening targets that point at loopback/private/link-local hosts "
                    "(open-redirect/SSRF-lite guard).",
                    "Require a bearer token header on DELETE /api/urls/{code}.",
                ],
            },
            notes=(
                "Normalized ambiguous requirement 'make it more secure' into 3 concrete "
                "sub-requirements; human approval required before design proceeds."
            ),
        )


class AmbiguousDesignExecutor(StageExecutor):
    name = "ambiguous_design"

    def run(self, context) -> StageResult:
        approved = context.latest("requirements")
        sub_reqs = approved.outputs.get("sub_requirements", []) if approved else []
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "task_plan": [
                    "Extend validation.py: reject loopback/private/link-local target hosts.",
                    "Add OWNER_TOKEN to config.py.",
                    "Require X-Owner-Token header on DELETE /api/urls/{code}, else 403.",
                ],
                "approved_sub_requirements": sub_reqs,
                "risks": ["Static shared token is a stopgap, not real per-user authorization."],
            },
            notes="Design derived from the human-approved normalized requirement.",
        )


class AmbiguousImplementationExecutor(StageExecutor):
    name = "ambiguous_implementation"

    def __init__(self, target_dir: Path):
        self.target_dir = target_dir

    def run(self, context) -> StageResult:
        src = self.target_dir / "src" / "urlshortener"
        (src / "domain" / "validation.py").write_text(VALIDATION_V3)
        (src / "config.py").write_text(CONFIG_V2)
        (src / "api" / "urls.py").write_text(URLS_ROUTER_SECURE)
        (self.target_dir / "tests" / "urlshortener" / "test_security_hardening.py").write_text(
            TEST_SECURITY_HARDENING
        )
        return StageResult(
            status=StageStatus.PASSED,
            outputs={
                "files_changed": [
                    "src/urlshortener/domain/validation.py",
                    "src/urlshortener/config.py",
                    "src/urlshortener/api/urls.py",
                    "tests/urlshortener/test_security_hardening.py",
                ],
                "schema_change": "none",
                "new_dependencies": [],
                "diff_preview": "reject loopback/private targets; require X-Owner-Token on DELETE",
            },
            notes="Implemented the 3 approved sub-requirements.",
        )


class AmbiguousDocumentationExecutor(StageExecutor):
    name = "ambiguous_documentation"

    def __init__(self, target_dir: Path):
        self.target_dir = target_dir

    def run(self, context) -> StageResult:
        docs_dir = self.target_dir / "docs"
        docs_dir.mkdir(parents=True, exist_ok=True)
        doc_path = docs_dir / "api-changes.md"
        section = (
            "\\n## Security hardening (ambiguous scenario)\\n\\n"
            "The raw requirement \\"make it more secure\\" was normalized (with human approval) "
            "into three sub-requirements: confirmed rate limiting coverage, an open-redirect/"
            "SSRF-lite guard rejecting loopback/private/link-local shorten targets, and a "
            "bearer-token check (`X-Owner-Token`) on `DELETE /api/urls/{code}`. Per-user "
            "authentication remains out of scope — see docs/testing-and-tradeoffs.md.\\n"
        )
        existing = doc_path.read_text() if doc_path.exists() else "# API Changes\\n"
        doc_path.write_text(existing + section)
        return StageResult(
            status=StageStatus.PASSED,
            outputs={"files_changed": ["docs/api-changes.md"]},
            notes="Documented the security hardening changes and their limitations.",
        )


def build_workflow(target_dir: Path | None = None) -> Workflow:
    target_dir = target_dir or REPO_ROOT
    requirements = StageNode(
        id="requirements", executor=AmbiguousSecurityRequirementsExecutor(), requires_approval=True,
    )
    design = StageNode(id="design", executor=AmbiguousDesignExecutor(), depends_on=["requirements"])
    implementation = StageNode(
        id="implementation", executor=AmbiguousImplementationExecutor(target_dir), depends_on=["design"],
    )
    unit_tests = StageNode(
        id="unit_tests",
        executor=RunPytestExecutor(target_dir, "tests/urlshortener/test_security_hardening.py"),
        depends_on=["implementation"],
    )
    documentation = StageNode(
        id="documentation", executor=AmbiguousDocumentationExecutor(target_dir), depends_on=["implementation"],
    )
    release_readiness = StageNode(
        id="release_readiness",
        executor=ReleaseReadinessExecutor(),
        depends_on=["unit_tests", "documentation"],
        exit_gate=combine_guardrails(destructive_migration_requires_approval, test_coverage_threshold(1.0)),
        requires_approval=True,
    )
    return Workflow(
        "ambiguous", [requirements, design, implementation, unit_tests, documentation, release_readiness]
    )
```

Replace `scenarios/__init__.py`:

```python
# scenarios/__init__.py
from scenarios.greenfield_custom_alias import build_workflow as greenfield
from scenarios.brownfield_click_counter_fix import build_workflow as brownfield
from scenarios.ambiguous_security_hardening import build_workflow as ambiguous

SCENARIO_REGISTRY: dict = {
    "greenfield": greenfield,
    "brownfield": brownfield,
    "ambiguous": ambiguous,
}
```

- [ ] **Step 4: Run the entire test suite**

Run: `pytest -v`
Expected: PASS — every test in `tests/urlshortener`, `tests/orchestrator`, and `tests/scenarios` (the one intentional exception is the strict `xfail` in `test_concurrency_clicks.py` within `tests/urlshortener`, which reports `XFAIL`, not `PASS` — that is correct and expected).

- [ ] **Step 5: Commit**

```bash
git add scenarios/ambiguous_security_hardening.py scenarios/__init__.py \
        tests/scenarios/test_ambiguous_scenario.py
git commit -m "feat: ambiguous scenario — normalize 'make it more secure' behind a human approval gate"
```

---

## Task 25: Architecture, Setup, and Testing/Trade-off Documentation

**Files:**
- Create: `docs/architecture.md`
- Create: `docs/setup.md`
- Create: `docs/testing-and-tradeoffs.md`

**Interfaces:** None — pure documentation, written against the system as it exists after Task 24.

- [ ] **Step 1: Write `docs/architecture.md`**

```markdown
# Architecture Overview

Two independent packages plus a composition layer, per
`docs/superpowers/specs/2026-08-16-agentic-url-shortener-design.md`.

## `src/urlshortener` — the product

- `domain/` — pure logic: `codes.py` (base62 generation + collision retry),
  `validation.py` (URL/alias/expiry rules — evolves per scenario, see below),
  `ratelimit.py` (token-bucket rate limiter).
- `repository/` — SQLite access: `db.py` (connection + versioned migration
  runner), `urls_repo.py`, `clicks_repo.py`.
- `api/` — FastAPI routers: `urls.py`, `redirect.py`, `analytics.py`,
  `health.py`, wired together in `main.py::create_app`.

## `src/orchestrator` — the engine

- `executor.py` / `context.py` — the vocabulary every stage speaks:
  `StageExecutor.run(context) -> StageResult`, and an append-only,
  versioned `RunContext` that gives every stage decision lineage.
- `graph.py` — `StageNode` (entry/exit gates, retry policy, fallback,
  rollback, `requires_approval`) and `Workflow` (a validated DAG).
- `engine.py` — the scheduler: dispatches ready nodes (dependencies
  satisfied) in parallel via a thread pool, applies retry/backoff,
  falls back, rolls back and blocks downstream on unrecoverable
  failure, pauses at approval checkpoints, honors safe-stop, and
  triggers re-planning (`replan.py`) whenever a stage produces a new
  context version.
- `store.py` / `events.py` — every transition is persisted
  (`runs/<run_id>/state.json`) and logged (`runs/<run_id>/events.jsonl`)
  before the engine proceeds, so a run can be inspected or resumed from
  a separate process.
- `guardrails.py` / `policy.py` — composable rules (secrets, destructive
  migrations, new dependencies, test coverage) used as `exit_gate`s.
- `metrics.py` — success rate, retry/rollback frequency, MTTR, latency,
  computed from the event log.
- `cli.py` — the `orchestrate` command; the one place allowed to import
  both `orchestrator` and `scenarios` (the engine itself never imports
  `scenarios` or `urlshortener`).

## `scenarios/` — what ties them together

Each scenario (`greenfield_custom_alias.py`, `brownfield_click_counter_fix.py`,
`ambiguous_security_hardening.py`) builds a `Workflow` of the same shape —
`requirements -> design -> implementation -> [unit_tests, documentation] ->
release_readiness` — wired to deterministic `StageExecutor`s that read and
write real files under a `target_dir`. `scenarios/common.py` holds the
reusable pieces: `copy_urlshortener_source` (isolates a scenario onto a
disposable copy of the codebase), `RunPytestExecutor` (a real subprocess
`pytest` run), `grep_impacted_files` (codebase-impact scanning), and
`ReleaseReadinessExecutor` (aggregates upstream outputs for the guardrail
`exit_gate`).

## Data flow

```
requirement text
     |
     v
[requirements] -> NormalizedRequirement (context v1)
     |
     v
[design] -> TaskPlan, reads requirements v1 (+ codebase impact scan if brownfield)
     |
     v
[implementation] -> writes real files, records diff summary
     |
     +---------------+
     v                v
[unit_tests]    [documentation]   <- parallel, both depend on implementation
     |                |
     +-------+--------+           <- sync point
             v
     [release_readiness] -> guardrail checks, human approval, go/no-go
```

If `requirements` is rejected and re-run, its context version bumps; the
engine's `invalidate_stale` walk (via `replan.py`) marks every downstream
node whose recorded `input_versions` are now stale back to `pending`, so
only the affected subgraph re-executes — see `tests/orchestrator/test_replan.py`.

## Key decisions

- **Deterministic executors, not live LLM calls** — every scenario executor
  is plain Python with a fixed transformation. The `StageExecutor` interface
  is the seam where a real model call would plug in; this prototype does not
  wire one, for reproducibility and zero-cost runs (see
  `docs/testing-and-tradeoffs.md`).
- **Whole-file rewrites, not line-level patches** — each scenario's
  implementation executor writes complete new file contents from a known
  string constant rather than diffing/patching. Simpler and fully
  deterministic, at the cost of the three scenarios being independent
  demonstrations against a fresh copy of the codebase rather than
  composable changes stacked on the same working tree — documented in
  Task 26 and `docs/testing-and-tradeoffs.md`.
- **Threaded scheduler, JSON-file persistence** — sufficient for a
  single-process prototype with a handful of parallel branches; not
  horizontally scalable (would need a durable queue for that).
```

- [ ] **Step 2: Write `docs/setup.md`**

```markdown
# Setup

## Prerequisites

- Python 3.12+
- `grep` (used by the brownfield scenario's codebase-impact scan; present
  on macOS/Linux by default)

## Install

```bash
git clone <this-repo>
cd agentic-url-shortener
python3.12 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```

## Run the test suite

```bash
pytest
```

Expect one `XFAIL` (`tests/urlshortener/test_concurrency_clicks.py`) —
that is the documented, known click-counter race the brownfield scenario
fixes; see Task 11 and Task 23 of the implementation plan.

## Run the URL shortener API

```bash
uvicorn urlshortener.main:app --reload
```

Then, in another terminal:

```bash
curl -X POST localhost:8000/api/urls -H 'content-type: application/json' \
  -d '{"url": "https://example.com"}'
curl -i localhost:8000/<code-from-above>
curl localhost:8000/api/urls/<code>/analytics
```

## Run an orchestrator scenario

```bash
orchestrate run greenfield --run-id demo-greenfield
orchestrate status demo-greenfield
orchestrate approve demo-greenfield release_readiness --note "ship it"
orchestrate metrics --run demo-greenfield
```

The `ambiguous` scenario pauses at `requirements` first:

```bash
orchestrate run ambiguous --run-id demo-ambiguous
orchestrate status demo-ambiguous          # requirements: awaiting_approval
orchestrate approve demo-ambiguous requirements --note "scope approved"
orchestrate status demo-ambiguous          # design/implementation/... now ran
orchestrate approve demo-ambiguous release_readiness --note "ship it"
```

See `docs/scenarios/*.md` for full captured transcripts of all three
scenarios, including the exact commands and resulting run state.
```

- [ ] **Step 3: Write `docs/testing-and-tradeoffs.md`**

```markdown
# Testing Approach, Limitations, and Trade-offs

## Testing approach

- **`urlshortener`** — unit tests per domain module (`codes`, `validation`,
  `ratelimit`), integration tests per API router via FastAPI `TestClient`
  against a temp SQLite file, one full-lifecycle test
  (`test_lifecycle.py`), and one concurrency regression test
  (`test_concurrency_clicks.py`, strict `xfail` until the brownfield
  scenario's fix lands).
- **`orchestrator`** — every engine capability (linear execution, parallel
  sync points, gates, retry/backoff, fallback, rollback + downstream
  blocking, approval pause/resume, reject/revise, safe-stop, re-planning,
  guardrails, metrics) is tested with fake in-memory `StageExecutor`s
  (`tests/orchestrator/fakes.py`) — zero dependency on `urlshortener` or
  `scenarios`, proving the engine is a real generic mechanism.
- **`scenarios`** — each scenario has an integration test that runs the
  real workflow against a disposable copy of the codebase
  (`scenarios.common.copy_urlshortener_source`) and asserts the actual
  end state: files changed, the real `pytest` subprocess passed, the
  expected approval checkpoints were hit.

## Known limitations

- **No live LLM calls.** Every `StageExecutor` in every scenario is
  deterministic Python with a fixed transformation, chosen for
  reproducibility and zero API cost/key requirement in this prototype.
  `StageExecutor.run(context) -> StageResult` is the seam where a real
  model call would be substituted.
- **Scenarios are independent, not composable on one working tree.**
  Each scenario's implementation executor rewrites whole files from a
  fixed string constant derived from the *pristine* base codebase, not
  from whatever the previous scenario left behind. Running greenfield
  then ambiguous against the *same* real tree would have ambiguous's
  rewrite of `urls.py` silently drop greenfield's alias/expiry handling.
  Task 26 avoids this by running each scenario against its own fresh
  workspace copy rather than chaining them — a real multi-change rollout
  would need implementation executors that read-and-patch current file
  state (true diffing) instead of whole-file rewrites. Whole-file
  rewrites were chosen here for determinism and plan-review clarity, at
  this explicit cost.
- **Rate limiter and rollback snapshots are in-memory/coarse.** The
  `TokenBucketLimiter` is per-process; a multi-instance deployment would
  need a shared backend (Redis). Rollback is a plain file-snapshot
  mechanism, not git-integrated — simpler, but coarser than a real
  VCS-based rollback.
- **The `redirect` click write is synchronous, not backgrounded.** It is
  a handful of indexed SQLite statements (sub-millisecond); backgrounding
  it would make the click-count-immediately-after-redirect test flaky
  without an explicit wait, which is worse for a prototype than the
  small synchronous cost.
- **The ambiguous scenario's owner-token check is a static shared
  secret, not per-user auth.** Explicitly flagged as an open question in
  the scenario's own normalized requirement (`docs/scenarios/ambiguous.md`)
  — real authentication is out of scope for this prototype.
- **Single-process, thread-pool scheduler.** Sufficient for a handful of
  parallel branches in one process; not horizontally scalable — a
  production orchestrator would need a durable, multi-worker queue.

## Risk notes carried into the Final Engineering Summary

See `docs/final-summary.md` (Task 26) for how these limitations map to
concrete risk/trade-off statements alongside the actual captured scenario
runs.
```

- [ ] **Step 4: Commit**

```bash
git add docs/architecture.md docs/setup.md docs/testing-and-tradeoffs.md
git commit -m "docs: architecture overview, setup instructions, testing approach and trade-offs"
```

---

## Task 26: Execute All Three Scenarios For Real and Capture Results

**Files:**
- Create: `docs/scenarios/greenfield.md`
- Create: `docs/scenarios/brownfield.md`
- Create: `docs/scenarios/ambiguous.md`
- Create: `docs/final-summary.md`
- Create (generated by the commands below, then committed as sample evidence): `runs/demo-greenfield/`, `runs/demo-brownfield/`, `runs/demo-ambiguous/`

**Interfaces:** None new — this task exercises the `orchestrate` CLI (Task 21, with `--target-dir` from this same task) against fresh workspace copies (`scenarios.common.copy_urlshortener_source`, Task 22) and writes up the real output.

Per the trade-off recorded in Task 25, each scenario runs against its **own** fresh copy of the current codebase (not chained against the real repo or against each other) — this is what `--target-dir` is for.

- [ ] **Step 1: Create three workspace copies**

```bash
mkdir -p /tmp/agentic-demo
python3 -c "
from pathlib import Path
from scenarios.common import copy_urlshortener_source
for name in ('greenfield', 'brownfield', 'ambiguous'):
    copy_urlshortener_source(Path('/tmp/agentic-demo') / name)
    print('copied', name)
"
```

Expected: prints `copied greenfield`, `copied brownfield`, `copied ambiguous`.

- [ ] **Step 2: Run the brownfield scenario end to end**

```bash
orchestrate run brownfield --run-id demo-brownfield --target-dir /tmp/agentic-demo/brownfield
orchestrate status demo-brownfield
```

Expected: `design`/`implementation`/`unit_tests`/`documentation` all `passed`,
`release_readiness` `awaiting_approval`.

```bash
orchestrate approve demo-brownfield release_readiness --note "atomic increment verified by the concurrency test; ship it"
orchestrate status demo-brownfield
```

Expected: `release_readiness` `passed`.

- [ ] **Step 3: Run the greenfield scenario end to end**

```bash
orchestrate run greenfield --run-id demo-greenfield --target-dir /tmp/agentic-demo/greenfield
orchestrate approve demo-greenfield release_readiness --note "custom alias + expiry verified by new tests; ship it"
orchestrate status demo-greenfield
```

Expected: same pattern as Step 2 — everything `passed`.

- [ ] **Step 4: Run the ambiguous scenario end to end, including the requirements approval gate**

```bash
orchestrate run ambiguous --run-id demo-ambiguous --target-dir /tmp/agentic-demo/ambiguous
orchestrate status demo-ambiguous
```

Expected: `requirements` `awaiting_approval`, everything else `pending`.

```bash
orchestrate approve demo-ambiguous requirements --note "normalized scope approved: rate-limit confirmation, open-redirect guard, owner-token DELETE gate; per-user auth explicitly deferred"
orchestrate status demo-ambiguous
```

Expected: `design`/`implementation`/`unit_tests`/`documentation` now `passed`, `release_readiness` `awaiting_approval`.

```bash
orchestrate approve demo-ambiguous release_readiness --note "security hardening tests pass; ship it"
orchestrate status demo-ambiguous
```

- [ ] **Step 5: Copy the run artifacts into the repo as evidence, and print metrics**

```bash
mkdir -p runs
cp -r "$(python3 -c 'from orchestrator.cli import RUNS_DIR; print(RUNS_DIR)')"/demo-greenfield runs/
cp -r "$(python3 -c 'from orchestrator.cli import RUNS_DIR; print(RUNS_DIR)')"/demo-brownfield runs/
cp -r "$(python3 -c 'from orchestrator.cli import RUNS_DIR; print(RUNS_DIR)')"/demo-ambiguous runs/
orchestrate metrics
```

Expected: `orchestrate metrics` prints a JSON report with `"runs": 3` and
`per_run` entries for all three demo run IDs. Keep this JSON output — it
goes into `docs/final-summary.md` in Step 7.

- [ ] **Step 6: Write `docs/scenarios/greenfield.md`, `brownfield.md`, `ambiguous.md`**

For each scenario, write a doc with these sections, filled in from the
**actual** output captured in Steps 2-5 (not hypothetical text — paste
the real `orchestrate status` output and the real `context.latest(...)`
outputs, which you can print with, e.g.:
`python3 -c "from orchestrator.store import RunStore; from pathlib import Path; import json; s = RunStore(Path('runs')); print(json.dumps(s.load('demo-greenfield').context.to_dict(), indent=2))"`):

1. **Raw requirement** — the literal input text.
2. **Decomposition** — the `requirements` and `design` stage outputs (normalized requirement, task plan, risks/impacted files).
3. **Orchestration** — the DAG (list nodes and `depends_on`), and the actual node-by-node status transcript from `orchestrate status`, including the approval interaction (the exact `approve`/`note` commands run).
4. **Validation** — the `unit_tests` stage's real pass/fail counts and the `release_readiness` guardrail outcome.
5. **Risks and limitations** — carried from `docs/testing-and-tradeoffs.md`, specific to this scenario (e.g. ambiguous.md must include the two `open_questions` from the requirements executor's output).

- [ ] **Step 7: Write `docs/final-summary.md`**

```markdown
# Final Engineering Summary

## Plan and rationale

Built as two independent packages — a URL shortener service and a
domain-agnostic agentic orchestration engine — plus three scenarios that
demonstrate the engine driving real changes to the service's codebase.
See `docs/superpowers/specs/2026-08-16-agentic-url-shortener-design.md`
for the full design rationale and
`docs/superpowers/plans/2026-08-16-agentic-url-shortener.md` for the
task-by-task build order (27 tasks, TDD throughout, one commit per
green test).

## Artifacts

- Working prototype: `src/urlshortener`, `src/orchestrator`, `scenarios/`.
- Architecture: `docs/architecture.md`.
- Setup: `docs/setup.md`.
- Three real, captured scenario runs: `docs/scenarios/*.md`,
  backed by `runs/demo-greenfield/`, `runs/demo-brownfield/`,
  `runs/demo-ambiguous/`.
- Reliability metrics from the three real runs: [paste the JSON from
  Task 26 Step 5 here].

## Risks, trade-offs, and validation

See `docs/testing-and-tradeoffs.md` for the full list; the two most
consequential for a reviewer:

1. Scenarios are independent demonstrations (fresh workspace per
   scenario), not composable changes on one working tree — a real
   rollout needs patch-based, not whole-file-rewrite, implementation
   executors.
2. No live LLM is wired into any `StageExecutor` — this prototype
   demonstrates the orchestration mechanism (DAG, gates, retries,
   rollback, approval, replanning, metrics) with deterministic stage
   logic; a production version would substitute a real model call
   behind the same `StageExecutor.run(context) -> StageResult`
   interface.

## Assumptions

- SQLite is an acceptable datastore for this prototype's scale.
- A single shared bearer token is an acceptable stand-in for real
  per-user authorization on `DELETE`, pending real auth (flagged in the
  ambiguous scenario's own `open_questions`).
- Reviewers can run `pytest` and the `orchestrate` CLI locally — no
  external services or Docker required.

## Limitations

Carried in full from `docs/testing-and-tradeoffs.md`: no live LLM calls,
non-composable scenario demos, in-memory rate limiter, file-snapshot
(not git-integrated) rollback, synchronous click-write, static
owner-token auth, single-process scheduler.
```

- [ ] **Step 8: Commit**

```bash
git add docs/scenarios runs/demo-greenfield runs/demo-brownfield runs/demo-ambiguous docs/final-summary.md
git commit -m "docs: capture real orchestrator runs for all three scenarios; final engineering summary"
```

---

## Task 27: README and Full Verification Pass

**Files:**
- Create: `README.md`

**Interfaces:** None — final wiring and verification.

- [ ] **Step 1: Write `README.md`**

```markdown
# Agentic URL Shortener System

A URL shortener service built and evolved by a real agentic orchestration
engine, demonstrating end-to-end SDLC automation with controlled autonomy.

- **Product:** `src/urlshortener` — FastAPI + SQLite URL shortener with
  create/redirect/analytics/delete APIs, rate limiting, and collision-safe
  code generation.
- **Engine:** `src/orchestrator` — a domain-agnostic workflow engine with
  an explicit DAG, entry/exit gates, parallel branches with sync points,
  bounded retry/fallback/rollback/safe-stop, human approval checkpoints,
  dynamic re-planning, policy guardrails, and reliability metrics
  (success rate, retry/rollback frequency, MTTR, latency).
- **Scenarios:** `scenarios/` — greenfield (add custom alias + expiry),
  brownfield (fix a real click-counter race condition), and ambiguous
  ("make it more secure", normalized behind a human approval gate) — all
  driving real changes to the product codebase.

See:
- [docs/architecture.md](docs/architecture.md) — components, data flow, key decisions.
- [docs/setup.md](docs/setup.md) — install and run.
- [docs/testing-and-tradeoffs.md](docs/testing-and-tradeoffs.md) — testing approach, known limitations.
- [docs/scenarios/](docs/scenarios/) — real, captured runs of all three scenarios.
- [docs/final-summary.md](docs/final-summary.md) — plan/rationale, artifacts, risks, assumptions, limitations.
- [docs/superpowers/specs/2026-08-16-agentic-url-shortener-design.md](docs/superpowers/specs/2026-08-16-agentic-url-shortener-design.md) — the original design spec.
- [docs/superpowers/plans/2026-08-16-agentic-url-shortener.md](docs/superpowers/plans/2026-08-16-agentic-url-shortener.md) — the task-by-task implementation plan this was built from.

## Quick start

```bash
python3.12 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
pytest
uvicorn urlshortener.main:app --reload &
orchestrate run greenfield --run-id demo
orchestrate status demo
orchestrate approve demo release_readiness --note "ship it"
```
```

- [ ] **Step 2: Run the full verification pass**

```bash
pytest -v
```

Expected: every test passes except the one strict `XFAIL`
(`tests/urlshortener/test_concurrency_clicks.py::test_concurrent_clicks_are_not_lost`,
which is correct and intentional per Task 11).

```bash
orchestrate metrics
```

Expected: prints the aggregate JSON metrics report across every run
persisted under `runs/` (including the three demo runs from Task 26),
with `avg_success_rate` reflecting that every gated node was eventually
approved.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: top-level README tying together setup, architecture, and scenario docs"
```
