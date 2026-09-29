import type { LearningPath, LearningPathDay, LearningPathTask, PathAdjustmentSignal } from '../types';
import { apiGet, apiPost } from './http';
import { normalizeTaskId } from './task';
import type { TaskCreated } from '@/types/api';

export interface GenerateLearningPathPayload {
  courseIds: number[];
  target: string;
  days: number;
  dailyMinutes: number;
}

export interface GenerateLearningPathResult {
  taskId: string;
}

type RawMap = Record<string, unknown>;

const text = (source: RawMap, key: string, fallback = '') => {
  const value = source[key];
  return value === undefined || value === null || value === '' ? fallback : String(value);
};

const numberValue = (value: unknown, fallback: number) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const stringList = (value: unknown): string[] => Array.isArray(value) ? value.map(String).filter(Boolean) : [];

const record = (value: unknown): RawMap => value && typeof value === 'object' && !Array.isArray(value)
  ? value as RawMap
  : {};

export const mapPathAdjustmentSignal = (value: unknown): PathAdjustmentSignal => {
  const raw = record(value);
  const metrics = record(raw.metrics);
  const pacing = record(raw.pacing);
  const cooldown = record(raw.cooldown);
  const rawStatus = text(raw, 'status', 'stable');
  const status = rawStatus === 'watch' || rawStatus === 'replan_recommended' ? rawStatus : 'stable';
  const rawAction = text(pacing, 'action', 'maintain');
  const action = rawAction === 'reduce' || rawAction === 'increase' ? rawAction : 'maintain';
  return {
    pathAvailable: Boolean(raw.path_available),
    status,
    shouldReplan: Boolean(raw.should_replan),
    replanCandidate: Boolean(raw.replan_candidate),
    riskScore: numberValue(raw.risk_score, 0),
    reasons: stringList(raw.reasons),
    metrics: {
      consecutiveLowEvidence: numberValue(metrics.consecutive_low_evidence, 0),
      lowMasteryPointCount: numberValue(metrics.low_mastery_point_count, 0),
      lowMasteryPoints: stringList(metrics.low_mastery_points),
      inactivityHours: numberValue(metrics.inactivity_hours, 0),
      completionRate: numberValue(metrics.completion_rate, 0),
    },
    pacing: {
      action,
      currentDailyMinutes: numberValue(pacing.current_daily_minutes, 0),
      recommendedDailyMinutes: numberValue(pacing.recommended_daily_minutes, 0),
    },
    cooldown: {
      eligible: Boolean(cooldown.eligible),
      remainingMinutes: numberValue(cooldown.remaining_minutes, 0),
      windowMinutes: numberValue(cooldown.window_minutes, 360),
    },
    evaluatedAt: text(raw, 'evaluated_at'),
  };
};

const mapTask = (raw: RawMap): LearningPathTask => ({
  type: text(raw, 'type'),
  title: text(raw, 'title'),
  estimatedMinutes: numberValue(raw.estimated_minutes, 0),
});

const mapDay = (raw: RawMap): LearningPathDay => ({
  day: numberValue(raw.day, 0),
  theme: text(raw, 'theme'),
  tasks: Array.isArray(raw.tasks) ? raw.tasks.map((item) => mapTask(item as RawMap)) : [],
  expectedOutcome: text(raw, 'expected_outcome'),
  reason: text(raw, 'reason'),
  difficulty: text(raw, 'difficulty'),
  evidenceChunkIds: stringList(raw.evidence_chunk_ids),
});

export const mapLearningPath = (raw: RawMap): LearningPath => ({
  pathId: text(raw, 'path_id'),
  pathTitle: text(raw, 'path_title', text(raw, 'target')),
  target: text(raw, 'target', text(raw, 'path_title')),
  dailyMinutes: numberValue(raw.daily_minutes, 0),
  dailyPlan: Array.isArray(raw.daily_plan) ? raw.daily_plan.map((item) => mapDay(item as RawMap)) : [],
  adjustmentStrategy: text(raw, 'adjustment_strategy'),
  personalizationSummary: text(raw, 'personalization_summary'),
  evidenceChunkIds: stringList(raw.evidence_chunk_ids),
  version: numberValue(raw.version, 0),
  previousVersion: numberValue(raw.previous_version, 0),
  replanTrigger: text(raw, 'replan_trigger', text(raw, 'trigger')),
  sourceEvaluationTaskId: text(raw, 'source_evaluation_task_id'),
  replanChanges: Array.isArray(raw.replan_changes)
    ? raw.replan_changes.map((item) => {
        const change = item as RawMap;
        return {
          action: text(change, 'action'),
          knowledgePoint: text(change, 'knowledge_point'),
          reason: text(change, 'reason'),
        };
      })
    : [],
  completedDays: Array.isArray(raw.completed_days)
    ? raw.completed_days.map((item) => numberValue(item, -1)).filter((item) => item > 0)
    : [],
  adjustmentSignal: mapPathAdjustmentSignal(raw.adjustment_signal),
});

export async function getCurrentLearningPath() {
  const data = await apiGet<RawMap>('/path/current');
  return {
    learningPath: mapLearningPath(data),
    raw: data,
  };
}

export async function generateLearningPath(payload: GenerateLearningPathPayload): Promise<GenerateLearningPathResult> {
  const data = await apiPost<TaskCreated>('/path/generate', {
    course_ids: payload.courseIds,
    target: payload.target,
    days: payload.days,
    daily_minutes: payload.dailyMinutes,
  });
  return {
    taskId: normalizeTaskId(data),
  };
}

export async function getPathAdjustmentSignal() {
  const data = await apiGet<RawMap>('/path/adjustment-signal');
  return mapPathAdjustmentSignal(data);
}

export async function startBehaviorPathReplan() {
  const data = await apiPost<RawMap>('/path/replan/behavior', {});
  return {
    taskId: normalizeTaskId(data),
    reused: Boolean(data.reused),
    triggerKey: text(data, 'trigger_key'),
  };
}

export async function completeLearningPathDay(day: number) {
  return apiPost<{
    path_id: string;
    version: number;
    day: number;
    theme: string;
    completed_days: number[];
    learning_update: import('@/types/api').LearningUpdate;
  }>(`/path/nodes/${day}/complete`, {});
}
