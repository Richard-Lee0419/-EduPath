import type { TutorAnswer, TutorCitation, TutorEvidence } from '../types';
import { apiPost } from './http';
import { normalizeTaskId, waitForAgentTask, type TaskUpdateHandler } from './task';
import type { LearningUpdate, ModelRuntime, ResourceSafety, TaskCreated } from '@/types/api';

export interface TutorChatPayload {
  courseId: number;
  question: string;
  answerMode: 'hint' | 'socratic' | 'step_by_step' | 'summary' | 'code_first';
  sessionId?: string;
}

const stringList = (value: unknown): string[] => {
  if (!Array.isArray(value)) return [];
  return value.map(String).filter(Boolean);
};

const record = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value ? (value as Record<string, unknown>) : {};

const mapEvidence = (value: unknown): TutorEvidence[] => {
  if (!Array.isArray(value)) return [];
  return value.map(record).map((item) => ({
    chunkId: String(item.chunk_id || item.chunkId || ''),
    title: String(item.title || '课程知识库证据'),
    content: String(item.content || ''),
    score: Number(item.score || 0),
    source: String(item.source || ''),
  })).filter((item) => item.chunkId);
};

const mapCitations = (value: unknown): TutorCitation[] => {
  if (!Array.isArray(value)) return [];
  return value.map(record).map((item) => ({
    answerFragment: String(item.answer_fragment || item.answerFragment || ''),
    evidenceChunkIds: stringList(item.evidence_chunk_ids || item.evidenceChunkIds),
  })).filter((item) => item.answerFragment && item.evidenceChunkIds.length);
};

export const mapTutorAnswer = (raw: unknown, question: string): TutorAnswer => {
  const result = (raw || {}) as Record<string, unknown>;
  const answer = String(result.answer_markdown || result.answer || result.content || '');
  const evidence = mapEvidence(result.evidence);
  const runtime = record(result.model_runtime || result.modelRuntime);
  const safety = record(result.safety);
  const learningUpdate = record(result.learning_update || result.learningUpdate);
  return {
    answerId: String(result.task_id || result.session_id || ''),
    sessionId: String(result.session_id || ''),
    question,
    answerMarkdown: answer,
    steps: stringList(result.steps).length
      ? stringList(result.steps)
      : answer
          .split(/\n+/)
          .map((line) => line.replace(/^[-*\d.、\s]+/, '').trim())
          .filter(Boolean)
          .slice(0, 4),
    evidenceChunkIds: evidence.map((item) => item.chunkId),
    confidence: Number(result.confidence ?? 0),
    citations: mapCitations(result.citations),
    evidence,
    safety: Object.keys(safety).length
      ? {
          passed: Boolean(safety.passed),
          risk_level: String(safety.risk_level || safety.riskLevel || ''),
          issues: stringList(safety.issues),
          suggestions: stringList(safety.suggestions),
          confidence: Number(safety.confidence || 0),
        } satisfies ResourceSafety
      : null,
    generationMode: result.generation_mode === 'real_model' || result.generation_mode === 'deterministic_fallback'
      ? result.generation_mode
      : '',
    modelRuntime: Object.keys(runtime).length ? runtime as ModelRuntime : null,
    learningUpdate: Object.keys(learningUpdate).length ? learningUpdate as LearningUpdate : null,
  };
};

export async function chatWithTutor(payload: TutorChatPayload, onTaskUpdate?: TaskUpdateHandler) {
  const created = await apiPost<TaskCreated>('/tutor/chat', {
    course_id: payload.courseId,
    question: payload.question,
    answer_mode: payload.answerMode,
    session_id: payload.sessionId,
  });
  const taskId = normalizeTaskId(created);
  const task = await waitForAgentTask(taskId, onTaskUpdate);
  return {
    taskId,
    answer: {
      ...mapTutorAnswer(task.result, payload.question),
      answerId: taskId,
    },
  };
}
