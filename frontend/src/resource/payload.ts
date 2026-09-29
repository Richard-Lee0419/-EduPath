/**
 * 资源类型化 payload 的纯解析层（W03 五类资源闭环）。
 *
 * 只做确定性结构判断，不依赖 Vue / 浏览器 API，可被 Node 直接运行测试。
 * 核心原则：
 * - payload 缺失 / malformed / 类型不符 ⇒ 一律返回 null，调用方回退到 markdown 正文渲染；
 * - 五类 payload 各自的字段结构在这里归一化，renderer 不再各自猜字段；
 * - codelab 的 execution.supported=false 是硬边界：任何地方都不得伪造执行结果。
 */

import type { ResourceType } from '../types/api';

export interface LecturePayload {
  kind: 'lecture';
  learningObjective: string[];
  prerequisites: string[];
  sections: Array<{ heading: string; body: string }>;
  workedExamples: Array<{ title: string; problem: string; solution: string }>;
  commonMistakes: string[];
  selfCheck: Array<{ question: string; hint: string }>;
  evidenceRefs: string[];
}

export interface MindmapPayload {
  kind: 'mindmap';
  centralConcept: string;
  nodes: Array<{ id: string; label: string; parentId: string }>;
  relations: Array<{ from: string; to: string; label: string }>;
  pitfalls: string[];
  mermaid: string | null;
}

export interface QuizOption {
  key: string;
  text: string;
}

export interface QuizQuestion {
  questionId: string;
  question: string;
  questionType: 'single_choice' | 'open';
  options: QuizOption[];
  /** 服务端在详情中剥离答案，这里永远为 null（提交接口返回后另行展示）。 */
  correctAnswer: null;
  knowledgePoint: string;
  difficulty: string;
}

export interface QuizPayload {
  kind: 'quiz';
  answersHidden: boolean;
  questions: QuizQuestion[];
}

export interface CodelabPayload {
  kind: 'codelab';
  goal: string;
  prerequisites: string[];
  language: string;
  starterCode: string;
  steps: string[];
  sampleInput: string;
  expectedOutput: string;
  explanation: string;
  executionSupported: false;
  executionNote: string;
}

export interface AnimationScene {
  sceneNo: string;
  title: string;
  narration: string;
  visualAction: string;
  durationSeconds: number | null;
}

export interface AnimationScriptPayload {
  kind: 'animation_script';
  learningGoal: string;
  mediaNote: string;
  scenes: AnimationScene[];
  states: string[];
  evidenceRefs: string[];
}

export type ResourcePayload =
  | LecturePayload
  | MindmapPayload
  | QuizPayload
  | CodelabPayload
  | AnimationScriptPayload;

const asString = (value: unknown, fallback = ''): string =>
  typeof value === 'string' ? value : typeof value === 'number' ? String(value) : fallback;

const asStringList = (value: unknown): string[] =>
  Array.isArray(value) ? value.map((item) => asString(item)).filter(Boolean) : [];

const asRecord = (value: unknown): Record<string, unknown> | null =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null;

export const RENDERABLE_RESOURCE_TYPES: ResourceType[] = [
  'lecture',
  'mindmap',
  'quiz',
  'codelab',
  'animation_script',
];

/** payload 是否可按类型化渲染；不可渲染（缺失/malformed/未知类型）时回退 markdown。 */
export function parseResourcePayload(
  resourceType: string | undefined | null,
  payload: Record<string, unknown> | null | undefined,
): ResourcePayload | null {
  if (!payload || typeof payload !== 'object') return null;
  switch (resourceType) {
    case 'lecture':
    case 'reading':
      return parseLecture(payload);
    case 'mindmap':
    case 'flowchart':
      return parseMindmap(payload);
    case 'quiz':
      return parseQuiz(payload);
    case 'codelab':
      return parseCodelab(payload);
    case 'animation_script':
      return parseAnimationScript(payload);
    default:
      return null;
  }
}

function parseLecture(payload: Record<string, unknown>): LecturePayload | null {
  const sections = Array.isArray(payload.sections)
    ? payload.sections
        .map(asRecord)
        .filter((item): item is Record<string, unknown> => item !== null)
        .map((item) => ({ heading: asString(item.heading), body: asString(item.body) }))
        .filter((item) => item.heading || item.body)
    : [];
  const objectives = asStringList(payload.learning_objective);
  if (!sections.length && !objectives.length) return null;
  const examples = Array.isArray(payload.worked_examples)
    ? payload.worked_examples
        .map(asRecord)
        .filter((item): item is Record<string, unknown> => item !== null)
        .map((item) => ({
          title: asString(item.title, '例题'),
          problem: asString(item.problem),
          solution: asString(item.solution),
        }))
    : [];
  return {
    kind: 'lecture',
    learningObjective: objectives,
    prerequisites: asStringList(payload.prerequisites),
    sections,
    workedExamples: examples,
    commonMistakes: asStringList(payload.common_mistakes),
    selfCheck: Array.isArray(payload.self_check)
      ? payload.self_check
          .map(asRecord)
          .filter((item): item is Record<string, unknown> => item !== null)
          .map((item) => ({ question: asString(item.question), hint: asString(item.hint) }))
          .filter((item) => item.question)
      : [],
    evidenceRefs: asStringList(payload.evidence_refs),
  };
}

function parseMindmap(payload: Record<string, unknown>): MindmapPayload | null {
  const central = asString(payload.central_concept);
  const nodes = Array.isArray(payload.nodes)
    ? payload.nodes
        .map(asRecord)
        .filter((item): item is Record<string, unknown> => item !== null)
        .map((item) => ({
          id: asString(item.id),
          label: asString(item.label),
          parentId: asString(item.parent_id),
        }))
        .filter((item) => item.id || item.label)
    : [];
  if (!central && !nodes.length) return null;
  const mermaidRaw = asString(payload.mermaid);
  return {
    kind: 'mindmap',
    centralConcept: central,
    nodes: nodes.length ? nodes : [{ id: 'root', label: central, parentId: '' }],
    relations: Array.isArray(payload.relations)
      ? payload.relations
          .map(asRecord)
          .filter((item): item is Record<string, unknown> => item !== null)
          .map((item) => ({
            from: asString(item.from),
            to: asString(item.to),
            label: asString(item.label),
          }))
      : [],
    pitfalls: asStringList(payload.pitfalls),
    mermaid: mermaidRaw || null,
  };
}

function parseQuiz(payload: Record<string, unknown>): QuizPayload | null {
  const rawQuestions = Array.isArray(payload.questions) ? payload.questions : [];
  if (!rawQuestions.length) return null;
  const questions: QuizQuestion[] = [];
  for (const rawItem of rawQuestions) {
    const item = asRecord(rawItem);
    if (!item) continue;
    const questionText = asString(item.question);
    const questionId = asString(item.question_id);
    // 全空字段的坏数据题直接丢弃，避免渲染出无意义的空题。
    if (!questionText && !questionId) continue;
    const rawOptions = Array.isArray(item.options) ? item.options : [];
    const options: QuizOption[] = rawOptions
      .map(asRecord)
      .filter((option): option is Record<string, unknown> => option !== null)
      .map((option) => ({ key: asString(option.key), text: asString(option.text) }))
      .filter((option) => option.key || option.text);
    questions.push({
      questionId,
      question: questionText,
      questionType: options.length ? 'single_choice' : 'open',
      options,
      // 防泄露：详情响应中服务端已剥离答案；这里显式置 null，杜绝旧字段误用。
      correctAnswer: null,
      knowledgePoint: asString(item.knowledge_point),
      difficulty: asString(item.difficulty),
    });
  }
  if (!questions.length) return null;
  return {
    kind: 'quiz',
    answersHidden: payload.answers_hidden !== false,
    questions,
  };
}

function parseCodelab(payload: Record<string, unknown>): CodelabPayload | null {
  const starterCode = asString(payload.starter_code);
  const goal = asString(payload.goal);
  const steps = asStringList(payload.steps);
  if (!starterCode && !goal && !steps.length) return null;
  const execution = asRecord(payload.execution);
  // 硬边界：没有 runtime 就不得伪造执行结果。supported 缺失一律视为 false。
  return {
    kind: 'codelab',
    goal,
    prerequisites: asStringList(payload.prerequisites),
    language: asString(payload.language, 'text'),
    starterCode,
    steps,
    sampleInput: asString(payload.sample_input),
    expectedOutput: asString(payload.expected_output),
    explanation: asString(payload.explanation),
    executionSupported: false,
    executionNote: asString(execution?.note, '当前环境未提供在线代码执行，请对照参考预期输出自行验证。'),
  };
}

function parseAnimationScript(payload: Record<string, unknown>): AnimationScriptPayload | null {
  const scenes = Array.isArray(payload.scenes)
    ? payload.scenes
        .map(asRecord)
        .filter((item): item is Record<string, unknown> => item !== null)
        .map((item) => {
          const duration = item.duration_seconds;
          return {
            sceneNo: asString(item.scene_no),
            title: asString(item.title),
            narration: asString(item.narration),
            visualAction: asString(item.visual_action),
            durationSeconds: typeof duration === 'number' ? duration : null,
          };
        })
        .filter((item) => item.sceneNo || item.narration)
    : [];
  if (!scenes.length) return null;
  return {
    kind: 'animation_script',
    learningGoal: asString(payload.learning_goal),
    mediaNote: asString(
      payload.media_note,
      '本资源是动画教学脚本（分镜），不含已渲染的动画视频文件。',
    ),
    scenes,
    states: asStringList(payload.states),
    evidenceRefs: asStringList(payload.evidence_refs),
  };
}
