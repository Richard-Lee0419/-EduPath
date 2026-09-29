import { apiGet, apiPost } from './http';
import type { AssignmentProgress, StudentAssignment, TeacherAssignment } from '@/types/api';

export type CreateTeacherAssignmentRequest = {
  course_id: number;
  title: string;
  instructions?: string;
  resource_id?: string;
  due_at?: string;
};

export function listTeacherAssignments(classId: number) {
  return apiGet<TeacherAssignment[]>(`/classes/${classId}/assignments`);
}

export function createTeacherAssignment(classId: number, request: CreateTeacherAssignmentRequest) {
  return apiPost<TeacherAssignment>(`/classes/${classId}/assignments`, request);
}

export function publishTeacherAssignment(classId: number, assignmentId: number) {
  return apiPost<TeacherAssignment>(`/classes/${classId}/assignments/${assignmentId}/publish`, {});
}

export function getTeacherAssignmentProgress(classId: number, assignmentId: number) {
  return apiGet<AssignmentProgress[]>(`/classes/${classId}/assignments/${assignmentId}/progress`);
}

export function listStudentAssignments(status?: 'assigned' | 'completed') {
  return apiGet<StudentAssignment[]>('/assignments', { status });
}

export function completeStudentAssignment(assignmentId: number, score?: number) {
  return apiPost<StudentAssignment>(`/assignments/${assignmentId}/complete`, score === undefined ? {} : { score });
}
