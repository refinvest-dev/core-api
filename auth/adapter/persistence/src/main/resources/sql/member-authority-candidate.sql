-- Explicit candidate only: not a Flyway migration or an automatic Member backfill.
CREATE TABLE auth_authority_policy (
    singleton SMALLINT PRIMARY KEY CHECK (singleton = 1),
    schema_version SMALLINT NOT NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE auth_member_access (
    member_id BIGINT PRIMARY KEY REFERENCES members(id) ON DELETE RESTRICT,
    allowed BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0)
);
CREATE TABLE auth_authority_callers (
    caller_id VARCHAR(64) PRIMARY KEY,
    ingress BOOLEAN NOT NULL,
    authority BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0)
);
CREATE TABLE auth_authority_keys (
    issuer VARCHAR(512) NOT NULL,
    kid VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    PRIMARY KEY (issuer, kid)
);
CREATE TABLE auth_authority_revocations (
    issuer VARCHAR(512) NOT NULL,
    jti_sha256 CHAR(64) NOT NULL CHECK (jti_sha256 ~ '^[0-9a-f]{64}$'),
    retain_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (issuer, jti_sha256)
);
