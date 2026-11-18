CREATE TABLE users (
    id BINARY(16) NOT NULL PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL DEFAULT '',
    primary_email VARCHAR(320) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    onboarding_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY user_email_uk (primary_email),
    CHECK (status IN ('active', 'deleted')),
    CHECK (onboarding_status IN ('pending', 'completed'))
) ENGINE = InnoDB;

CREATE TABLE auth_identities (
    id BINARY(16) NOT NULL PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    email VARCHAR(320) NULL,
    email_verified BOOLEAN NOT NULL,
    linked_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY auth_identity_subject_uk (provider, subject),
    UNIQUE KEY auth_identity_user_provider_uk (user_id, provider),
    KEY auth_identity_email_idx (email),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE = InnoDB;

CREATE TABLE auth_sessions (
    id BINARY(16) NOT NULL PRIMARY KEY,
    user_id BINARY(16) NULL,
    token_hash BINARY(32) NOT NULL,
    csrf_hash BINARY(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY auth_session_token_uk (token_hash),
    KEY auth_session_user_idx (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    CHECK (state IN ('anonymous', 'active', 'revoked'))
) ENGINE = InnoDB;

CREATE TABLE auth_challenges (
    id BINARY(16) NOT NULL PRIMARY KEY,
    email_normalized VARCHAR(320) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    session_id BINARY(16) NOT NULL,
    user_id BINARY(16) NULL,
    code_hash BINARY(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY auth_challenge_email_idx (email_normalized, created_at),
    FOREIGN KEY (session_id) REFERENCES auth_sessions(id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    CHECK (purpose IN ('login', 'reauth')),
    CHECK (state IN ('pending', 'consumed', 'blocked'))
) ENGINE = InnoDB;

CREATE TABLE auth_transactions (
    id BINARY(16) NOT NULL PRIMARY KEY,
    session_id BINARY(16) NOT NULL,
    user_id BINARY(16) NULL,
    type VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NULL,
    secret_hash BINARY(32) NOT NULL,
    payload JSON NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at DATETIME(6) NOT NULL,
    UNIQUE KEY auth_transaction_secret_uk (secret_hash),
    FOREIGN KEY (session_id) REFERENCES auth_sessions(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE = InnoDB;

CREATE TABLE user_consents (
    id BINARY(16) NOT NULL PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    term_key VARCHAR(64) NOT NULL,
    term_version VARCHAR(64) NOT NULL,
    accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    request_id VARCHAR(64) NOT NULL,
    UNIQUE KEY user_consent_uk (user_id, term_key, term_version),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE = InnoDB;

CREATE TABLE account_deletion_requests (
    id BINARY(16) NOT NULL PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    state VARCHAR(32) NOT NULL,
    completed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE = InnoDB;

ALTER TABLE projects ADD COLUMN owner_user_id BINARY(16) NULL,
    ADD CONSTRAINT project_owner_fk FOREIGN KEY (owner_user_id) REFERENCES users(id);

CREATE TABLE auth_rate_limits (
    bucket_hash BINARY(32) NOT NULL PRIMARY KEY,
    window_started_at DATETIME(6) NOT NULL,
    requests INT UNSIGNED NOT NULL
) ENGINE = InnoDB;
