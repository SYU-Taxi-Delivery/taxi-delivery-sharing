-- Recruitment v4 schema; reuses existing USERS. UTC DATETIME(6).
CREATE TABLE post (
    id BIGINT NOT NULL AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    title VARCHAR(60) NOT NULL,
    content TEXT NOT NULL,
    capacity INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    reference_time DATETIME(6) NOT NULL,
    communication_url VARCHAR(500) NULL,
    departure VARCHAR(100) NULL,
    destination VARCHAR(100) NULL,
    restaurant_name VARCHAR(100) NULL,
    pickup_location VARCHAR(100) NULL,
    order_guide VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    deleted_by BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_author FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT fk_post_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id),
    CONSTRAINT ck_post_type CHECK (type IN ('TAXI','DELIVERY')),
    CONSTRAINT ck_post_status CHECK (status IN ('OPEN','FULL','CLOSED','CANCELLED','FINISHED','REMOVED')),
    CONSTRAINT ck_post_capacity CHECK ((type='TAXI' AND capacity BETWEEN 2 AND 4) OR (type='DELIVERY' AND capacity BETWEEN 2 AND 10)),
    CONSTRAINT ck_post_fields CHECK (
        (type='TAXI' AND departure IS NOT NULL AND destination IS NOT NULL AND restaurant_name IS NULL AND pickup_location IS NULL AND order_guide IS NULL)
        OR (type='DELIVERY' AND restaurant_name IS NOT NULL AND pickup_location IS NOT NULL AND departure IS NULL AND destination IS NULL)),
    CONSTRAINT ck_post_places CHECK (
        (type='TAXI' AND CHAR_LENGTH(TRIM(departure)) > 0 AND CHAR_LENGTH(TRIM(destination)) > 0)
        OR (type='DELIVERY' AND CHAR_LENGTH(TRIM(restaurant_name)) > 0 AND CHAR_LENGTH(TRIM(pickup_location)) > 0)),
    CONSTRAINT ck_post_text CHECK (CHAR_LENGTH(TRIM(title)) > 0 AND CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_post_reference_minute CHECK (SECOND(reference_time)=0 AND MICROSECOND(reference_time)=0),
    INDEX ix_post_author_reference (author_id,reference_time,status),
    INDEX ix_post_active (deleted_at,status,reference_time,id)
) ENGINE=InnoDB;

CREATE TABLE participation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_participation_post FOREIGN KEY (post_id) REFERENCES post(id),
    CONSTRAINT fk_participation_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_participation_post_user UNIQUE (post_id,user_id),
    CONSTRAINT ck_participation_state CHECK (
        (status='JOINED' AND cancelled_at IS NULL) OR (status='CANCELLED' AND cancelled_at IS NOT NULL)),
    INDEX ix_participation_post_status (post_id,status),
    INDEX ix_participation_user_updated (user_id,updated_at,id)
) ENGINE=InnoDB;
