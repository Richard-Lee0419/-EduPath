import { apiGet } from "./http";
import type { Course, KnowledgePoint } from "@/types/api";

export function getCourses() {
  return apiGet<Course[]>("/courses");
}

export function getKnowledgePoints(courseId: number) {
  return apiGet<KnowledgePoint[]>(`/courses/${courseId}/knowledge-points`);
}

export function requestedCourseId() {
  const value = new URLSearchParams(window.location.search).get('course');
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

export function learningCourses(courses: Course[]) {
  return courses.filter((course) => course.visibility !== 'class' || course.status === 'active');
}
