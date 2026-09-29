import { apiGet } from "./http";
import type { Course, KnowledgePoint } from "@/types/api";

export function getCourses() {
  return apiGet<Course[]>("/courses");
}

export function getKnowledgePoints(courseId: number) {
  return apiGet<KnowledgePoint[]>(`/courses/${courseId}/knowledge-points`);
}
