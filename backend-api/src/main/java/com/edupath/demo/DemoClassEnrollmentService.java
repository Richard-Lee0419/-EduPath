package com.edupath.demo;

import com.edupath.course.ClassRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class DemoClassEnrollmentService {

    private final ClassRepository classRepository;
    private final boolean enabled;
    private final String className;

    public DemoClassEnrollmentService(
            ClassRepository classRepository,
            @Value("${edupath.demo-mode.enabled:true}") boolean enabled,
            @Value("${edupath.demo-mode.class-name:测试2班}") String className) {
        this.classRepository = classRepository;
        this.enabled = enabled;
        this.className = className;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void enrollExistingStudents() {
        if (!enabled) {
            return;
        }
        ClassRepository.ClassRow demoClass = classRepository.findByName(className);
        if (demoClass == null) {
            return;
        }
        classRepository.activeStudentUserIds().forEach(userId ->
                classRepository.addMember(demoClass.id(), userId, "student"));
    }

    public void enrollStudent(long userId) {
        if (!enabled) {
            return;
        }
        ClassRepository.ClassRow demoClass = classRepository.findByName(className);
        if (demoClass != null) {
            classRepository.addMember(demoClass.id(), userId, "student");
        }
    }
}
