export type PageKey =
  | 'welcome'
  | 'login'
  | 'register'
  | 'dashboard'
  | 'learningHome'
  | 'profile'
  | 'resources'
  | 'resourceCenter'
  | 'learningPath'
  | 'knowledgeBase'
  | 'tutor'
  | 'quiz'
  | 'evaluation'
  | 'teacherInsights'
  | 'taskCenter'
  | 'userCenter';

export type UserRole = 'student' | 'teacher' | 'admin';

export interface User {
  id: number;
  username: string;
  email?: string;
  role: UserRole;
  name: string;
  major?: string;
  grade?: string;
  coursePreferences?: string[];
  lastLoginAt?: string;
}

export interface LoginPayload {
  username: string;
  password: string;
}

export interface RegisterPayload {
  email: string;
  password: string;
  name?: string;
}

export type AgentStatus = 'pending' | 'running' | 'success' | 'failed' | 'cancelled';

export interface AgentStep {
  agent: string;
  title: string;
  status: AgentStatus;
  message: string;
}

export interface ResourceCard {
  id: string;
  type: 'lecture' | 'mindmap' | 'quiz' | 'codelab' | 'animation_script' | 'flowchart' | 'reading';
  title: string;
  subtitle: string;
  tags: string[];
  difficulty?: string;
  minutes?: number;
  confidence?: number;
  personalizedReason?: string;
  profileFingerprint?: string;
  qualityScore?: number;
  qualityGrade?: 'A' | 'B' | 'C' | 'D';
  qualityGatePassed?: boolean;
  accent: string;
}

export interface MasteryPoint {
  name: string;
  score: number;
  course: string;
}

export interface ChatMessage {
  id: string;
  role: 'student' | 'agent';
  name: string;
  content: string;
}

export interface Profile {
  studentId: string;
  studentName: string;
  major: string;
  grade: string;
  targetCourses: string[];
  learningGoal: string;
  weakPoints: string[];
  resourcePreference: string[];
  cognitiveStyle: string[];
  learningPace: string;
  confidenceScore: number;
}

export interface LearningPathTask {
  type: string;
  title: string;
  estimatedMinutes: number;
}

export interface LearningPathDay {
  day: number;
  theme: string;
  tasks: LearningPathTask[];
  expectedOutcome: string;
  reason: string;
  difficulty: string;
  evidenceChunkIds: string[];
}

export type PathAdjustmentStatus = 'stable' | 'watch' | 'replan_recommended';

export interface PathAdjustmentSignal {
  pathAvailable: boolean;
  status: PathAdjustmentStatus;
  shouldReplan: boolean;
  replanCandidate: boolean;
  riskScore: number;
  reasons: string[];
  metrics: {
    consecutiveLowEvidence: number;
    lowMasteryPointCount: number;
    lowMasteryPoints: string[];
    inactivityHours: number;
    completionRate: number;
  };
  pacing: {
    action: 'maintain' | 'reduce' | 'increase';
    currentDailyMinutes: number;
    recommendedDailyMinutes: number;
  };
  cooldown: {
    eligible: boolean;
    remainingMinutes: number;
    windowMinutes: number;
  };
  evaluatedAt: string;
}

export interface LearningPath {
  pathId: string;
  pathTitle: string;
  target: string;
  dailyMinutes: number;
  dailyPlan: LearningPathDay[];
  adjustmentStrategy: string;
  personalizationSummary: string;
  evidenceChunkIds: string[];
  version: number;
  previousVersion: number;
  replanTrigger: string;
  sourceEvaluationTaskId: string;
  replanChanges: Array<{
    action: string;
    knowledgePoint: string;
    reason: string;
  }>;
  completedDays: number[];
  adjustmentSignal: PathAdjustmentSignal;
}

export interface TutorAnswer {
  answerId: string;
  sessionId: string;
  question: string;
  answerMarkdown: string;
  steps: string[];
  evidenceChunkIds: string[];
  confidence: number;
  citations: TutorCitation[];
  evidence: TutorEvidence[];
  safety: ResourceSafety | null;
  generationMode: 'real_model' | 'deterministic_fallback' | '';
  modelRuntime: ModelRuntime | null;
  learningUpdate: LearningUpdate | null;
}

export interface TutorCitation {
  answerFragment: string;
  evidenceChunkIds: string[];
}

export interface TutorEvidence {
  chunkId: string;
  title: string;
  content: string;
  score: number;
  source: string;
}

export interface EvaluationMastery {
  knowledgePoint: string;
  masteryScore: number;
  level: string;
}

export interface EvaluationReport {
  reportId: string;
  studentId: string;
  quizId: number;
  overallScore: number;
  mastery: EvaluationMastery[];
  weakPoints: string[];
  mistakePatterns: string[];
  recommendation: string;
}

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}
import type { LearningUpdate, ModelRuntime, ResourceSafety } from './types/api';
