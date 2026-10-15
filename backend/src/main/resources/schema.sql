CREATE TABLE IF NOT EXISTS materials (id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, material_id TEXT NOT NULL REFERENCES materials(id) ON DELETE CASCADE, name TEXT NOT NULL, storage_name TEXT NOT NULL, content TEXT NOT NULL, truncated INTEGER NOT NULL, created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS idx_documents_material ON documents(material_id);
CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS predictions (id TEXT PRIMARY KEY, resume_id TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS idx_predictions_resume ON predictions(resume_id,created_at);
CREATE TABLE IF NOT EXISTS reports (session_id TEXT PRIMARY KEY REFERENCES sessions(id), payload TEXT NOT NULL);
