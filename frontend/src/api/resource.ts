import type { AgentStep, ResourceCard } from '../types';
import { apiGet, apiPatch, apiPost } from './http';
import { normalizeTaskId, toAgentSteps } from './task';
import type {
  AgentTask,
  GeneratedResource,
  ModelRuntime,
  ResourceDetail,
  ResourceListResponse,
  ResourceInteractionSummary,
  ResourceQualityMetrics,
  ResourceQualityRepairDecisionResult,
  ResourceQualityRepairComparison,
  ResourceQualityRepairExecutionCreated,
  ResourceQualityRepairList,
  ResourceQualityRepairPublicationResult,
  ResourceQualityRegressionResult,
  ResourceType,
  TaskCreated,
} from '@/types/api';

export interface GenerateResourcesPayload {
  courseId: number;
  knowledgePoints: string[];
  goal: string;
  resourceTypes: ResourceCard['type'][];
  difficulty: 'basic' | 'medium' | 'advanced';
}

export interface GenerateResourcesResult {
  taskId: string;
  status: 'success' | 'running' | 'failed';
  agentTrace: AgentStep[];
  resources: ResourceCard[];
  modelRuntime?: ModelRuntime;
}

const typeLabels: Record<ResourceCard['type'], string> = {
  lecture: '个性化讲义',
  mindmap: '思维导图',
  quiz: '题库',
  codelab: '代码实验',
  animation_script: '动画脚本',
  flowchart: '流程图',
  reading: '拓展阅读',
};

const tones = ['teal', 'amber', 'blue', 'coral'] as const;

const normalizeType = (type?: string): ResourceCard['type'] => {
  const candidate = (type || 'lecture') as ResourceCard['type'];
  return candidate in typeLabels ? candidate : 'lecture';
};

export const mapResourceCard = (item: GeneratedResource, index = 0): ResourceCard => {
  const type = normalizeType(item.resource_type || item.type);
  return {
    id: item.resource_id || String(item.id),
    type,
    title: item.title,
    subtitle: item.summary || typeLabels[type],
    tags: item.knowledge_points?.length ? item.knowledge_points : [typeLabels[type]],
    difficulty: item.difficulty,
    minutes: item.estimated_minutes,
    personalizedReason: item.personalized_reason,
    profileFingerprint: item.profile_fingerprint,
    qualityScore: item.quality_evaluation?.total_score,
    qualityGrade: item.quality_evaluation?.grade,
    qualityGatePassed: item.quality_evaluation?.gate_passed,
    accent: tones[index % tones.length],
  };
};

/**
 * 同一用户重复点击生成时，后端会保留历史版本；学生资源库只展示最新同类资源，
 * 避免相同资源包在页面上重复堆叠。历史记录仍保留在服务端，可继续通过任务记录追溯。
 */
const dedupeResourceCards = (items: ResourceCard[]) => {
  const seen = new Set<string>();
  return items.filter((item) => {
    const key = `${item.type}::${item.title.trim().toLocaleLowerCase()}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
};

export async function listResources(params: { page?: number; size?: number; courseId?: number } = {}) {
  const data = await apiGet<ResourceListResponse>('/resources', {
    page: params.page ?? 1,
    size: params.size ?? 10,
    course_id: params.courseId,
  });

  const items = dedupeResourceCards(data.items.map(mapResourceCard));
  return {
    items,
    page: data.page,
    size: data.size,
    total: items.length,
  };
}

export async function getResourceDetail(resourceId: string) {
  return apiGet<ResourceDetail>(`/resources/${encodeURIComponent(resourceId)}`);
}

export async function recordResourceInteraction(
  resourceId: string,
  payload: { action: 'view' | 'start' | 'complete' | 'skip' | 'favorite' | 'rate'; rating?: number; progressPercent?: number; eventId?: string },
) {
  return apiPost<ResourceInteractionSummary>(`/resources/${encodeURIComponent(resourceId)}/interactions`, {
    action: payload.action,
    rating: payload.rating,
    progress_percent: payload.progressPercent,
    event_id: payload.eventId,
  });
}

export interface QuizSubmissionView {
  total: number;
  score: number | null;
  message: string;
  items: Array<{
    question_id: string;
    correct: boolean | null;
    correct_answer: string;
    explanation: string;
    knowledge_point: string;
    diagnostic_label: string;
  }>;
}

/** 提交资源练习题；正确答案与解析只由本接口返回（提交后展示）。 */
export async function submitResourceQuiz(
  resourceId: string,
  answers: Array<{ question_id: string; answer: string }>,
) {
  return apiPost<QuizSubmissionView>(`/resources/${encodeURIComponent(resourceId)}/quiz/submit`, {
    answers,
  });
}

export async function generateResources(payload: GenerateResourcesPayload): Promise<GenerateResourcesResult> {
  const data = await apiPost<TaskCreated>('/agent/resource-task', {
    course_id: payload.courseId,
    knowledge_point_ids: [],
    knowledge_points: payload.knowledgePoints,
    resource_types: payload.resourceTypes as ResourceType[],
    difficulty: payload.difficulty,
    goal: payload.goal,
  });

  return {
    taskId: normalizeTaskId(data),
    status: 'running',
    agentTrace: [],
    resources: [],
    modelRuntime: undefined,
  };
}

export async function getResourceQualityMetrics(params: {
  courseId?: number;
  resourceType?: string;
  generationMode?: string;
  windowDays?: number;
} = {}) {
  return apiGet<ResourceQualityMetrics>('/resources/quality-metrics', {
    course_id: params.courseId,
    resource_type: params.resourceType,
    generation_mode: params.generationMode,
    window_days: params.windowDays ?? 30,
  });
}

export async function createResourceQualityRegressionTask(payload: {
  courseId?: number;
  resourceType?: string;
  generationMode?: string;
  windowDays?: number;
  maxResources?: number;
}) {
  return apiPost<TaskCreated>('/resources/quality-regression-tasks', {
    course_id: payload.courseId,
    resource_type: payload.resourceType,
    generation_mode: payload.generationMode,
    window_days: payload.windowDays ?? 30,
    max_resources: Math.min(payload.maxResources ?? 5, 10),
    force: false,
  });
}

export const qualityRegressionResultFromTask = (task: AgentTask) =>
  (task.result || null) as ResourceQualityRegressionResult | null;

export async function listResourceQualityRepairs(params: {
  courseId?: number;
  status?: string;
  page?: number;
  size?: number;
} = {}) {
  return apiGet<ResourceQualityRepairList>('/resources/quality-repairs', {
    course_id: params.courseId,
    status: params.status,
    page: params.page ?? 1,
    size: params.size ?? 10,
  });
}

export async function decideResourceQualityRepair(
  repairId: string,
  payload: { decision: 'approve' | 'reject'; note?: string },
) {
  return apiPatch<ResourceQualityRepairDecisionResult>(
    `/resources/quality-repairs/${encodeURIComponent(repairId)}/decision`,
    payload,
  );
}

export async function executeResourceQualityRepair(repairId: string) {
  return apiPost<ResourceQualityRepairExecutionCreated>(
    `/resources/quality-repairs/${encodeURIComponent(repairId)}/execute`,
  );
}

export async function getResourceQualityRepairComparison(repairId: string) {
  return apiGet<ResourceQualityRepairComparison>(
    `/resources/quality-repairs/${encodeURIComponent(repairId)}/comparison`,
  );
}

export async function publishResourceQualityRepair(
  repairId: string,
  payload: { expectedResourceVersion: number; note?: string },
) {
  return apiPost<ResourceQualityRepairPublicationResult>(
    `/resources/quality-repairs/${encodeURIComponent(repairId)}/publish`,
    {
      expected_resource_version: payload.expectedResourceVersion,
      note: payload.note,
    },
  );
}

export async function rollbackResourceQualityRepair(repairId: string, note?: string) {
  return apiPost<ResourceQualityRepairPublicationResult>(
    `/resources/quality-repairs/${encodeURIComponent(repairId)}/rollback`,
    { note },
  );
}

export const resourcesFromTask = (task: AgentTask): GenerateResourcesResult => {
  const result = (task.result || {}) as {
    resources?: GeneratedResource[];
    model_runtime?: ModelRuntime;
  };
  return {
    taskId: task.task_id,
    status: task.status === 'failed' ? 'failed' : task.status === 'success' ? 'success' : 'running',
    agentTrace: toAgentSteps(task),
    resources: dedupeResourceCards((result.resources || []).map(mapResourceCard)),
    modelRuntime: result.model_runtime,
  };
};
