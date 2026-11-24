-- 자료마다 팀원이 남기는 댓글. 지운 댓글은 기록만 남기고 목록에서 뺀다
CREATE TABLE material_comments (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    material_id BINARY(16) NOT NULL,
    author_id BINARY(16) NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY material_comments_list (material_id, created_at, id),
    CONSTRAINT material_comments_material_fk FOREIGN KEY (project_id, material_id) REFERENCES materials (project_id, id),
    CONSTRAINT material_comments_author_fk FOREIGN KEY (author_id) REFERENCES users (id)
) ENGINE = InnoDB;
