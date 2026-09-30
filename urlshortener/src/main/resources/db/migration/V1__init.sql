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
