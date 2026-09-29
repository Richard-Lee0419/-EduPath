/**
 * 研究事件客户端（W01）。
 *
 * 设计约束：
 * - **fire-and-forget**：`track()` 永远同步返回、永不抛错，埋点失败不得影响学习主流程；
 * - `keepalive: true`：用户在生成过程中离开页面（研究任务 U-07）时事件仍能送达；
 * - 不依赖业务 API 客户端，避免埋点反过来影响业务请求的错误处理。
 */

import { apiBaseUrl } from '../api/http';
import {
  buildEventPayload,
  deliverEvents,
  type ResearchEventInput,
  type ResearchEventName,
} from './core';
import { getResearchContext } from './context';

async function sendBatch(body: { events: Record<string, unknown>[] }): Promise<void> {
  const token = typeof localStorage === 'undefined' ? null : localStorage.getItem('edupath_token');
  const response = await fetch(`${apiBaseUrl}/research/events`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    keepalive: true,
  });
  if (!response.ok) {
    throw new Error(`research event rejected: ${response.status}`);
  }
}

/** 上报一个研究事件。任何失败都只在开发环境打日志，绝不影响业务。 */
export function track(eventName: ResearchEventName, input: Omit<ResearchEventInput, 'eventName'> = {}): void {
  const context = getResearchContext();
  const payload = buildEventPayload({ ...input, eventName }, context);
  void deliverEvents([payload], sendBatch, (error) => {
    if (import.meta.env?.DEV) {
      console.warn('[research] 事件上报失败（不影响业务）', error);
    }
  });
}

export { RESEARCH_EVENT_NAMES, RESEARCH_SOURCE_TYPES } from './core';
export type { ResearchEventName, ResearchSourceType } from './core';
