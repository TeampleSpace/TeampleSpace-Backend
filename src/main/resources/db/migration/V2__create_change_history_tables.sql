-- 연속 저장을 하나의 검토 단위로 묶는 변경 이력
-- 승인, 댓글, 복원 관련 테이블과 컬럼은 해당 기능을 만들 때 추가한다

CREATE TABLE document_changes (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    presentation_id BINARY(16) NOT NULL,
    generation BIGINT UNSIGNED NOT NULL,
    author_id BINARY(16) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    client_mutation_id VARCHAR(64) NOT NULL,
    edit_group_id BINARY(16) NULL,
    session_id BINARY(16) NULL,
    group_state VARCHAR(32) NOT NULL,
    opened_at DATETIME(6) NOT NULL,
    last_saved_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NULL,
    presentation_version BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY document_changes_project_id_uk (project_id, id),
    -- 닫힌 묶음의 식별자를 다시 쓸 수 없도록 묶음마다 변경은 하나다
    UNIQUE KEY document_changes_edit_group_uk (edit_group_id),
    KEY document_changes_open_idx (project_id, group_state, last_saved_at),
    KEY document_changes_list_idx (presentation_id, created_at, id),
    CONSTRAINT document_changes_presentation_fk FOREIGN KEY (project_id, presentation_id)
        REFERENCES presentations (project_id, id),
    CONSTRAINT document_changes_kind_check CHECK (kind IN ('element_edit')),
    CONSTRAINT document_changes_state_check CHECK (group_state IN ('open', 'closed')),
    CONSTRAINT document_changes_closed_check CHECK ((group_state = 'closed') = (closed_at IS NOT NULL))
) ENGINE = InnoDB;

CREATE TABLE change_targets (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    change_id BINARY(16) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    element_id BINARY(16) NULL,
    slide_id BINARY(16) NULL,
    before_version BIGINT UNSIGNED NULL,
    after_version BIGINT UNSIGNED NULL,
    before_state JSON NULL,
    after_state JSON NULL,
    ordinal INT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY change_targets_ordinal_uk (change_id, ordinal),
    KEY change_targets_element_idx (element_id, change_id),
    KEY change_targets_slide_idx (slide_id, change_id),
    CONSTRAINT change_targets_change_fk FOREIGN KEY (project_id, change_id) REFERENCES document_changes (project_id, id),
    CONSTRAINT change_targets_element_fk FOREIGN KEY (element_id) REFERENCES slide_elements (id),
    CONSTRAINT change_targets_slide_fk FOREIGN KEY (slide_id) REFERENCES slides (id),
    CONSTRAINT change_targets_scope_check CHECK (scope IN ('element')),
    CONSTRAINT change_targets_element_check CHECK (scope <> 'element' OR (element_id IS NOT NULL AND slide_id IS NOT NULL))
) ENGINE = InnoDB;
