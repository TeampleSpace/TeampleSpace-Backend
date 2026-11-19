CREATE TABLE templates (
    id BINARY(16) NOT NULL,
    template_key VARCHAR(64) NOT NULL,
    release_no INT UNSIGNED NOT NULL,
    name VARCHAR(255) NOT NULL,
    state ENUM('draft', 'published', 'retired') NOT NULL,
    display_position INT UNSIGNED NOT NULL,
    preview JSON NOT NULL,
    fonts JSON NOT NULL,
    layouts JSON NOT NULL,
    schema_version INT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY templates_release_uk (template_key, release_no)
) ENGINE = InnoDB;

INSERT INTO templates (id, template_key, release_no, name, state, display_position, preview, fonts, layouts, schema_version)
VALUES
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00001'), 'clean-report', 1, '클린 리포트', 'published', 0,
     JSON_OBJECT('backgroundColor', '#FFFFFF', 'headingColor', '#212735', 'description', '흰 배경과 차분한 제목, 근거와 출처 중심'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1),
    (UUID_TO_BIN('ec85bac3-878f-4b5f-b4bc-5f0bc8c00002'), 'blue-focus', 1, '블루 포커스', 'published', 1,
     JSON_OBJECT('backgroundColor', '#EBF1FF', 'headingColor', '#2864E8', 'description', '옅은 파랑 배경과 파란 제목, 핵심 주장 강조'),
     JSON_ARRAY('Pretendard'), JSON_ARRAY('title', 'content', 'summary'), 1);

CREATE TABLE design_settings (
    project_id BINARY(16) NOT NULL,
    mode ENUM('template', 'custom') NOT NULL,
    template_id BINARY(16) NULL,
    custom JSON NULL,
    version BIGINT UNSIGNED NOT NULL,
    updated_by BINARY(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (project_id),
    CONSTRAINT design_project_fk FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT design_template_fk FOREIGN KEY (template_id) REFERENCES templates (id),
    CONSTRAINT design_updater_fk FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT design_mode_ck CHECK (
        (mode = 'template' AND template_id IS NOT NULL AND custom IS NULL)
        OR (mode = 'custom' AND template_id IS NULL AND custom IS NOT NULL)
    )
) ENGINE = InnoDB;
