import type { Profile } from '../types';
import { apiGet, apiPost } from './http';
import { normalizeTaskId } from './task';
import type { TaskCreated } from '@/types/api';

export interface AnalyzeProfilePayload {
  message: string;
  courseIds: number[];
}

export interface CurrentProfileResult {
  profile: Profile;
  version: number;
  updatedReason?: string;
}

export interface AnalyzeProfileResult {
  taskId: string;
}

type BackendProfileResponse = {
  profile?: Record<string, unknown>;
  version?: number;
  updated_reason?: string;
};

const stringList = (value: unknown): string[] => {
  if (!Array.isArray(value)) return [];
  return value.map(String).filter(Boolean);
};

const text = (source: Record<string, unknown> | undefined, key: string, fallback = ''): string => {
  const value = source?.[key];
  return value === undefined || value === null || value === '' ? fallback : String(value);
};

export const mapProfile = (raw?: Record<string, unknown>): Profile => ({
  studentId: text(raw, 'student_id'),
  studentName: text(raw, 'student_name', text(raw, 'student_id')),
  major: text(raw, 'major'),
  grade: text(raw, 'grade'),
  targetCourses: stringList(raw?.target_courses),
  learningGoal: text(raw, 'learning_goal'),
  weakPoints: stringList(raw?.weak_points),
  resourcePreference: stringList(raw?.resource_preference),
  cognitiveStyle: stringList(raw?.cognitive_style),
  learningPace: text(raw, 'learning_pace'),
  confidenceScore: Number(raw?.confidence_score ?? 0),
});

export async function getCurrentProfile(): Promise<CurrentProfileResult> {
  const data = await apiGet<BackendProfileResponse>('/profile/current');
  return {
    profile: mapProfile(data.profile),
    version: data.version ?? 1,
    updatedReason: data.updated_reason,
  };
}

export async function analyzeProfile(payload: AnalyzeProfilePayload): Promise<AnalyzeProfileResult> {
  const data = await apiPost<TaskCreated>('/profile/chat', {
    message: payload.message,
    course_ids: payload.courseIds,
  });
  return {
    taskId: normalizeTaskId(data),
  };
}
