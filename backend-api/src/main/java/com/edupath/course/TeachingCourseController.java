package com.edupath.course;

import com.edupath.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teaching-courses")
public class TeachingCourseController {

    private final TeachingCourseService courses;

    public TeachingCourseController(TeachingCourseService courses) {
        this.courses = courses;
    }

    @GetMapping
    public ApiResponse<List<TeachingCourseService.TeachingCourse>> list() {
        return ApiResponse.success(courses.list());
    }

    @PostMapping
    public ApiResponse<TeachingCourseService.TeachingCourse> create(@RequestBody TeachingCourseService.CourseFields body) {
        return ApiResponse.success(courses.create(body));
    }

    @PatchMapping("/{id}")
    public ApiResponse<TeachingCourseService.TeachingCourse> update(@PathVariable long id,
            @RequestBody TeachingCourseService.CourseFields body) {
        return ApiResponse.success(courses.update(id, body));
    }

    @PutMapping("/{id}/publication")
    public ApiResponse<TeachingCourseService.TeachingCourse> publication(@PathVariable long id,
            @RequestBody TeachingCourseService.Publication body) {
        return ApiResponse.success(courses.publish(id, body));
    }
}
