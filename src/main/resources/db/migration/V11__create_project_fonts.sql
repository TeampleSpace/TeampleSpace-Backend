CREATE TABLE project_fonts (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    format ENUM('ttf', 'otf') NOT NULL,
    digest BINARY(32) NOT NULL,
    size_bytes INT UNSIGNED NOT NULL,
    content MEDIUMBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY project_fonts_digest_uk (project_id, digest),
    CONSTRAINT project_fonts_project_fk FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT project_fonts_size_ck CHECK (size_bytes > 0 AND size_bytes <= 5242880 AND size_bytes = OCTET_LENGTH(content))
) ENGINE = InnoDB;
