-- Serialize refresh rotation, append and revocation even before a family has any credentials.
-- Existing sessions remain unchanged; a lock row is created lazily when the family is used.
CREATE TABLE refresh_session_family_locks (
    family_id UUID PRIMARY KEY,
    revoked_at TIMESTAMP WITH TIME ZONE
);
