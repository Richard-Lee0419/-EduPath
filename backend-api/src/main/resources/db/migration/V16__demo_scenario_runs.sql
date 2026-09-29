CREATE TABLE demo_scenario_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    scenario_key VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reset_existing BOOLEAN NOT NULL DEFAULT TRUE,
    prepared_by BIGINT NOT NULL,
    summary TEXT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    CONSTRAINT fk_demo_scenario_runs_user FOREIGN KEY (prepared_by) REFERENCES users(id)
);

CREATE INDEX idx_demo_scenario_runs_scenario
    ON demo_scenario_runs(scenario_key, completed_at);
