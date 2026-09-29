/**
 * 研究会话上下文（W01）。
 *
 * 一次研究会话可以绑定：batch_id、participant_code、source_type、product_version。
 * 这些信息**不进入产品主界面**，只通过 URL 参数（测试环境）注入并落到 sessionStorage：
 *
 *   /resource-generate?research_batch=B01&participant=S01&source_type=real_user&product_version=1.0.0-rc.1
 *
 * - 刷新页面后同一 session 保持不变（sessionStorage + 已解析参数）；
 * - `?research_reset=1` 明确开启一个新会话（清空 sessionStorage 与曝光去重缓存）；
 * - participant_code 只允许匿名编号（S01/T01 之类），**不得与真实姓名绑定**。
 */

import {
  createId,
  isKnownSourceType,
  resetExposureCache,
  type ResearchContext,
  type ResearchSourceType,
} from './core';

const STORAGE_KEY = 'edupath_research_context';

interface PersistedContext {
  sessionId: string;
  participantCode: string | null;
  sourceType: ResearchSourceType;
  researchBatch: string | null;
  productVersion: string;
}

let cached: ResearchContext | null = null;

function storage(): Storage | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function readStored(): PersistedContext | null {
  const store = storage();
  if (!store) return null;
  const raw = store.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<PersistedContext>;
    if (!parsed.sessionId) return null;
    return {
      sessionId: parsed.sessionId,
      participantCode: parsed.participantCode ?? null,
      sourceType:
        parsed.sourceType && isKnownSourceType(parsed.sourceType) ? parsed.sourceType : 'real_user',
      researchBatch: parsed.researchBatch ?? null,
      productVersion: parsed.productVersion ?? 'unknown',
    };
  } catch {
    return null;
  }
}

function writeStored(context: PersistedContext): void {
  const store = storage();
  if (!store) return;
  try {
    store.setItem(STORAGE_KEY, JSON.stringify(context));
  } catch {
    // 隐私模式等场景下写入失败不影响产品功能
  }
}

function normalizeParticipant(value: string | null): string | null {
  if (!value) return null;
  const trimmed = value.trim();
  // 匿名编号：字母/数字/_/-，最长 32；不符合的（比如真实姓名带空格）直接丢弃
  return /^[A-Za-z0-9_-]{1,32}$/.test(trimmed) ? trimmed : null;
}

/**
 * 解析研究会话上下文。URL 参数优先并会持久化，保证刷新后同一 session 保持。
 */
export function resolveResearchContext(
  search: string = typeof window === 'undefined' ? '' : window.location.search,
): ResearchContext {
  const params = new URLSearchParams(search || '');
  const reset = params.get('research_reset') === '1';
  if (reset) {
    const store = storage();
    store?.removeItem(STORAGE_KEY);
    resetExposureCache();
    cached = null;
  }

  const batch = params.get('research_batch');
  const participant = params.get('participant');
  const sourceType = params.get('source_type');
  const productVersion = params.get('product_version');
  const hasExplicit = Boolean(batch || participant || sourceType || productVersion);

  const stored = reset ? null : readStored();
  const base: PersistedContext = stored ?? {
    sessionId: createId('sess'),
    participantCode: null,
    // 默认值按"真实产品使用"处理；任何研究批次、内部测试、回放、dry-run 都必须显式声明 source_type。
    sourceType: 'real_user',
    researchBatch: null,
    productVersion: 'unknown',
  };

  const next: PersistedContext = {
    sessionId: base.sessionId,
    participantCode: hasExplicit ? normalizeParticipant(participant) ?? base.participantCode : base.participantCode,
    sourceType:
      sourceType && isKnownSourceType(sourceType)
        ? sourceType
        : hasExplicit
          ? base.sourceType
          : base.sourceType,
    researchBatch: batch ?? base.researchBatch,
    productVersion: productVersion ?? base.productVersion,
  };

  if (hasExplicit || reset) {
    writeStored(next);
  }
  return next;
}

/** 取当前会话上下文（进程内缓存，避免每次事件都读 storage）。 */
export function getResearchContext(): ResearchContext {
  if (!cached) {
    cached = resolveResearchContext();
  }
  return cached;
}

/** 明确开启新会话（研究主持人在参与者之间切换时调用）。 */
export function resetResearchSession(): ResearchContext {
  cached = null;
  resetExposureCache();
  const store = storage();
  store?.removeItem(STORAGE_KEY);
  cached = {
    sessionId: createId('sess'),
    participantCode: null,
    sourceType: 'real_user',
    researchBatch: null,
    productVersion: 'unknown',
  };
  writeStored({
    sessionId: cached.sessionId,
    participantCode: cached.participantCode,
    sourceType: cached.sourceType,
    researchBatch: cached.researchBatch,
    productVersion: cached.productVersion,
  });
  return cached;
}

/** 仅用于测试：重置进程内缓存。 */
export function __resetResearchContextCache(): void {
  cached = null;
}
