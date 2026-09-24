-- 컴포넌트가 하나도 없는 커밋도 "스냅샷 있음" 으로 읽히게 커밋 자체를 따로 남긴다
CREATE TABLE IF NOT EXISTS snapshot (
    commit_sha TEXT PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS component (
    commit_sha TEXT NOT NULL,
    fqn        TEXT NOT NULL,
    layer      TEXT NOT NULL,
    file       TEXT NOT NULL,
    PRIMARY KEY (commit_sha, fqn)
);

CREATE TABLE IF NOT EXISTS node (
    commit_sha TEXT NOT NULL,
    id         TEXT NOT NULL,
    fqn        TEXT NOT NULL,
    method     TEXT NOT NULL,
    layer      TEXT NOT NULL,
    endpoint   TEXT,
    body_hash  TEXT NOT NULL,
    file       TEXT NOT NULL,
    PRIMARY KEY (commit_sha, id)
);

CREATE TABLE IF NOT EXISTS edge (
    commit_sha TEXT NOT NULL,
    from_id    TEXT NOT NULL,
    to_id      TEXT NOT NULL,
    file       TEXT NOT NULL,
    PRIMARY KEY (commit_sha, from_id, to_id)
);
