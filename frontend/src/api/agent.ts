import { apiBaseUrl, apiPost } from './http';
import { accessToken } from './session';
import type { ResourceType, TaskCreated } from '@/types/api';
export { getAgentTask } from './task';

export function createResourceTask(payload: {
  course_id: number;
  knowledge_point_ids?: number[];
  knowledge_points?: string[];
  resource_types: ResourceType[];
  difficulty: 'basic' | 'medium' | 'advanced';
  goal?: string;
}) {
  return apiPost<TaskCreated>('/agent/resource-task', payload);
}

export function createTaskStreamUrl(taskId: string) {
  const token = accessToken();
  const query = token ? `?access_token=${encodeURIComponent(token)}` : '';
  return `${apiBaseUrl}/agent/tasks/${taskId}/stream${query}`;
}
