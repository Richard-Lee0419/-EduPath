/**
 * 研究埋点的纯逻辑核心（W01 研究基础设施）。
 *
 * 这个文件**不依赖任何浏览器 API 或 Vue**，因此可以被 Node 直接运行做自动化测试
 * （scripts/research-events.test.mjs）。浏览器相关能力（fetch、IntersectionObserver、
 * sessionStorage）分别放在 client.ts / exposure.ts / context.ts。
 *
 * 重要口径（与后端 ResearchEventSchema 保持一致）：
 * - 只记录"发生了什么"，**不推断**用户是否理解、是否困惑、是否满意；
 * - `reason_exposed` / `evidence_exposed` = 内容真的进入可视区域（曝光）；
 *   `reason_opened` / `evidence_opened` = 用户真实点击/展开动作；
 *   两者都只是行为数据，"理解"必须由任务后的人工提问/复述评分决定。
 */

export const RESEARCH_EVENT_NAMES = [
  'task_started',
  'profile_confirmed',
  'profile_corrected',
  'generation_requested',
  'generation_state_changed',
  'resource_opened',
  'reason_exposed',
  'reason_opened',
  'evidence_exposed',
  'evidence_opened',
  'path_change_shown',
  'path_change_accepted',
  'quiz_submitted',
  'remedial_task_started',
  'teacher_assignment_published',
  'task_completed',
  'task_abandoned',
] as const;

export type ResearchEventName = (typeof RESEARCH_EVENT_NAMES)[number];

/**
 * 数据来源类型。不同来源的数据**绝不允许混在一起统计**：
 * 真实参与者、内部人工测试、自动化、demo、回放、dry-run 必须各自显式声明。
 */
export const RESEARCH_SOURCE_TYPES = [
  'real_user',
  'internal_manual',
  'automation',
  'demo',
  'replay',
  'dry_run',
] as const;

export type ResearchSourceType = (typeof RESEARCH_SOURCE_TYPES)[number];

/** 允许写入 metadata 的键（服务端白名单的镜像，客户端先做一次过滤，避免必然被拒的上报）。 */
export const RESEARCH_METADATA_KEYS = [
  'ui_element',
  'exposure_key',
  'step',
  'status',
  'error_code',
  'duration_ms',
  'count',
  'profile_version',
  'changed_axis',
  'from_value',
  'to_value',
  'statement_length',
  'task_id',
  'task_code',
  'research_task_id',
  'agent',
  'resource_id',
  'resource_type',
  'resource_types',
  'knowledge_point_id',
  'knowledge_point_ids',
  'difficulty',
  'estimated_minutes',
  'execution_state',
  'safety_passed',
  'reason_id',
  'reason_visible',
  'evidence_ids',
  'evidence_count',
  'top_score',
  'path_id',
  'path_length',
  'changed_day_count',
  'adjustment_strategy_present',
  'node_label',
  'quiz_id',
  'item_count',
  'answered_count',
  'score',
  'course_id',
  'assignment_id',
  'target_count',
] as const;

/** 明确禁止的键名片段（白名单之外的第二道防线）。 */
const SENSITIVE_HINTS = [
  'name',
  'phone',
  'mobile',
  'email',
  'id_card',
  'password',
  'token',
  'secret',
  'apikey',
  'api_key',
  'chat',
  'message',
  'content',
  'raw',
  'transcript',
];

const METADATA_KEY_SET = new Set<string>(RESEARCH_METADATA_KEYS);
const EVENT_NAME_SET = new Set<string>(RESEARCH_EVENT_NAMES);
const SOURCE_TYPE_SET = new Set<string>(RESEARCH_SOURCE_TYPES);
const MAX_METADATA_VALUE_LENGTH = 200;

export interface ResearchContext {
  sessionId: string;
  participantCode: string | null;
  sourceType: ResearchSourceType;
  researchBatch: string | null;
  productVersion: string;
}

export interface ResearchEventInput {
  eventName: ResearchEventName;
  page?: string;
  resourceType?: string;
  executionState?: string;
  courseId?: number | null;
  taskRef?: string | null;
  sessionContext?: string | null;
  metadata?: Record<string, unknown>;
}

export function isKnownEventName(value: string): value is ResearchEventName {
  return EVENT_NAME_SET.has(value);
}

export function isKnownSourceType(value: string): value is ResearchSourceType {
  return SOURCE_TYPE_SET.has(value);
}

export function isSensitiveKey(key: string): boolean {
  const normalized = (key || '').toLowerCase();
  return SENSITIVE_HINTS.some((hint) => normalized.includes(hint));
}

export function createId(prefix: string): string {
  const globalCrypto = typeof globalThis !== 'undefined' ? (globalThis.crypto as Crypto | undefined) : undefined;
  if (globalCrypto?.randomUUID) {
    return `${prefix}_${globalCrypto.randomUUID().replace(/-/g, '')}`;
  }
  const random = Math.random().toString(36).slice(2, 12);
  return `${prefix}_${Date.now().toString(36)}${random}`;
}

export function nowIso(): string {
  return new Date().toISOString();
}

/**
 * 过滤 metadata：只保留白名单键，丢弃敏感键与非标量值，并限制长度。
 * 返回值只包含可以安全进入研究数据库的内容。
 */
export function sanitizeMetadata(metadata?: Record<string, unknown> | null): Record<string, unknown> {
  if (!metadata) return {};
  const sanitized: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(metadata)) {
    if (!METADATA_KEY_SET.has(key) || isSensitiveKey(key)) continue;
    if (value === undefined || value === null) continue;
    if (typeof value === 'string') {
      sanitized[key] = value.length > MAX_METADATA_VALUE_LENGTH ? value.slice(0, MAX_METADATA_VALUE_LENGTH) : value;
      continue;
    }
    if (typeof value === 'number' || typeof value === 'boolean') {
      sanitized[key] = value;
      continue;
    }
    // 数组仅允许标量元素（例如 resource_types / evidence_ids），其他结构一律丢弃
    if (Array.isArray(value)) {
      const scalars = value.filter((item) => typeof item === 'string' || typeof item === 'number');
      if (scalars.length) {
        sanitized[key] = scalars.slice(0, 20);
      }
    }
  }
  return sanitized;
}

/** 组装上报体（snake_case，与后端 DTO 的 SNAKE_CASE 命名策略一致）。 */
export function buildEventPayload(
  input: ResearchEventInput,
  context: ResearchContext,
  eventId: string = createId('evt'),
  clientTimestamp: string = nowIso(),
): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    event_id: eventId,
    event_name: input.eventName,
    session_id: context.sessionId,
    source_type: context.sourceType,
    client_timestamp: clientTimestamp,
  };
  if (context.participantCode) payload.participant_code = context.participantCode;
  if (context.researchBatch) payload.research_batch = context.researchBatch;
  if (context.productVersion) payload.product_version = context.productVersion;
  if (input.page) payload.page = input.page;
  if (input.resourceType) payload.resource_type = input.resourceType;
  if (input.executionState) payload.execution_state = input.executionState;
  if (typeof input.courseId === 'number') payload.course_id = input.courseId;
  if (input.taskRef) payload.task_ref = input.taskRef;
  if (input.sessionContext) payload.session_context = input.sessionContext;
  const metadata = sanitizeMetadata(input.metadata);
  if (Object.keys(metadata).length) payload.metadata = metadata;
  return payload;
}

/**
 * 曝光去重规则：同一个 exposure key 在一次会话内只记录一次。
 * key 由调用方给出（例如 `reason:res_123`），保证"重复曝光"不会把指标刷高。
 */
const exposureCache = new Set<string>();

export function shouldEmitExposure(key: string): boolean {
  if (!key) return true;
  if (exposureCache.has(key)) return false;
  exposureCache.add(key);
  return true;
}

export function resetExposureCache(): void {
  exposureCache.clear();
}

/**
 * 投递事件。**埋点是观测副作用**：任何失败都不允许向上抛，
 * 以免影响资源生成 / Quiz / 画像等核心学习流程。
 */
export async function deliverEvents(
  events: Record<string, unknown>[],
  sender: (body: { events: Record<string, unknown>[] }) => Promise<void>,
  onFailure?: (error: unknown) => void,
): Promise<boolean> {
  if (!events.length) return true;
  try {
    await sender({ events });
    return true;
  } catch (error) {
    if (onFailure) {
      try {
        onFailure(error);
      } catch {
        // 连失败回调都不能影响主流程
      }
    }
    return false;
  }
}
