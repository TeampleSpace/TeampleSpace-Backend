CREATE TABLE outlines (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    updated_by BINARY(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY outlines_project_uk (project_id),
    UNIQUE KEY outlines_project_id_uk (project_id, id),
    CONSTRAINT outlines_project_fk FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT outlines_updater_fk FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE TABLE outline_items (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    outline_id BINARY(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    position INT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY outline_items_project_id_uk (project_id, id),
    UNIQUE KEY outline_items_position_uk (outline_id, position),
    CONSTRAINT outline_items_outline_fk FOREIGN KEY (project_id, outline_id)
        REFERENCES outlines (project_id, id)
) ENGINE = InnoDB;

CREATE TABLE outline_sources (
    outline_item_id BINARY(16) NOT NULL,
    revision_id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    position INT UNSIGNED NOT NULL,
    PRIMARY KEY (outline_item_id, revision_id),
    UNIQUE KEY outline_sources_position_uk (outline_item_id, position),
    CONSTRAINT outline_sources_item_fk FOREIGN KEY (project_id, outline_item_id)
        REFERENCES outline_items (project_id, id) ON DELETE CASCADE,
    CONSTRAINT outline_sources_revision_fk FOREIGN KEY (project_id, revision_id)
        REFERENCES material_revisions (project_id, id)
) ENGINE = InnoDB;
