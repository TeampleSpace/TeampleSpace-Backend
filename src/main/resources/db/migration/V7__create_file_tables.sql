-- 검사를 통과해 확정한 파일. 자료와 개정은 바뀌지 않는 이 객체만 참조한다
CREATE TABLE file_objects (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    bucket_key VARCHAR(512) NOT NULL,
    checksum BINARY(32) NOT NULL,
    mime_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    state VARCHAR(32) NOT NULL,
    verified_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY file_objects_bucket_key_uk (bucket_key),
    UNIQUE KEY file_objects_project_id_uk (project_id, id),
    CONSTRAINT file_objects_project_fk FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT file_objects_state_check CHECK (state IN ('ready', 'deleting', 'deleted'))
) ENGINE = InnoDB;

-- 업로드 준비 한 번. 전송한 파일은 임시 위치에 두고 등록할 때 검사해 확정한다
CREATE TABLE uploads (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    claimed_mime VARCHAR(127) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    checksum BINARY(32) NOT NULL,
    staging_key VARCHAR(512) NOT NULL,
    state VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    file_object_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uploads_staging_key_uk (staging_key),
    KEY uploads_expiry_idx (state, expires_at),
    CONSTRAINT uploads_project_fk FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT uploads_user_fk FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uploads_file_fk FOREIGN KEY (project_id, file_object_id) REFERENCES file_objects (project_id, id),
    CONSTRAINT uploads_state_check CHECK (state IN ('prepared', 'consumed', 'rejected', 'expired'))
) ENGINE = InnoDB;

ALTER TABLE materials
    ADD COLUMN source_file_id BINARY(16) NULL AFTER source_url,
    ADD CONSTRAINT materials_source_file_fk FOREIGN KEY (project_id, source_file_id)
        REFERENCES file_objects (project_id, id);

ALTER TABLE material_revisions
    ADD CONSTRAINT material_revisions_source_file_fk FOREIGN KEY (project_id, source_file_id)
        REFERENCES file_objects (project_id, id);

-- 분석으로 나눈 본문 조각. 쪽 번호는 1부터 시작한다
CREATE TABLE material_chunks (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    revision_id BINARY(16) NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    page_start INT UNSIGNED NULL,
    page_end INT UNSIGNED NULL,
    text TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY material_chunks_ordinal_uk (revision_id, ordinal),
    UNIQUE KEY material_chunks_revision_id_uk (revision_id, id),
    CONSTRAINT material_chunks_revision_fk FOREIGN KEY (project_id, revision_id)
        REFERENCES material_revisions (project_id, id)
) ENGINE = InnoDB;
