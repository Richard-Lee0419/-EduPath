import { apiGet } from './http';

export interface ClassSummary {
  id: number;
  name: string;
  description?: string;
  owner_user_id?: number;
  status: string;
}

export interface ClassCourseSummary {
  class_id: number;
  course_id: number;
  course_code: string;
  course_name: string;
}

export interface ClassInsightMetrics {
  member_count: number;
  assessed_students: number;
  average_score: number;
  completion_rate: number;
  at_risk_students: number;
  activity_count: number;
  last_activity_at?: string;
}

export interface ClassLearnerInsight {
  user_id: number;
  username: string;
  attempt_count: number;
  average_score: number;
  mastery_score: number;
  mastery_points: number;
  risk_level: 'unassessed' | 'high' | 'medium' | 'stable';
  risk_reason: string;
  recommended_action: string;
  last_assessment_at?: string;
}

export interface ClassWeakKnowledgePoint {
  knowledge_point: string;
  average_mastery: number;
  learner_count: number;
  attempt_count: number;
  recommended_action: string;
}

export interface ClassInsightResponse {
  class_info: ClassSummary;
  courses: ClassCourseSummary[];
  metrics: ClassInsightMetrics;
  learners: ClassLearnerInsight[];
  weak_knowledge_points: ClassWeakKnowledgePoint[];
  data_basis: string;
  generated_at: string;
}

export function listClasses() {
  return apiGet<ClassSummary[]>('/classes');
}

export function getClassInsights(classId: number) {
  return apiGet<ClassInsightResponse>(`/classes/${classId}/insights`);
}
