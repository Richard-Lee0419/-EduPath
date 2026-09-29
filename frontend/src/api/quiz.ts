import { apiPost } from './http';
import type { ModelRuntime, ResourceEvidence, ResourceSafety } from '../types/api';

export type QuizQuestion = {
  question_id: number;
  stem: string;
  type: 'single_choice';
  difficulty?: string;
  knowledge_point?: string;
  options: string[];
  evidence_chunk_ids: string[];
};

export type QuizResponse = {
  taskId: string;
  quiz_id: number;
  title: string;
  questions: QuizQuestion[];
  evidence: ResourceEvidence[];
  safety: ResourceSafety | null;
  generationMode: ModelRuntime['mode'] | '';
  modelRuntime: ModelRuntime | null;
  sourceTaskId: string;
};

export type QuizQuestionResult = {
  questionId: number;
  knowledgePoint: string;
  stem: string;
  submittedAnswer: string;
  correctAnswer: string;
  explanation: string;
  correct: boolean;
};

export type QuizEvaluation = {
  overallScore: number;
  weakPoints: string[];
  mistakePatterns: string[];
  nextActions: string[];
  summary: string;
};

export type PathAdjustment = {
  action: 'insert_remediation' | 'reorder' | 'adjust_difficulty' | 'advance' | string;
  knowledgePoint: string;
  reason: string;
};

export type QuizPathUpdate = {
  updated: boolean;
  pathId: string;
  version: number;
  previousVersion: number;
  trigger: string;
  sourceEvaluationTaskId: string;
  changes: PathAdjustment[];
};

export type QuizSubmitResult = {
  taskId: string;
  score: number;
  correctCount: number;
  totalCount: number;
  questionResults: QuizQuestionResult[];
  evaluation: QuizEvaluation;
  profileUpdate: Record<string, unknown>;
  pathUpdate: QuizPathUpdate;
  evidence: ResourceEvidence[];
  safety: ResourceSafety | null;
  generationMode: ModelRuntime['mode'] | '';
  modelRuntime: ModelRuntime | null;
  sourceTaskId: string;
};

type RawMap = Record<string, unknown>;

type RawQuizQuestion = RawMap & {
  question_id?: number;
  questionId?: number;
  stem?: string;
  question?: string;
};

export type GenerateQuizPayload = {
  courseId: number;
  knowledgePointIds?: number[];
  knowledgePoints?: string[];
  difficulty?: 'basic' | 'medium' | 'advanced';
  questionCount?: number;
};

const stringList = (value: unknown): string[] => Array.isArray(value) ? value.map(String).filter(Boolean) : [];
const mapEvidence = (value: unknown): ResourceEvidence[] => Array.isArray(value)
  ? value.map((item) => {
      const raw = item as RawMap;
      return {
        chunk_id: String(raw.chunk_id || ''),
        title: String(raw.title || ''),
        content: String(raw.content || ''),
        score: Number(raw.score || 0),
        source: String(raw.source || ''),
      };
    })
  : [];

const asMap = (value: unknown): RawMap => value && typeof value === 'object' ? value as RawMap : {};

export async function generateQuiz(payload: GenerateQuizPayload): Promise<QuizResponse> {
  const questionCount = payload.questionCount ?? 3;
  if (questionCount < 1 || questionCount > 3) {
    throw new Error('专项小测仅允许生成 1 至 3 道题');
  }
  // 测验接口按 API 契约同步返回题目，不创建 agent_task，也不会返回 task_id。
  const data = asMap(await apiPost<RawMap>('/quiz/generate', {
    course_id: payload.courseId,
    knowledge_point_ids: payload.knowledgePointIds ?? [],
    knowledge_points: payload.knowledgePoints ?? [],
    difficulty: payload.difficulty ?? 'basic',
    question_count: questionCount,
  }));
  const rawQuestions = Array.isArray(data.questions) ? data.questions as RawQuizQuestion[] : [];
  return {
    taskId: '',
    quiz_id: Number(data.quiz_id ?? data.quizId ?? 0),
    title: String(data.title || ''),
    questions: rawQuestions.map((question) => ({
      question_id: Number(question.question_id ?? question.questionId ?? 0),
      stem: String(question.stem || question.question || ''),
      type: 'single_choice',
      difficulty: String(question.difficulty || ''),
      knowledge_point: String(question.knowledge_point ?? question.knowledgePoint ?? ''),
      options: stringList(question.options),
      evidence_chunk_ids: stringList(question.evidence_chunk_ids),
    })),
    evidence: mapEvidence(data.evidence),
    safety: data.safety ? data.safety as ResourceSafety : null,
    generationMode: String(data.generation_mode || '') as QuizResponse['generationMode'],
    modelRuntime: data.model_runtime ? data.model_runtime as ModelRuntime : null,
    sourceTaskId: String(data.source_task_id || ''),
  };
}

export async function submitQuiz(
  quizId: number,
  answers: Array<{ question_id: number; answer: string }>,
): Promise<QuizSubmitResult> {
  if (answers.length < 1 || answers.length > 3) {
    throw new Error('专项小测仅允许提交 1 至 3 道题');
  }
  // 提交同样是同步事务：判分、画像回写和路径更新在一次请求中完成。
  const data = asMap(await apiPost<RawMap>('/quiz/submit', {
    quiz_id: quizId,
    answers,
  }));
  const evaluation = asMap(data.evaluation);
  const pathUpdate = asMap(data.path_update);
  const rawQuestionResults = Array.isArray(data.question_results) ? data.question_results as RawMap[] : [];
  const score = Number(data.score || 0);
  const totalCount = answers.length;
  const weakPoints = stringList(data.weak_points);
  const mistakePatterns = stringList(data.mistake_patterns);
  const recommendation = String(data.recommendation || '');
  const remedialNodes = Array.isArray(pathUpdate.remedial_nodes) ? pathUpdate.remedial_nodes as RawMap[] : [];
  return {
    taskId: '',
    score,
    correctCount: Number(data.correct_count ?? Math.round((score / 100) * totalCount)),
    totalCount: Number(data.total_count ?? totalCount),
    questionResults: rawQuestionResults.map((item) => ({
      questionId: Number(item.question_id || 0),
      knowledgePoint: String(item.knowledge_point || ''),
      stem: String(item.stem || ''),
      submittedAnswer: String(item.submitted_answer || ''),
      correctAnswer: String(item.correct_answer || ''),
      explanation: String(item.explanation || ''),
      correct: Boolean(item.correct),
    })),
    evaluation: {
      overallScore: Number(evaluation.overall_score ?? score),
      weakPoints: stringList(evaluation.weak_points ?? weakPoints),
      mistakePatterns: stringList(evaluation.mistake_patterns ?? mistakePatterns),
      nextActions: stringList(evaluation.next_actions ?? (recommendation ? [recommendation] : [])),
      summary: String(evaluation.summary || recommendation || '评估与画像已更新'),
    },
    profileUpdate: asMap(data.profile_update),
    pathUpdate: {
      updated: Boolean(pathUpdate.updated ?? (remedialNodes.length > 0 || Number(pathUpdate.version || 0) > Number(pathUpdate.previous_version || 0))),
      pathId: String(pathUpdate.path_id || ''),
      version: Number(pathUpdate.version || 0),
      previousVersion: Number(pathUpdate.previous_version || 0),
      trigger: String(pathUpdate.trigger || pathUpdate.replan_trigger || ''),
      sourceEvaluationTaskId: String(pathUpdate.source_evaluation_task_id || pathUpdate.source_attempt_id || ''),
      changes: Array.isArray(pathUpdate.changes)
        ? pathUpdate.changes.map((item) => {
            const change = asMap(item);
            return {
              action: String(change.action || ''),
              knowledgePoint: String(change.knowledge_point || ''),
              reason: String(change.reason || ''),
            };
          })
        : remedialNodes.map((item) => ({
            action: 'insert_remediation',
            knowledgePoint: String(item.knowledge_point || ''),
            reason: String(item.reason || item.status || '待完成补救资源'),
          })),
    },
    evidence: mapEvidence(data.evidence),
    safety: data.safety ? data.safety as ResourceSafety : null,
    generationMode: String(data.generation_mode || '') as QuizSubmitResult['generationMode'],
    modelRuntime: data.model_runtime ? data.model_runtime as ModelRuntime : null,
    sourceTaskId: String(data.source_task_id || ''),
  };
}
