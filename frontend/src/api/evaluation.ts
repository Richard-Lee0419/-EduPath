import type { EvaluationReport } from '../types';
import { apiGet } from './http';
import { submitQuiz } from './quiz';

export interface EvaluationAnswer {
  questionId: number;
  answer: string;
}

export interface AnalyzeEvaluationPayload {
  quizId: number;
  studentId: string;
  answers: EvaluationAnswer[];
}

type RawMap = Record<string, unknown>;

const stringList = (value: unknown): string[] => {
  if (!Array.isArray(value)) return [];
  return value.map(String).filter(Boolean);
};

export const mapEvaluationReport = (raw: RawMap): EvaluationReport => ({
  reportId: String(raw.report_id || ''),
  studentId: String(raw.student_id || ''),
  quizId: Number(raw.quiz_id || 0),
  overallScore: Number(raw.overall_score || raw.score || 0),
  mastery: Array.isArray(raw.mastery)
    ? raw.mastery.map((item) => {
        const mastery = item as RawMap;
        return {
          knowledgePoint: String(mastery.knowledge_point || mastery.name || ''),
          masteryScore: Number(mastery.mastery_score || mastery.score || 0),
          level: String(mastery.level || ''),
        };
      })
    : [],
  weakPoints: stringList(raw.weak_points),
  mistakePatterns: stringList(raw.mistake_patterns),
  recommendation: stringList(raw.next_actions).join('；') || String(raw.recommendation || ''),
});

export async function getEvaluationReport() {
  const data = await apiGet<RawMap>('/evaluation/report');
  return mapEvaluationReport(data);
}

export async function analyzeEvaluation(payload: AnalyzeEvaluationPayload) {
  const result = await submitQuiz(
    payload.quizId,
    payload.answers.map((answer) => ({
      question_id: answer.questionId,
      answer: answer.answer,
    })),
  );

  return {
    taskId: result.taskId,
    quizResult: result,
    evaluationReport: await getEvaluationReport(),
  };
}
