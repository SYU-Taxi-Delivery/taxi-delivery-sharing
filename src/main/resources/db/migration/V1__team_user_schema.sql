-- Baseline for a NEW database: matches the team's existing user entities.
-- Existing user databases must be inspected and explicitly baselined at version 1.
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    student_no VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL,
    school_email VARCHAR(100) NOT NULL UNIQUE,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE user_auth (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    CONSTRAINT uq_user_auth_provider UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_user_auth_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE local_credential (
    user_id BIGINT NOT NULL PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    failed_login_count INT NOT NULL,
    locked_until DATETIME(6) NULL,
    password_updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_local_credential_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE refresh_token (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at DATETIME(6) NOT NULL,
    revoked BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
