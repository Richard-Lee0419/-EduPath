INSERT INTO classes (name, description, owner_user_id, status, created_at, updated_at)
SELECT '测试2班', '用于展示两门计算机课程的班级学习聚合洞察', u.id, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users u
WHERE u.username = 'teacher'
  AND NOT EXISTS (SELECT 1 FROM classes c WHERE c.name = '测试2班');

INSERT INTO class_members (class_id, user_id, role, status, created_at)
SELECT c.id, u.id, 'student', 'active', CURRENT_TIMESTAMP
FROM classes c
JOIN users u ON u.username = 'demo'
WHERE c.name = '测试2班'
  AND NOT EXISTS (
      SELECT 1 FROM class_members cm
      WHERE cm.class_id = c.id AND cm.user_id = u.id AND cm.role = 'student'
  );

INSERT INTO class_courses (class_id, course_id, status, created_at)
SELECT c.id, course.id, 'active', CURRENT_TIMESTAMP
FROM classes c
JOIN courses course ON course.code IN ('data_structures_algorithms', 'computer_organization')
WHERE c.name = '测试2班'
  AND NOT EXISTS (
      SELECT 1 FROM class_courses cc
      WHERE cc.class_id = c.id AND cc.course_id = course.id
  );
