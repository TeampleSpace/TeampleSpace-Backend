-- 공유 문서 저장 검증에 필요한 최소 테이블
-- 사용자와 파일 테이블은 해당 기능 단계에서 추가하고 그때 외래키를 연결한다

CREATE TABLE projects (
    id BINARY(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    event_seq BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT projects_status_check CHECK (status IN ('active', 'deleting', 'deleted'))
) ENGINE = InnoDB;

CREATE TABLE project_memberships (
    project_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    left_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT project_memberships_project_fk FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT project_memberships_state_check CHECK (state IN ('active', 'left', 'removed'))
) ENGINE = InnoDB;

CREATE TABLE presentations (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    schema_version INT UNSIGNED NOT NULL,
    generation BIGINT UNSIGNED NOT NULL DEFAULT 1,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    order_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    canvas_width DECIMAL(12, 4) NOT NULL,
    canvas_height DECIMAL(12, 4) NOT NULL,
    canvas_unit VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'ready',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY presentations_project_uk (project_id),
    UNIQUE KEY presentations_project_id_uk (project_id, id),
    CONSTRAINT presentations_project_fk FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT presentations_canvas_check CHECK (canvas_width > 0 AND canvas_height > 0),
    CONSTRAINT presentations_state_check CHECK (state IN ('ready', 'deleted'))
) ENGINE = InnoDB;

CREATE TABLE slides (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    presentation_id BINARY(16) NOT NULL,
    generation BIGINT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    position INT UNSIGNED NOT NULL,
    layout_id VARCHAR(64) NULL,
    deleted_at DATETIME(6) NULL,
    -- 삭제되지 않은 슬라이드만 같은 위치를 쓸 수 없다
    active_position INT UNSIGNED GENERATED ALWAYS AS (IF(deleted_at IS NULL, position, NULL)) STORED,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY slides_project_id_uk (project_id, id),
    UNIQUE KEY slides_active_position_uk (presentation_id, generation, active_position),
    CONSTRAINT slides_presentation_fk FOREIGN KEY (project_id, presentation_id) REFERENCES presentations (project_id, id)
) ENGINE = InnoDB;

CREATE TABLE slide_elements (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    slide_id BINARY(16) NOT NULL,
    type VARCHAR(32) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    x DECIMAL(12, 4) NOT NULL,
    y DECIMAL(12, 4) NOT NULL,
    width DECIMAL(12, 4) NOT NULL,
    height DECIMAL(12, 4) NOT NULL,
    rotation DECIMAL(8, 4) NOT NULL DEFAULT 0,
    z_order INT NOT NULL DEFAULT 0,
    content JSON NOT NULL,
    style JSON NOT NULL,
    image_file_id BINARY(16) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY slide_elements_slide_idx (slide_id, deleted_at, z_order, id),
    CONSTRAINT slide_elements_slide_fk FOREIGN KEY (project_id, slide_id) REFERENCES slides (project_id, id),
    CONSTRAINT slide_elements_type_check CHECK (type IN ('text', 'shape', 'image')),
    CONSTRAINT slide_elements_size_check CHECK (width > 0 AND height > 0)
) ENGINE = InnoDB;

-- 프로젝트별 순번으로 저장하는 이벤트 기록. 문서 변경과 같은 트랜잭션에서 쓴다
CREATE TABLE project_events (
    project_id BINARY(16) NOT NULL,
    seq BIGINT UNSIGNED NOT NULL,
    type VARCHAR(64) NOT NULL,
    actor_id BINARY(16) NULL,
    mutation_id VARCHAR(64) NULL,
    resource_id BINARY(16) NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (project_id, seq),
    CONSTRAINT project_events_project_fk FOREIGN KEY (project_id) REFERENCES projects (id)
) ENGINE = InnoDB;

CREATE TABLE idempotency_records (
    id BINARY(16) NOT NULL,
    actor_scope VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    route_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    response_status INT NULL,
    response_body JSON NULL,
    resource_id BINARY(16) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY idempotency_records_key_uk (actor_scope, route_key, key_hash),
    CONSTRAINT idempotency_records_state_check CHECK (state IN ('processing', 'succeeded', 'failed'))
) ENGINE = InnoDB;
