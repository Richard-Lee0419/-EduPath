/**
 * 研究任务会话的浏览器接线 + URL 研究模式控制（W01 Instrumentation Closeout）。
 *
 * 采用「URL 研究模式 + sessionStorage + 极小测试控制」，**不改变普通用户产品 UI**。
 *
 * URL 参数（仅研究环境使用）：
 *   research_task=U-01           要操作的任务代码
 *   research_action=start|complete|abandon
 *   research_reset=1             清空研究会话与任务会话（新参与者）
 *
 * 例：
 *   /resource-generate?research_batch=B01&participant=S01&source_type=real_user&research_task=U-01&research_action=start
 *
 * 幂等性由 ResearchTaskSession 状态机保证：刷新页面携带相同 research_action 不会重复发射；
 * 完成判定（task_completed）由研究主持人在观察到用户达到规定目标后，通过 URL 显式驱动，
 * 绝不由"资源生成成功 / Quiz 提交成功"等产品行为自动推断。
 */

import { track } from './client';
import {
  ResearchTaskSession,
  RESEARCH_TASK_STORAGE_KEY,
  type ResearchTaskEmitter,
  type ResearchTaskStorage,
} from './taskSession';

function storage(): ResearchTaskStorage | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/** 研究任务事件统一出口：只记录任务状态与匿名编号，不记录姓名/邮箱/聊天全文。 */
const emit: ResearchTaskEmitter = (eventName, state) => {
  track(eventName, {
    taskRef: state.research_task_id,
    metadata: {
      research_task_id: state.research_task_id,
      task_code: state.task_code,
    },
  });
};

let defaultSession: ResearchTaskSession | null = null;

export function getResearchTaskSession(): ResearchTaskSession {
  if (!defaultSession) {
    defaultSession = new ResearchTaskSession(storage(), emit);
  }
  return defaultSession;
}

export function resetResearchTaskSession(): void {
  getResearchTaskSession().reset();
}

/** 供测试清空内存缓存。 */
export function __resetResearchTaskSessionCache(): void {
  defaultSession = null;
}

/**
 * 在应用启动时处理研究任务控制 URL 参数（main.ts 调用一次）。
 * 不处理则普通用户完全无感知（无任何研究 UI）。
 */
export function initResearchTaskControl(search?: string): void {
  if (typeof window === 'undefined') return;
  const params = new URLSearchParams(search ?? window.location.search);
  if (params.get('research_reset') === '1') {
    resetResearchTaskSession();
  }
  const action = params.get('research_action');
  if (!action) return;
  const taskCode = params.get('research_task');
  const session = getResearchTaskSession();
  if (action === 'start' && taskCode) {
    session.start(taskCode);
  } else if (action === 'complete') {
    session.complete(taskCode ?? undefined);
  } else if (action === 'abandon') {
    session.abandon(taskCode ?? undefined);
  }
}

export { RESEARCH_TASK_STORAGE_KEY };
