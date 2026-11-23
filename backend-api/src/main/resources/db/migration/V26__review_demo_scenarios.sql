ALTER TABLE review_links ADD COLUMN sample_course_id BIGINT NULL;

CREATE TABLE review_demo_students (
    review_link_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (review_link_id, user_id),
    CONSTRAINT fk_review_demo_students_link FOREIGN KEY (review_link_id) REFERENCES review_links(id),
    CONSTRAINT fk_review_demo_students_user FOREIGN KEY (user_id) REFERENCES users(id)
);
