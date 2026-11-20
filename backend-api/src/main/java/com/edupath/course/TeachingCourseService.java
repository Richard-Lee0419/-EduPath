package com.edupath.course;

import com.edupath.auth.AuthContext;
import com.edupath.auth.RoleGuard;
import com.edupath.auth.UserRepository;
import com.edupath.kb.KnowledgeDocumentRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeachingCourseService {

    private final CourseRepository courses;
    private final ClassRepository classes;
    private final UserRepository users;
    private final RoleGuard roles;
    private final KnowledgeDocumentRepository documents;
    private final CourseAccess access;

    TeachingCourseService(CourseRepository courses, ClassRepository classes, UserRepository users, RoleGuard roles,
                          KnowledgeDocumentRepository documents, CourseAccess access) {
        this.courses = courses;
        this.classes = classes;
        this.users = users;
        this.roles = roles;
        this.documents = documents;
        this.access = access;
    }

    List<TeachingCourse> list() {
        roles.requireAny("teacher", "admin");
        return courses.listCourses().stream().filter(course -> "class".equals(course.visibility()))
                .filter(course -> access.canManage(course.id()))
                .map(this::toTeachingCourse).toList();
    }

    @Transactional
    public TeachingCourse create(CourseFields request) {
        roles.requireAny("teacher", "admin");
        long userId = AuthContext.requirePrincipal().userId();
        String name = name(request.name(), "课程名称", 100);
        String description = description(request.description());
        List<String> topics = topics(request.topics());
        long id = courses.createCourse("CLASS-" + UUID.randomUUID(), name, description, userId, "class", "draft");
        users.grantCourseManager(userId, id);
        for (int i = 0; i < topics.size(); i++) {
            courses.createKnowledgePoint(id, null, topics.get(i), "basic", i);
        }
        return read(id);
    }

    @Transactional
    public TeachingCourse update(long id, CourseFields request) {
        lockManagedCourse(id);
        String name = request.name() == null ? null : name(request.name(), "课程名称", 100);
        courses.updateCourse(id, name, description(request.description()), null);
        if (request.topics() != null) {
            List<String> desired = topics(request.topics());
            List<CourseRepository.KnowledgePointRow> current = courses.listKnowledgePoints(id);
            for (CourseRepository.KnowledgePointRow point : current) {
                if (desired.contains(point.name())) continue;
                if (documents.hasTopicDocuments(id, point.id())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "知识点「" + point.name() + "」仍有资料，请先处理对应 PDF");
                }
                courses.updateKnowledgePoint(point.id(), null, null, "deleted");
            }
            for (int i = 0; i < desired.size(); i++) {
                String item = desired.get(i);
                var existing = current.stream().filter(point -> point.name().equals(item)).findFirst();
                if (existing.isPresent()) courses.orderKnowledgePoint(existing.get().id(), i);
                else courses.createKnowledgePoint(id, null, item, "basic", i);
            }
        }
        return read(id);
    }

    @Transactional
    public TeachingCourse publish(long id, Publication request) {
        CourseRepository.CourseRow course = lockManagedCourse(id);
        List<Long> ids = request.classIds() == null ? classes.courseClassIds(id) : request.classIds();
        if (ids.stream().anyMatch(classId -> classId == null || classId < 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "班级编号无效");
        }
        List<Long> selected = ids.stream().distinct().toList();
        var user = AuthContext.requirePrincipal();
        for (long classId : selected) {
            ClassRepository.ClassRow classroom = classes.find(classId);
            if (classroom == null || !"active".equals(classroom.status())
                    || (!"admin".equals(user.role()) && classroom.ownerUserId() != user.userId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能选择自己负责的有效班级");
            }
        }
        if (request.published()) {
            if (selected.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个班级");
            if (!documents.hasIndexedPdf(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请先上传至少一份成功入库的课程 PDF");
            }
        }
        classes.replaceCourseClasses(id, selected);
        String status = request.published() ? "active" : "draft".equals(course.status()) ? "draft" : "archived";
        courses.updateCourse(id, null, null, status);
        return read(id);
    }

    private CourseRepository.CourseRow lockManagedCourse(long id) {
        roles.requireCourseManager(id);
        return courses.lockClassCourse(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "班级课程不存在"));
    }

    private TeachingCourse read(long id) {
        return courses.findCourse(id).map(this::toTeachingCourse).orElseThrow();
    }

    private TeachingCourse toTeachingCourse(CourseRepository.CourseRow course) {
        return new TeachingCourse(course.id(), course.code(), course.name(), course.description(), course.status(),
                classes.courseClassIds(course.id()), courses.listKnowledgePoints(course.id()).stream()
                        .map(point -> new Topic(point.id(), point.name())).toList());
    }

    private static List<String> topics(List<String> names) {
        if (names == null || names.isEmpty() || names.size() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请设置 1–30 个知识点");
        }
        List<String> result = new ArrayList<>();
        var seen = new HashSet<String>();
        for (String value : names) {
            String topic = name(value, "知识点名称", 100);
            if (!seen.add(topic.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识点名称不能重复");
            }
            result.add(topic);
        }
        return result;
    }

    private static String name(String value, String label, int maxLength) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "须为 1–" + maxLength + " 字");
        }
        return text;
    }

    private static String description(String value) {
        if (value == null) return null;
        String text = value.strip();
        if (text.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "课程简介不能超过 4000 字");
        }
        return text;
    }

    public record CourseFields(String name, String description, List<String> topics) {}

    public record Publication(List<Long> classIds, boolean published) {}

    public record TeachingCourse(long id, String code, String name, String description, String status,
                                 List<Long> classIds, List<Topic> topics) {}

    public record Topic(long id, String name) {}
}
