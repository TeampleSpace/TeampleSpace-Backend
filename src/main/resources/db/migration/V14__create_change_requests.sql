-- 팀원이 다른 팀원에게 승인을 받아 실행하는 작업 요청. 요청할 때의 본문을 그대로 저장한다
CREATE TABLE change_requests (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    type VARCHAR(32) NOT NULL,
    requested_by BINARY(16) NOT NULL,
    payload JSON NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'pending',
    decided_by BINARY(16) NULL,
    result JSON NULL,
    error JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY change_requests_project (project_id, state, created_at),
    CONSTRAINT change_requests_project_fk FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT change_requests_requester_fk FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT change_requests_decider_fk FOREIGN KEY (decided_by) REFERENCES users (id)
) ENGINE = InnoDB;
