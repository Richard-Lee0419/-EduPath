import { apiGet, apiPatch, apiPost, request } from './http';
import type { Course } from '@/types/api';

export type TeachingCourse = Course & {
  status: 'draft' | 'active' | 'archived';
  class_ids: number[];
  topics: { id: number; name: string }[];
};

type CourseDraft = { name: string; description: string; topics?: string[] };

const collection = '/teaching-courses';

export const listTeachingCourses = () => apiGet<TeachingCourse[]>(collection);

export const createTeachingCourse = (draft: CourseDraft) => apiPost<TeachingCourse>(collection, draft);

export const updateTeachingCourse = (id: number, draft: CourseDraft) =>
  apiPatch<TeachingCourse>(`${collection}/${id}`, draft);

export function setTeachingCoursePublication(id: number, classIds: number[], published: boolean) {
  return request<TeachingCourse>(`${collection}/${id}/publication`, {
    method: 'PUT',
    body: JSON.stringify({ class_ids: classIds, published }),
  });
}
