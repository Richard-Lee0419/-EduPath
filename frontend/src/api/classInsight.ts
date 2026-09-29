import { apiGet } from './http';
import type { ClassLearningInsights, TeachingClass } from '@/types/api';

export function listTeachingClasses() {
  return apiGet<TeachingClass[]>('/classes');
}

export function getClassLearningInsights(
  classId: number,
  params: { courseId?: number; windowDays?: number } = {},
) {
  return apiGet<ClassLearningInsights>(`/classes/${classId}/insights`, {
    course_id: params.courseId,
    window_days: params.windowDays ?? 30,
  });
}
