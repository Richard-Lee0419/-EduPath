package com.edupath.course;

import com.edupath.auth.AuthContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CourseCatalogService {

    private final CourseRepository courseRepository;

    public CourseCatalogService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public List<CourseDto> listCourses() {
        return courseRepository.listCourses().stream().map(this::toCourseDto).toList();
    }

    public List<KnowledgePointDto> listKnowledgePoints(int courseId) {
        List<CourseRepository.KnowledgePointRow> rows = courseRepository.listKnowledgePoints(courseId);
        Map<Long, MutableKnowledgePoint> nodes = new LinkedHashMap<>();
        for (CourseRepository.KnowledgePointRow row : rows) {
            nodes.put(row.id(), new MutableKnowledgePoint(row));
        }
        List<MutableKnowledgePoint> roots = new ArrayList<>();
        for (MutableKnowledgePoint node : nodes.values()) {
            if (node.row.parentId() != null && nodes.containsKey(node.row.parentId())) {
                nodes.get(node.row.parentId()).children.add(node);
            } else {
                roots.add(node);
            }
        }
        Comparator<MutableKnowledgePoint> comparator = Comparator
                .comparingInt((MutableKnowledgePoint node) -> node.row.sortOrder())
                .thenComparingLong(node -> node.row.id());
        roots.sort(comparator);
        nodes.values().forEach(node -> node.children.sort(comparator));
        return roots.stream().map(this::toKnowledgePointDto).toList();
    }

    public String courseName(int courseId) {
        return courseRepository.findCourse(courseId)
                .map(CourseRepository.CourseRow::name)
                .orElse("未知课程");
    }

    public String courseCode(int courseId) {
        return courseRepository.findCourse(courseId)
                .map(CourseRepository.CourseRow::code)
                .orElse("unknown");
    }

    public List<String> resolveKnowledgePointNames(
            int courseId, List<Integer> knowledgePointIds, List<String> requestedNames) {
        if (requestedNames != null && !requestedNames.isEmpty()) {
            return requestedNames.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .toList();
        }
        Map<Integer, String> index = new LinkedHashMap<>();
        for (CourseRepository.KnowledgePointRow row : courseRepository.listKnowledgePoints(courseId)) {
            index.put((int) row.id(), row.name());
        }
        if (knowledgePointIds != null && !knowledgePointIds.isEmpty()) {
            List<String> names = knowledgePointIds.stream()
                    .map(index::get)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!names.isEmpty()) {
                return names;
            }
        }
        return index.values().stream().skip(1).limit(2).toList().isEmpty()
                ? List.of("核心知识点")
                : index.values().stream().skip(1).limit(2).toList();
    }

    public String normalizeCourseTopic(int courseId) {
        return resolveKnowledgePointNames(courseId, List.of(), List.of()).stream()
                .findFirst()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .orElse("topic");
    }

    @Transactional
    public CourseDto createCourse(CourseCreateRequest request) {
        String code = requireText(request.code(), "课程编码不能为空");
        String name = requireText(request.name(), "课程名称不能为空");
        long id = courseRepository.createCourse(
                code,
                name,
                request.description(),
                AuthContext.requirePrincipal().userId());
        return courseRepository.findCourse(id).map(this::toCourseDto).orElseThrow();
    }

    @Transactional
    public CourseDto updateCourse(long courseId, CourseUpdateRequest request) {
        int updated = courseRepository.updateCourse(courseId, request.name(), request.description(), request.status());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在: " + courseId);
        }
        return courseRepository.findCourse(courseId).map(this::toCourseDto).orElseThrow();
    }

    @Transactional
    public KnowledgePointDto createKnowledgePoint(long courseId, KnowledgePointCreateRequest request) {
        courseRepository.findCourse(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在: " + courseId));
        long id = courseRepository.createKnowledgePoint(
                courseId,
                request.parentId(),
                requireText(request.name(), "知识点名称不能为空"),
                request.difficulty(),
                request.sortOrder() == null ? 0 : request.sortOrder());
        return courseRepository.listKnowledgePoints(courseId).stream()
                .filter(row -> row.id() == id)
                .findFirst()
                .map(row -> new KnowledgePointDto((int) row.id(), row.name(), row.difficulty(), List.of()))
                .orElseThrow();
    }

    @Transactional
    public KnowledgePointDto updateKnowledgePoint(long pointId, KnowledgePointUpdateRequest request) {
        int updated = courseRepository.updateKnowledgePoint(pointId, request.name(), request.difficulty(), request.status());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识点不存在: " + pointId);
        }
        return courseRepository.listCourses().stream()
                .flatMap(course -> courseRepository.listKnowledgePoints(course.id()).stream())
                .filter(row -> row.id() == pointId)
                .findFirst()
                .map(row -> new KnowledgePointDto((int) row.id(), row.name(), row.difficulty(), List.of()))
                .orElseThrow();
    }

    private CourseDto toCourseDto(CourseRepository.CourseRow row) {
        return new CourseDto((int) row.id(), row.code(), row.name(), row.description());
    }

    private KnowledgePointDto toKnowledgePointDto(MutableKnowledgePoint node) {
        return new KnowledgePointDto(
                (int) node.row.id(),
                node.row.name(),
                node.row.difficulty(),
                node.children.stream().map(this::toKnowledgePointDto).toList());
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private static final class MutableKnowledgePoint {
        private final CourseRepository.KnowledgePointRow row;
        private final List<MutableKnowledgePoint> children = new ArrayList<>();

        private MutableKnowledgePoint(CourseRepository.KnowledgePointRow row) {
            this.row = row;
        }
    }

    public record CourseDto(int id, String code, String name, String description) {}

    public record KnowledgePointDto(int id, String name, String difficulty, List<KnowledgePointDto> children) {
        public KnowledgePointDto {
            children = children == null ? List.of() : new ArrayList<>(children);
        }
    }

    public record CourseCreateRequest(String code, String name, String description) {}

    public record CourseUpdateRequest(String name, String description, String status) {}

    public record KnowledgePointCreateRequest(Long parentId, String name, String difficulty, Integer sortOrder) {}

    public record KnowledgePointUpdateRequest(String name, String difficulty, String status) {}
}
