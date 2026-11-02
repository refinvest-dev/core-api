-- Adopt the existing Hibernate schema without rewriting subscriptions.
CREATE TABLE IF NOT EXISTS subscriptions (
    member_id BIGINT PRIMARY KEY,
    tier VARCHAR(20) NOT NULL
);
