/**
 * 研究任务会话（W01 Instrumentation Closeout）。
 *
 * 围绕 U-01～U-07 中的真实用户任务，构建轻量 Research Task Session。
 * 一个研究任务至少具备：
 *   research_task_id / task_code / started_at / completed_at(可空) / abandoned_at(可空)
 *
 * 关键语义（不把产品行为当研究判定）：
 * - `task_started`    研究主持人启动任务时记录（URL 研究模式驱动）；
 * - `task_completed`  用户完成规定目标（主持人确认）时记录；
 * - `task_abandoned`  用户明确放弃 / 主持人结束未完成任务时记录。
 *
 * 约束：
 * - completed 与 abandoned **互斥**；
 * - 刷新页面**不重复发 task_started**（状态持久化于 sessionStorage）；
 * - `reset()`（research_reset=1）后可创建新 task session。
 *
 * 本文件是纯逻辑 + 依赖注入（storage / emit / now / id），不依赖浏览器 API 或 Vue，
 * 因此可被 Node 直接运行做自动化测试。
 */

// 说明：本文件为了能被 Node 直接运行（type-stripping）做自动化测试，不依赖浏览器 API，
// 也不 import 其他模块（Node type-stripping 对扩展名省略的内部相对导入不支持）。
// createId / nowIso 在此内联，与 core.ts 保持同语义。

function nowIso(): string {
  return new Date().toISOString();
}

function createId(prefix: string): string {
  const globalCrypto = typeof globalThis !== 'undefined' ? (globalThis.crypto as Crypto | undefined) : undefined;
  if (globalCrypto?.randomUUID) {
    return `${prefix}_${globalCrypto.randomUUID().replace(/-/g, '')}`;
  }
  const random = Math.random().toString(36).slice(2, 12);
  return `${prefix}_${Date.now().toString(36)}${random}`;
}

export interface ResearchTaskState {
  research_task_id: string;
  task_code: string;
  started_at: string;
  completed_at: string | null;
  abandoned_at: string | null;
}

export type ResearchTaskEventName = 'task_started' | 'task_completed' | 'task_abandoned';

export interface ResearchTaskStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export type ResearchTaskEmitter = (eventName: ResearchTaskEventName, state: ResearchTaskState) => void;

export const RESEARCH_TASK_STORAGE_KEY = 'edupath_research_task';

/** 任务处于"进行中"（已开始、未完成、未放弃）。 */
export function isTaskActive(state: ResearchTaskState | null): boolean {
  return state !== null && state.completed_at === null && state.abandoned_at === null;
}

/** 无进行中任务（空或已终结）才可开始新任务。 */
export function canStartTask(state: ResearchTaskState | null): boolean {
  return !isTaskActive(state);
}

/** 只有"进行中"任务才能被标记完成。 */
export function canCompleteTask(state: ResearchTaskState | null): boolean {
  return isTaskActive(state);
}

/** 只有"进行中"任务才能被标记放弃。 */
export function canAbandonTask(state: ResearchTaskState | null): boolean {
  return isTaskActive(state);
}

function normalize(raw: string | null): ResearchTaskState | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<ResearchTaskState>;
    if (
      !parsed ||
      typeof parsed.research_task_id !== 'string' ||
      typeof parsed.task_code !== 'string' ||
      typeof parsed.started_at !== 'string'
    ) {
      return null;
    }
    return {
      research_task_id: parsed.research_task_id,
      task_code: parsed.task_code,
      started_at: parsed.started_at,
      completed_at: typeof parsed.completed_at === 'string' ? parsed.completed_at : null,
      abandoned_at: typeof parsed.abandoned_at === 'string' ? parsed.abandoned_at : null,
    };
  } catch {
    return null;
  }
}

export class ResearchTaskSession {
  private readonly storage: ResearchTaskStorage | null;
  private readonly emit: ResearchTaskEmitter;
  private readonly now: () => string;
  private readonly id: () => string;

  constructor(
    storage: ResearchTaskStorage | null,
    emit: ResearchTaskEmitter,
    now: () => string = nowIso,
    id: () => string = () => createId('rtask'),
  ) {
    this.storage = storage;
    this.emit = emit;
    this.now = now;
    this.id = id;
  }

  current(): ResearchTaskState | null {
    return this.storage ? normalize(this.storage.getItem(RESEARCH_TASK_STORAGE_KEY)) : null;
  }

  /** 开始任务；若已有进行中任务则拒绝（刷新不重复发 task_started）。 */
  start(taskCode: string): boolean {
    const code = (taskCode || '').trim();
    if (!code) return false;
    if (!canStartTask(this.current())) return false;
    const next: ResearchTaskState = {
      research_task_id: this.id(),
      task_code: code,
      started_at: this.now(),
      completed_at: null,
      abandoned_at: null,
    };
    this._commit(next, 'task_started');
    return true;
  }

  /** 标记完成；仅在"进行中"且 task_code 匹配时生效。 */
  complete(taskCode?: string): boolean {
    const state = this.current();
    if (!canCompleteTask(state) || !state) return false;
    if (taskCode && state.task_code !== taskCode) return false;
    this._commit({ ...state, completed_at: this.now() }, 'task_completed');
    return true;
  }

  /** 标记放弃；仅在"进行中"且 task_code 匹配时生效。 */
  abandon(taskCode?: string): boolean {
    const state = this.current();
    if (!canAbandonTask(state) || !state) return false;
    if (taskCode && state.task_code !== taskCode) return false;
    this._commit({ ...state, abandoned_at: this.now() }, 'task_abandoned');
    return true;
  }

  reset(): void {
    this.storage?.removeItem(RESEARCH_TASK_STORAGE_KEY);
  }

  /** 先落盘状态，再发射事件；事件发射失败绝不回滚状态、绝不向上抛（埋点失败不阻断业务）。 */
  private _commit(state: ResearchTaskState, eventName: ResearchTaskEventName): void {
    this.storage?.setItem(RESEARCH_TASK_STORAGE_KEY, JSON.stringify(state));
    try {
      this.emit(eventName, state);
    } catch {
      // 埋点失败不影响任务状态与业务
    }
  }
}
