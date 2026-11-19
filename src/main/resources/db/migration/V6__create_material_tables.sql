-- 프로젝트 자료. 올린 파일과 입력한 주소는 분석 전과 실패 뒤에도 보여 줘야 해서 자료가 직접 가진다
CREATE TABLE materials (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    created_by BINARY(16) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    state VARCHAR(32) NOT NULL,
    source_url TEXT NULL,
    current_revision_id BINARY(16) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY materials_project_id_uk (project_id, id),
    KEY materials_list_idx (project_id, deleted_at, created_at, id),
    CONSTRAINT materials_project_fk FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT materials_creator_fk FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT materials_kind_check CHECK (kind IN ('file', 'link', 'memo')),
    CONSTRAINT materials_state_check CHECK (state IN ('processing', 'ready', 'failed', 'deleted'))
) ENGINE = InnoDB;

-- 바뀌지 않는 원문 개정. 요약과 발췌는 분석 결과를 다시 조회하려고 함께 둔다
CREATE TABLE material_revisions (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    material_id BINARY(16) NOT NULL,
    revision_no BIGINT UNSIGNED NOT NULL,
    source_file_id BINARY(16) NULL,
    source_url TEXT NULL,
    source_title VARCHAR(512) NULL,
    markdown LONGTEXT NULL,
    extracted_text LONGTEXT NULL,
    page_count INT UNSIGNED NULL,
    summary TEXT NULL,
    excerpt TEXT NULL,
    excerpt_page INT UNSIGNED NULL,
    content_hash BINARY(32) NOT NULL,
    extractor_version VARCHAR(64) NOT NULL,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY material_revisions_no_uk (material_id, revision_no),
    UNIQUE KEY material_revisions_material_id_uk (material_id, id),
    UNIQUE KEY material_revisions_project_id_uk (project_id, id),
    CONSTRAINT material_revisions_material_fk FOREIGN KEY (project_id, material_id) REFERENCES materials (project_id, id),
    CONSTRAINT material_revisions_creator_fk FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE = InnoDB;

-- 현재 개정은 같은 자료의 개정만 가리킨다. 자료를 먼저 만들고 개정을 넣은 뒤 연결한다
ALTER TABLE materials
    ADD CONSTRAINT materials_current_revision_fk FOREIGN KEY (id, current_revision_id)
        REFERENCES material_revisions (material_id, id);

-- 자료마다 하나인 팀 메모. 원문인 마크다운 자료와 다른 자원이다
CREATE TABLE material_notes (
    material_id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    markdown LONGTEXT NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    updated_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (material_id),
    CONSTRAINT material_notes_material_fk FOREIGN KEY (project_id, material_id) REFERENCES materials (project_id, id),
    CONSTRAINT material_notes_updater_fk FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE = InnoDB;
