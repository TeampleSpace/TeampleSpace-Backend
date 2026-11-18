ALTER TABLE projects
    ADD COLUMN topic TEXT NULL,
    ADD COLUMN presentation_constraints TEXT NULL,
    ADD COLUMN deleted_at DATETIME(6) NULL;

CREATE TABLE project_invitations (
    id BINARY(16) NOT NULL PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    token_ciphertext TEXT NOT NULL,
    issued_by BINARY(16) NOT NULL,
    state VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    active_project_id BINARY(16) GENERATED ALWAYS AS (IF(state='active',project_id,NULL)) STORED,
    UNIQUE KEY invitation_token_uk (token_hash),
    UNIQUE KEY invitation_active_project_uk (active_project_id),
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (issued_by) REFERENCES users(id),
    CHECK (state IN ('active','revoked'))
) ENGINE=InnoDB;

CREATE INDEX membership_user_state_idx ON project_memberships(user_id,state,project_id);
CREATE INDEX projects_active_id_idx ON projects(status,id);
