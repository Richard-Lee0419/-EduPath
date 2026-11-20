CREATE TABLE agent_task_courses (
    task_id VARCHAR(64) NOT NULL,
    course_id BIGINT NOT NULL,
    PRIMARY KEY (task_id, course_id),
    CONSTRAINT fk_agent_task_courses_task FOREIGN KEY (task_id) REFERENCES agent_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_task_courses_course FOREIGN KEY (course_id) REFERENCES courses(id)
);

CREATE VIEW class_course_mastery AS
SELECT student_id, course_id, knowledge_point,
       SUM(attempts) AS attempts,
       SUM(correct_count) AS correct_count,
       SUM(behavior_weight) AS behavior_weight,
       ROUND(1.0 * SUM(weighted_score) / (10 * SUM(attempts) + SUM(behavior_weight)), 0) AS mastery_score,
       MAX(updated_at) AS updated_at
FROM (
    SELECT qa.student_id, q.course_id, qq.knowledge_point, 1 AS attempts,
           CASE WHEN qi.correct THEN 1 ELSE 0 END AS correct_count, 0 AS behavior_weight,
           CASE WHEN qi.correct THEN 1000 ELSE 0 END AS weighted_score, qi.created_at AS updated_at
    FROM quiz_attempt_items qi
    JOIN quiz_attempts qa ON qa.attempt_id = qi.attempt_id
    JOIN quizzes q ON q.quiz_id = qa.quiz_id
    JOIN quiz_questions qq ON qq.question_id = qi.question_id
    JOIN courses c ON c.id = q.course_id AND c.visibility = 'class'
    UNION ALL
    SELECT le.student_id, le.course_id, le.knowledge_point, 0, 0, le.evidence_weight,
           le.evidence_weight * le.evidence_score, le.occurred_at
    FROM learning_events le JOIN courses c ON c.id = le.course_id AND c.visibility = 'class'
    WHERE le.evidence_weight > 0
) evidence
GROUP BY student_id, course_id, knowledge_point;
