import { apiDelete, apiGet, apiPost } from './http';

export interface TeacherInvite {
  code: string;
  expires_at: string;
}

export interface ReviewLink {
  id: number;
  class_id: number;
  class_name: string;
  expires_at: string;
  active: boolean;
}

export type IssuedReviewLink = Omit<ReviewLink, 'active'> & { code: string };
export type ReviewLinks = { enabled: boolean; links: ReviewLink[] };

export const issueTeacherInvite = (expiresInHours: number, email?: string) =>
  apiPost<TeacherInvite>('/admin/access/teacher-invites', { expires_in_hours: expiresInHours, email });

export const getReviewLinks = () => apiGet<ReviewLinks>('/admin/access/review-links');

export const issueReviewLink = (expiresInHours: number) =>
  apiPost<IssuedReviewLink>('/admin/access/review-links', { expires_in_hours: expiresInHours });

export const revokeReviewLink = (id: number) => apiDelete<void>(`/admin/access/review-links/${id}`);
