-- 메모 공유 편집의 변경 조각. 편집기가 만든 조각을 순번대로 쌓고 압축하면 앞 순번을 한 덩어리로 바꾼다
CREATE TABLE memo_updates (
    material_id BINARY(16) NOT NULL,
    seq BIGINT UNSIGNED NOT NULL,
    data MEDIUMBLOB NOT NULL,
    created_by BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (material_id, seq),
    CONSTRAINT memo_updates_material_fk FOREIGN KEY (material_id) REFERENCES materials (id) ON DELETE CASCADE
) ENGINE = InnoDB;
