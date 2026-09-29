export type ApiResponse<T> = {
  code: number;
  message: string;
  data: T;
};

export type TaskStatus = "pending" | "running" | "success" | "failed" | "cancelled";
export type ResourceType =
  | "lecture"
  | "mindmap"
  | "quiz"
  | "codelab"
  | "animation_script"
  | "flowchart"
  | "reading";
export type Difficulty = "basic" | "medium" | "advanced";

export type Course = {
  id: number;
  code: "data_structures_algorithms" | "computer_organization";
  name: string;
  description: string;
};

export type KnowledgePoint = {
  id: number;
  name: string;
  difficulty?: Difficulty;
  children?: KnowledgePoint[];
};

export type LoginResult = {
  token: string;
  refresh_token: string;
  expires_at: string;
  user: {
    id: number;
    username: string;
    email?: string;
    role: "student" | "teacher" | "admin";
  };
  email_delivery?: {
    sent: boolean;
    provider: string;
    message: string;
  };
};

export type TaskCreated = {
  task_id: string;
};

export type AgentTask = {
  task_id: string;
  status: TaskStatus;
  progress: number;
  current_agent?: string;
  steps: Array<{
    agent: string;
    status: TaskStatus;
    message: string;
  }>;
  result?: unknown;
  error_message?: string;
  created_at?: string;
  updated_at?: string;
};

export type AgentTaskListItem = {
  task_id: string;
  domain: string;
  operation_type: string;
  status: TaskStatus;
  progress: number;
  current_agent?: string;
  error_message?: string;
  actor_username?: string;
  retryable: boolean;
  created_at?: string;
  updated_at?: string;
};

export type AgentTaskListResponse = {
  items: AgentTaskListItem[];
  total: number;
  page: number;
  size: number;
};

export type ModelRuntime = {
  configured?: boolean;
  real_model_used?: boolean;
  mode: 'real_model' | 'deterministic_fallback';
  provider?: string;
  model?: string;
  call_count?: number;
  total_tokens?: number;
  duration_ms?: number;
  quality_revision_rounds?: number;
  safety_feedback_revision_rounds?: number;
  quality_feedback_revision_rounds?: number;
  quality_revised_resource_count?: number;
  calls?: Array<{
    agent: string;
    provider: string;
    model: string;
    duration_ms: number;
    total_tokens?: number;
  }>;
};

export type GeneratedResource = {
  id: number;
  resource_id?: string;
  title: string;
  type: ResourceType;
  resource_type?: ResourceType;
  course_id?: number;
  knowledge_points?: string[];
  difficulty: Difficulty;
  summary: string;
  created_at?: string;
  object_key?: string;
  object_url?: string | null;
  storage_status?: string;
  personalized_reason?: string;
  estimated_minutes?: number;
  profile_fingerprint?: string;
  quality_evaluation?: ResourceQualityEvaluation;
  generation_mode?: 'real_model' | 'deterministic_fallback' | 'unknown' | string;
};

export type ResourceInteractionSummary = {
  interaction_count: number;
  progress_percent?: number;
  rating?: number;
  last_interaction_at?: string;
  learning_update?: LearningUpdate;
};

export type MasteryUpdate = {
  knowledge_point: string;
  previous_score: number;
  mastery_score: number;
  quiz_attempts: number;
  behavior_weight: number;
  event_type: string;
};

export type LearningUpdate = {
  recorded_events: number;
  duplicate_events: number;
  event_ids: string[];
  mastery_updates: MasteryUpdate[];
  profile_update: Record<string, unknown>;
};

export type ResourceEvidence = {
  chunk_id?: string;
  chunkId?: string;
  title: string;
  content: string;
  score: number;
  source: string;
};

export type ResourceSafety = {
  passed: boolean;
  risk_level?: string;
  riskLevel?: string;
  issues: string[];
  suggestions: string[];
  confidence: number;
};

export type ResourceQualityDimension = {
  score: number;
  weight: number;
  passed: boolean;
  findings: string[];
};

export type ResourceQualityEvaluation = {
  evaluator_version: string;
  total_score: number;
  grade: 'A' | 'B' | 'C' | 'D';
  gate_passed: boolean;
  dimensions: Record<string, ResourceQualityDimension>;
  issues: string[];
  recommendations: string[];
};

export type ResourceDetail = GeneratedResource & {
  status: string;
  content_format: string;
  content: string;
  /** 类型化资源 payload；解析失败或历史资源允许为空并回退 Markdown。 */
  payload?: Record<string, unknown> | null;
  content_schema_version?: string | null;
  evidence: ResourceEvidence[];
  safety: ResourceSafety;
  quality_evaluation?: ResourceQualityEvaluation;
};

export type ResourceListResponse = {
  items: GeneratedResource[];
  page: number;
  size: number;
  total: number;
};

export type ResourceQualityMetrics = {
  source: {
    source_table: string;
    grain: string;
    freshness_at?: string;
    window_days: number;
    eligible_resources: number;
    evaluated_resources: number;
  };
  summary: {
    total_resources: number;
    evaluated_resources: number;
    evaluation_coverage: number;
    average_score: number;
    gate_pass_rate: number;
    alert_count: number;
  };
  trend: Array<{
    date: string;
    resource_count: number;
    average_score: number;
    gate_pass_rate: number;
  }>;
  by_resource_type: ResourceQualityBreakdown[];
  by_generation_mode: ResourceQualityBreakdown[];
  dimensions: Array<{
    dimension: string;
    label: string;
    resource_count: number;
    average_score: number;
    pass_rate: number;
  }>;
  alerts: Array<{
    code: string;
    severity: 'warning' | 'critical';
    title: string;
    message: string;
    observed_value: number;
    threshold: number;
    dimension?: string;
  }>;
  filters: {
    course_id?: number;
    resource_type?: string;
    generation_mode?: string;
    window_days: number;
  };
  metric_definitions: Array<{ key: string; label: string; definition: string }>;
};

export type ResourceQualityBreakdown = {
  key: string;
  resource_count: number;
  average_score: number;
  gate_pass_rate: number;
};

export type ResourceQualityRegressionResult = {
  trigger_alert_codes: string[];
  inspected_resources: number;
  regression_count: number;
  stable_count: number;
  action_required: boolean;
  regressed_resource_ids: string[];
  queued_repair_ids: string[];
  automation_policy: 'audit_only';
  policy_description: string;
  results: Array<{
    resource_id: string;
    evaluator_version: string;
    score_delta: number;
    gate_changed: boolean;
    evaluator_changed: boolean;
    regressed_dimensions: string[];
    regression_detected: boolean;
    baseline_evaluation: ResourceQualityEvaluation;
    current_evaluation: ResourceQualityEvaluation;
  }>;
  completed_at: string;
};

export type ResourceQualityRepairStatus =
  | 'pending_review'
  | 'approved'
  | 'rejected'
  | 'in_progress'
  | 'completed'
  | 'failed'
  | 'published'
  | 'rolled_back';

export type ResourceQualityRepairItem = {
  repair_id: string;
  resource_id: string;
  resource_title: string;
  resource_type: string;
  source_task_id: string;
  course_id: number;
  trigger_alert_codes: string[];
  regressed_dimensions: string[];
  baseline_evaluation: ResourceQualityEvaluation;
  current_evaluation: ResourceQualityEvaluation;
  score_delta: number;
  status: ResourceQualityRepairStatus;
  can_review: boolean;
  can_execute: boolean;
  reviewed_by?: number;
  review_note?: string;
  reviewed_at?: string;
  execution_task_id?: string;
  candidate_quality_evaluation?: ResourceQualityEvaluation;
  candidate_generation_mode?: string;
  publish_ready: boolean;
  execution_error?: string;
  executed_at?: string;
  candidate_base_version?: number;
  can_compare: boolean;
  can_publish: boolean;
  can_rollback: boolean;
  previous_resource_version?: number;
  published_resource_version?: number;
  published_by?: number;
  publish_note?: string;
  published_at?: string;
  rollback_resource_version?: number;
  rolled_back_by?: number;
  rollback_note?: string;
  rolled_back_at?: string;
  created_at: string;
  updated_at: string;
};

export type ResourceQualityRepairList = {
  items: ResourceQualityRepairItem[];
  page: number;
  size: number;
  total: number;
};

export type ResourceQualityRepairDecisionResult = {
  item: ResourceQualityRepairItem;
  next_action: 'ready_for_controlled_repair' | 'closed';
  safety_notice: string;
};

export type ResourceQualityRepairExecutionCreated = {
  repair_id: string;
  task_id: string;
  status: 'in_progress';
};

export type ResourceQualityRepairComparison = {
  repair_id: string;
  resource_id: string;
  current_resource_version: number;
  candidate_base_version?: number;
  stale: boolean;
  original_resource: ResourceDetail & { version: number };
  candidate_resource: GeneratedResource & { content_format: string; content: string };
  current_quality_evaluation: ResourceQualityEvaluation;
  candidate_quality_evaluation: ResourceQualityEvaluation;
  changed_fields: string[];
  can_publish: boolean;
};

export type ResourceQualityRepairPublicationResult = {
  repair_id: string;
  resource_id: string;
  status: 'published' | 'rolled_back';
  from_version: number;
  to_version: number;
  atomic: boolean;
  next_action: 'candidate_published' | 'previous_snapshot_restored';
};

export type TeachingClass = {
  id: number;
  name: string;
  description?: string;
  owner_user_id?: number;
  status: string;
  created_at: string;
  updated_at: string;
};

export type TeachingClassCourse = {
  class_id: number;
  course_id: number;
  course_code: string;
  course_name: string;
  status: string;
  created_at: string;
};

export type ClassLearningInsights = {
  source: {
    source_tables: string;
    window_days: number;
    since: string;
    generated_at: string;
  };
  class_info: TeachingClass;
  courses: TeachingClassCourse[];
  selected_course: TeachingClassCourse;
  summary: {
    student_count: number;
    active_students: number;
    active_rate: number;
    mastery_covered_students: number;
    mastery_coverage: number;
    average_mastery: number;
    average_quiz_score: number;
    learning_event_count: number;
    completed_resources: number;
    critical_students: number;
    attention_students: number;
    insufficient_data_students: number;
  };
  activity_trend: Array<{
    date: string;
    event_count: number;
    active_students: number;
    average_evidence_score: number;
  }>;
  weak_knowledge_points: Array<{
    knowledge_point: string;
    student_count: number;
    average_mastery: number;
    at_risk_students: number;
    attempts: number;
  }>;
  student_risks: Array<{
    user_id: number;
    username: string;
    risk_level: 'critical' | 'attention' | 'insufficient_data' | 'steady';
    reasons: string[];
    mastery_points: number;
    average_mastery?: number;
    average_quiz_score?: number;
    activity_count: number;
    completed_resources: number;
    last_active_at?: string;
  }>;
  recommended_interventions: string[];
};

export type TeacherAssignment = {
  id: number;
  class_id: number;
  course_id: number;
  teacher_user_id: number;
  title: string;
  instructions?: string;
  resource_id?: string;
  due_at?: string;
  status: 'draft' | 'published' | 'archived' | string;
  published_at?: string;
  created_at: string;
  updated_at: string;
  assigned_count: number;
  completed_count: number;
};

export type StudentAssignment = {
  id: number;
  class_id: number;
  class_name: string;
  course_id: number;
  course_name: string;
  title: string;
  instructions?: string;
  resource_id?: string;
  due_at?: string;
  assignment_status: 'published' | string;
  target_status: 'assigned' | 'completed' | string;
  completed_at?: string;
  score?: number;
  published_at?: string;
};

export type AssignmentProgress = {
  assignment_id: number;
  student_id: number;
  username: string;
  status: 'assigned' | 'completed' | string;
  completed_at?: string;
  score?: number;
};
