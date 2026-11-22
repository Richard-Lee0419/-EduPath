ALTER TABLE review_links MODIFY COLUMN created_by BIGINT NULL;

CREATE TABLE review_session_quota (
    id INT NOT NULL PRIMARY KEY,
    session_day DATE NOT NULL,
    sessions_started INT NOT NULL
);

INSERT INTO review_session_quota (id, session_day, sessions_started) VALUES (1, CURRENT_DATE, 0);
