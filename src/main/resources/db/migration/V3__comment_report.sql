-- Public comments and reports on a recruitment post (#53). UTC DATETIME(6), like V2.
CREATE TABLE post_comment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_comment_post FOREIGN KEY (post_id) REFERENCES post(id),
    CONSTRAINT fk_post_comment_author FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT ck_post_comment_content CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    INDEX ix_post_comment_post_created (post_id,created_at,id)
) ENGINE=InnoDB;

-- One report per user per post (REPORT-002). Reports never remove a post by themselves.
CREATE TABLE post_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL,
    reason VARCHAR(30) NOT NULL,
    detail VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_report_post FOREIGN KEY (post_id) REFERENCES post(id),
    CONSTRAINT fk_post_report_reporter FOREIGN KEY (reporter_id) REFERENCES users(id),
    CONSTRAINT uq_post_report_post_reporter UNIQUE (post_id,reporter_id),
    CONSTRAINT ck_post_report_reason CHECK (reason IN ('INAPPROPRIATE','AD_PROMOTION','ABUSE','FALSE_RECRUITMENT','OTHER')),
    INDEX ix_post_report_reporter (reporter_id)
) ENGINE=InnoDB;
