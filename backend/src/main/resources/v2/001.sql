CREATE TABLE v2_schema_migrations (version INTEGER PRIMARY KEY CHECK(version>=1), checksum TEXT NOT NULL, applied_at TEXT NOT NULL);
CREATE TABLE v2_operations (
 id TEXT PRIMARY KEY, kind TEXT NOT NULL, scope_key TEXT NOT NULL, resource_type TEXT NOT NULL, resource_id TEXT NOT NULL,
 request_id TEXT NOT NULL, input_hash TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','INTERRUPTED')),
 generation INTEGER NOT NULL CHECK(generation>=0), expected_revision INTEGER NOT NULL CHECK(expected_revision>=0),
 payload TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 UNIQUE(kind,scope_key,request_id)
);
CREATE INDEX v2_operations_state ON v2_operations(state,updated_at,id);
CREATE TABLE v2_review_items (
 id TEXT PRIMARY KEY, source_session_id TEXT NOT NULL REFERENCES sessions(id), question_index INTEGER NOT NULL CHECK(question_index>=0),
 status TEXT NOT NULL CHECK(status IN ('PENDING_CONFIRMATION','DUE','IN_PROGRESS','WAITING_EVIDENCE','RESOLVED','ARCHIVED')),
 next_review_at TEXT, revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), payload TEXT NOT NULL,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(source_session_id,question_index)
);
CREATE INDEX v2_review_due ON v2_review_items(status,next_review_at,created_at,id);
CREATE TABLE v2_practice_cases (
 id TEXT PRIMARY KEY, family_id TEXT NOT NULL, case_version INTEGER NOT NULL CHECK(case_version>=1),
 source_kind TEXT NOT NULL CHECK(source_kind IN ('SESSION','STORY')), source_id TEXT NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), payload TEXT NOT NULL, created_at TEXT NOT NULL, UNIQUE(family_id,case_version)
);
CREATE INDEX v2_cases_source ON v2_practice_cases(source_kind,source_id,created_at,id);
CREATE TABLE v2_practice_attempts (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES v2_practice_cases(id), ordinal INTEGER NOT NULL CHECK(ordinal>=0),
 kind TEXT NOT NULL CHECK(kind IN ('ORIGINAL_SNAPSHOT','NEW_ANSWER')),
 status TEXT NOT NULL CHECK(status IN ('IMPORTED','DRAFT','SUBMITTED','EVALUATING','READY','FAILED','INTERRUPTED')),
 answer_text TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), payload TEXT NOT NULL,
 created_at TEXT NOT NULL, submitted_at TEXT, UNIQUE(case_id,ordinal)
);
CREATE INDEX v2_attempts_case ON v2_practice_attempts(case_id,created_at,id);
CREATE TABLE v2_practice_evaluation_groups (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES v2_practice_cases(id),
 status TEXT NOT NULL CHECK(status IN ('RUNNING','READY','FAILED','INTERRUPTED')),
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), payload TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE INDEX v2_groups_case ON v2_practice_evaluation_groups(case_id,created_at,id);
CREATE TABLE v2_skill_events (
 id TEXT PRIMARY KEY, skill_id TEXT, review_item_id TEXT REFERENCES v2_review_items(id),
 attempt_id TEXT REFERENCES v2_practice_attempts(id), source_key TEXT NOT NULL UNIQUE,
 event_type TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE INDEX v2_skill_timeline ON v2_skill_events(skill_id,created_at,id);
CREATE TABLE v2_practice_comparisons (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES v2_practice_cases(id),
 left_attempt_id TEXT NOT NULL REFERENCES v2_practice_attempts(id), right_attempt_id TEXT NOT NULL REFERENCES v2_practice_attempts(id),
 status TEXT NOT NULL CHECK(status IN ('TEXT_ONLY','DIFFERENT_CONTEXT','RUNNING','READY','FAILED','INTERRUPTED')),
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), payload TEXT NOT NULL, created_at TEXT NOT NULL,
 CHECK(left_attempt_id<>right_attempt_id)
);
CREATE TABLE v2_jd_diagnostics (
 id TEXT PRIMARY KEY, family_id TEXT NOT NULL, diagnostic_version INTEGER NOT NULL CHECK(diagnostic_version>=1),
 status TEXT NOT NULL CHECK(status IN ('RUNNING','READY','CONFIRMED','FAILED','INTERRUPTED')),
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), payload TEXT NOT NULL, created_at TEXT NOT NULL,
 UNIQUE(family_id,diagnostic_version)
);
CREATE TABLE v2_sprints (
 id TEXT PRIMARY KEY, diagnostic_id TEXT NOT NULL REFERENCES v2_jd_diagnostics(id),
 status TEXT NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), payload TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX v2_sprints_status ON v2_sprints(status,updated_at,id);
CREATE TABLE v2_stories (
 id TEXT PRIMARY KEY, template_kind TEXT NOT NULL CHECK(template_kind IN ('INTRO','STAR')),
 status TEXT NOT NULL CHECK(status IN ('DRAFT','CONFIRMED','ARCHIVED')),
 current_version INTEGER NOT NULL CHECK(current_version>=1), revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0),
 payload TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE v2_story_versions (
 id TEXT PRIMARY KEY, story_id TEXT NOT NULL REFERENCES v2_stories(id), version INTEGER NOT NULL CHECK(version>=1),
 status TEXT NOT NULL CHECK(status IN ('DRAFT','CONFIRMED')), payload TEXT NOT NULL, created_at TEXT NOT NULL,
 UNIQUE(story_id,version)
);
CREATE TABLE v2_design_sessions (
 id TEXT PRIMARY KEY, scenario_id TEXT NOT NULL CHECK(scenario_id IN ('ORDER_SERVICE','KNOWLEDGE_AGENT')), scenario_version TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('IN_PROGRESS','COMPLETED','ENDED_EARLY')),
 current_stage TEXT NOT NULL CHECK(current_stage IN ('CLARIFY','CONSTRAINTS','ENTITIES_API','ARCHITECTURE','TRADEOFFS')),
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), payload TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX v2_design_history ON v2_design_sessions(state,updated_at,id);
CREATE TABLE v2_training_links (
 id TEXT PRIMARY KEY, source_kind TEXT NOT NULL CHECK(source_kind IN ('REVIEW','JD')), source_id TEXT NOT NULL,
 session_id TEXT UNIQUE REFERENCES sessions(id), revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0),
 payload TEXT NOT NULL, created_at TEXT NOT NULL
);
