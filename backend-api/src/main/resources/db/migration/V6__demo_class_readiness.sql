INSERT INTO classes (name, description, owner_user_id, status, created_at, updated_at)
SELECT 'A3 双核心冲刺班', '用于演示算法与数据结构、计算机组成原理的个性化学习闭环', 2, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM classes WHERE name = 'A3 双核心冲刺班' AND status <> 'deleted'
);

INSERT INTO class_members (class_id, user_id, role, status, created_at)
SELECT c.id, 1, 'student', 'active', CURRENT_TIMESTAMP
FROM classes c
WHERE c.name = 'A3 双核心冲刺班'
  AND c.status <> 'deleted'
  AND NOT EXISTS (
      SELECT 1 FROM class_members cm
      WHERE cm.class_id = c.id AND cm.user_id = 1 AND cm.role = 'student'
  );

INSERT INTO class_courses (class_id, course_id, status, created_at)
SELECT c.id, course.id, 'active', CURRENT_TIMESTAMP
FROM classes c
JOIN courses course ON course.id IN (1, 2)
WHERE c.name = 'A3 双核心冲刺班'
  AND c.status <> 'deleted'
  AND NOT EXISTS (
      SELECT 1 FROM class_courses cc
      WHERE cc.class_id = c.id AND cc.course_id = course.id
  );
