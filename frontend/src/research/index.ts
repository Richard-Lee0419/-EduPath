/** W01 研究埋点模块入口（最小侵入：只做 instrumentation，不改变任何 UI 布局）。 */

export { track } from './client';
export {
  RESEARCH_EVENT_NAMES,
  RESEARCH_SOURCE_TYPES,
  RESEARCH_METADATA_KEYS,
  isKnownEventName,
  isKnownSourceType,
  sanitizeMetadata,
  buildEventPayload,
  shouldEmitExposure,
  resetExposureCache,
  deliverEvents,
  createId,
  type ResearchEventInput,
  type ResearchEventName,
  type ResearchSourceType,
} from './core';
export { getResearchContext, resolveResearchContext, resetResearchSession } from './context';
export { observeExposure } from './exposure';
export {
  ResearchTaskSession,
  isTaskActive,
  canStartTask,
  canCompleteTask,
  canAbandonTask,
  RESEARCH_TASK_STORAGE_KEY,
  type ResearchTaskState,
  type ResearchTaskEventName,
} from './taskSession';
export {
  getResearchTaskSession,
  resetResearchTaskSession,
  initResearchTaskControl,
} from './taskSessionClient';
