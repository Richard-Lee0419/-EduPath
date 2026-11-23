import { apiGet, apiPost } from './http';
import type { ClassLearningInsights, TeachingClass } from '@/types/api';

export function listTeachingClasses() {
  return apiGet<TeachingClass[]>('/classes');
}

export type ClassMember = { user_id: number; username: string; class_role: string };
export type ReviewStudent = { user_id: number; username: string; display_name: string };

export const listReviewStudents = () => apiGet<ReviewStudent[]>('/classes/review-students');

export const createTeachingClass = (name: string) => apiPost<{ class_info: TeachingClass }>('/classes', { name });

export const listTeachingClassMembers = (classId: number) => apiGet<ClassMember[]>(`/classes/${classId}/members`);

export const addTeachingClassStudent = (classId: number, username: string) =>
  apiPost<ClassMember[]>(`/classes/${classId}/members`, { username, role: 'student' });

export function getClassLearningInsights(
  classId: number,
  params: { courseId?: number; windowDays?: number } = {},
) {
  return apiGet<ClassLearningInsights>(`/classes/${classId}/insights`, {
    course_id: params.courseId,
    window_days: params.windowDays ?? 30,
  });
}
