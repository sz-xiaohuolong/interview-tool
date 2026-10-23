CREATE TABLE IF NOT EXISTS materials (id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, material_id TEXT NOT NULL REFERENCES materials(id) ON DELETE CASCADE, name TEXT NOT NULL, storage_name TEXT NOT NULL, content TEXT NOT NULL, truncated INTEGER NOT NULL, created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS idx_documents_material ON documents(material_id);
CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS predictions (id TEXT PRIMARY KEY, resume_id TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS idx_predictions_resume ON predictions(resume_id,created_at);
CREATE TABLE IF NOT EXISTS reports (session_id TEXT PRIMARY KEY REFERENCES sessions(id), payload TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS project_repositories (material_id TEXT PRIMARY KEY REFERENCES materials(id) ON DELETE CASCADE, payload TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS job_applications (
    id TEXT PRIMARY KEY,
    company TEXT NOT NULL CHECK(length(company) BETWEEN 1 AND 200),
    position TEXT NOT NULL CHECK(length(position) BETWEEN 1 AND 200),
    location TEXT NOT NULL DEFAULT '', platform TEXT NOT NULL DEFAULT '',
    url TEXT NOT NULL DEFAULT '', jd TEXT NOT NULL DEFAULT '',
    job_type TEXT NOT NULL DEFAULT 'OTHER' CHECK(job_type IN ('CAMPUS','INTERNSHIP','SUMMER_INTERNSHIP','FULL_TIME','OTHER')),
    status TEXT NOT NULL DEFAULT 'TO_APPLY' CHECK(status IN ('TO_APPLY','APPLIED','ASSESSMENT','INTERVIEW','OFFER','REJECTED','WITHDRAWN')),
    applied_date TEXT, next_action_date TEXT, interview_at TEXT,
    interview_note TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
    archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_job_applications_list ON job_applications(archived,status,next_action_date);
CREATE TABLE IF NOT EXISTS job_search_favorites (id TEXT PRIMARY KEY, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS job_search_favorite_sources (source_key TEXT PRIMARY KEY, favorite_id TEXT NOT NULL REFERENCES job_search_favorites(id) ON DELETE CASCADE, snapshot_json TEXT NOT NULL, saved_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS idx_job_search_favorite_sources_favorite ON job_search_favorite_sources(favorite_id);
CREATE TABLE IF NOT EXISTS job_search_jd_links (source_key TEXT PRIMARY KEY, material_id TEXT NOT NULL UNIQUE REFERENCES materials(id) ON DELETE CASCADE, source_json TEXT NOT NULL, source_content_hash TEXT NOT NULL);
