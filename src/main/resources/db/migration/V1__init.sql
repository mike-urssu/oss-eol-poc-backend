CREATE TABLE opensource (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug          TEXT        NOT NULL UNIQUE,
    name          TEXT        NOT NULL,
    homepage_url  TEXT,
    description   TEXT,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE source_registry (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opensource_id  BIGINT      NOT NULL REFERENCES opensource(id),
    url            TEXT        NOT NULL,
    collector_type TEXT        NOT NULL CHECK (collector_type IN ('API', 'WEB', 'GITHUB')),
    parser_key     TEXT,
    trust_level    TEXT        NOT NULL CHECK (trust_level IN ('OFFICIAL', 'SECONDARY')),
    config         JSONB       NOT NULL DEFAULT '{}',
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (opensource_id, url)
);

CREATE TABLE oss_version (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opensource_id          BIGINT      NOT NULL REFERENCES opensource(id),
    version                TEXT        NOT NULL,
    latest_patch_version   TEXT,
    release_date           DATE,
    release_date_precision TEXT CHECK (release_date_precision IN ('DAY', 'MONTH', 'YEAR')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (opensource_id, version),
    CHECK ((release_date IS NULL) = (release_date_precision IS NULL))
);

CREATE TABLE oss_lifecycle (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id                  BIGINT      NOT NULL UNIQUE REFERENCES oss_version(id),
    eol_date                    DATE,
    eol_date_precision          TEXT CHECK (eol_date_precision IN ('DAY', 'MONTH', 'YEAR')),
    security_eol_date           DATE,
    security_eol_date_precision TEXT CHECK (security_eol_date_precision IN ('DAY', 'MONTH', 'YEAR')),
    support_declared            TEXT CHECK (support_declared IN ('SUPPORTED', 'EOL')),
    last_verified_at            TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((eol_date IS NULL) = (eol_date_precision IS NULL)),
    CHECK ((security_eol_date IS NULL) = (security_eol_date_precision IS NULL))
);

CREATE TABLE eol_collection (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opensource_id     BIGINT      NOT NULL REFERENCES opensource(id),
    source_id         BIGINT      NOT NULL REFERENCES source_registry(id),
    trigger           TEXT        NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    status            TEXT CHECK (status IN ('SUCCESS', 'FAILED')),   -- NULL = 진행 중
    skipped_unchanged BOOLEAN     NOT NULL DEFAULT FALSE,
    error_code        TEXT,
    error_message     TEXT,
    http_status       INT,
    raw_content       TEXT,
    content_text      TEXT,
    content_hash      TEXT,
    prompt_version    TEXT,
    ai_model          TEXT,
    ai_input_tokens   BIGINT,
    ai_output_tokens  BIGINT,
    candidate_count   INT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ
);
CREATE INDEX ON eol_collection (source_id, id DESC);
CREATE INDEX ON eol_collection (opensource_id, id DESC);

CREATE TABLE eol_candidate (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    collection_id               BIGINT      NOT NULL REFERENCES eol_collection(id),
    opensource_id               BIGINT      NOT NULL REFERENCES opensource(id),
    source_id                   BIGINT      NOT NULL REFERENCES source_registry(id),
    version                     TEXT        NOT NULL,
    latest_patch_version        TEXT,
    release_date                DATE,
    release_date_precision      TEXT,
    eol_date                    DATE,
    eol_date_precision          TEXT,
    security_eol_date           DATE,
    security_eol_date_precision TEXT,
    support_declared            TEXT,
    evidence                    JSONB       NOT NULL,   -- [{field, value, quote}]
    extraction_method           TEXT        NOT NULL CHECK (extraction_method IN ('AI', 'RULE')),
    validation_status           TEXT        NOT NULL CHECK (validation_status IN ('APPROVED', 'REVIEW_REQUIRED', 'REJECTED')),
    validation_reasons          JSONB       NOT NULL DEFAULT '[]',
    reviewed_by                 TEXT,
    review_comment              TEXT,
    reviewed_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON eol_candidate (opensource_id, version, id DESC);
CREATE INDEX ON eol_candidate (validation_status) WHERE reviewed_at IS NULL;

CREATE TABLE eol_evidence (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id        BIGINT      NOT NULL REFERENCES oss_version(id),
    field             TEXT        NOT NULL CHECK (field IN ('RELEASE_DATE', 'LATEST_PATCH_VERSION', 'EOL_DATE', 'SECURITY_EOL_DATE', 'SUPPORT')),
    value             TEXT        NOT NULL,   -- 표시 형식 (예: 2027-11, 2028-11-09, EOL)
    source_id         BIGINT      NOT NULL REFERENCES source_registry(id),
    source_url        TEXT        NOT NULL,
    quote             TEXT        NOT NULL,
    extraction_method TEXT        NOT NULL CHECK (extraction_method IN ('AI', 'RULE')),
    collection_id     BIGINT      NOT NULL REFERENCES eol_collection(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (version_id, field, source_id, value)
);

CREATE TABLE eol_change_history (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id    BIGINT      NOT NULL REFERENCES oss_version(id),
    field         TEXT        NOT NULL,
    old_value     TEXT,
    new_value     TEXT,
    candidate_id  BIGINT      NOT NULL REFERENCES eol_candidate(id),
    changed_by    TEXT        NOT NULL,
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON eol_change_history (version_id, changed_at DESC);
