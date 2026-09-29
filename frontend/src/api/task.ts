import { apiBaseUrl, apiGet, apiPost } from './http';
import type { AgentStep } from '../types';
import type { AgentTask, AgentTaskListResponse, TaskCreated, TaskStatus } from '@/types/api';

const terminalStatuses: TaskStatus[] = ['success', 'failed', 'cancelled'];

export type TaskUpdateHandler = (task: AgentTask) => void;

export interface WaitForAgentTaskOptions {
  intervalMs?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
}

class SseUnavailableError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = 'SseUnavailableError';
  }
}

class AgentTaskTerminalError extends Error {
  constructor(task: AgentTask) {
    super(task.error_message || `任务${task.status}`);
    this.name = 'AgentTaskTerminalError';
  }
}

const timeoutError = () => new Error('任务执行超时，可刷新当前页面继续恢复进度');

const abortReason = (signal: AbortSignal): unknown =>
  signal.reason ?? new DOMException('The operation was aborted.', 'AbortError');

export const isAbortError = (error: unknown): boolean =>
  (error instanceof DOMException && error.name === 'AbortError') ||
  (error instanceof Error && error.name === 'AbortError');

const assertTaskSucceeded = (task: AgentTask) => {
  if (task.status !== 'success') {
    throw new AgentTaskTerminalError(task);
  }
};

export const normalizeTaskId = (data: TaskCreated | { taskId?: string; task_id?: string }): string => {
  const taskId = 'task_id' in data ? data.task_id : data.taskId;
  if (!taskId) {
    throw new Error('后端未返回 task_id');
  }
  return taskId;
};

export function getAgentTask(taskId: string) {
  return apiGet<AgentTask>(`/agent/tasks/${taskId}`);
}

export function listAgentTasks(params: { status?: TaskStatus; page?: number; size?: number } = {}) {
  return apiGet<AgentTaskListResponse>('/agent/tasks', params);
}

export function cancelAgentTask(taskId: string) {
  return apiPost<TaskCreated>(`/agent/tasks/${taskId}/cancel`);
}

export function retryAgentTask(taskId: string) {
  return apiPost<TaskCreated>(`/agent/tasks/${taskId}/retry`);
}

export async function waitForAgentTask(
  taskId: string,
  onUpdate?: TaskUpdateHandler,
  options: WaitForAgentTaskOptions = {},
) {
  const timeoutMs = options.timeoutMs ?? 120000;
  const deadline = Date.now() + timeoutMs;

  if (options.signal?.aborted) {
    throw abortReason(options.signal);
  }

  if (typeof ReadableStream !== 'undefined') {
    try {
      return await waitForAgentTaskStream(taskId, onUpdate, timeoutMs, options.signal);
    } catch (error) {
      if (isAbortError(error) || !(error instanceof SseUnavailableError)) throw error;
    }
  }

  return waitForAgentTaskPolling(taskId, onUpdate, {
    intervalMs: options.intervalMs ?? 700,
    deadline,
    signal: options.signal,
  });
}

async function waitForAgentTaskPolling(
  taskId: string,
  onUpdate: TaskUpdateHandler | undefined,
  options: { intervalMs: number; deadline: number; signal?: AbortSignal },
) {
  while (Date.now() < options.deadline) {
    if (options.signal?.aborted) {
      throw abortReason(options.signal);
    }
    const task = await getAgentTask(taskId);
    onUpdate?.(task);
    if (terminalStatuses.includes(task.status)) {
      assertTaskSucceeded(task);
      return task;
    }
    await waitForDelay(Math.min(options.intervalMs, options.deadline - Date.now()), options.signal);
  }

  throw timeoutError();
}

async function waitForAgentTaskStream(
  taskId: string,
  onUpdate: TaskUpdateHandler | undefined,
  timeoutMs: number,
  signal?: AbortSignal,
) {
  const controller = new AbortController();
  let didTimeout = false;
  const timeout = window.setTimeout(() => {
    didTimeout = true;
    controller.abort();
  }, timeoutMs);
  const forwardAbort = () => controller.abort(abortReason(signal!));
  signal?.addEventListener('abort', forwardAbort, { once: true });
  const token = localStorage.getItem('edupath_token');
  try {
    const response = await fetch(`${apiBaseUrl}/agent/tasks/${encodeURIComponent(taskId)}/stream`, {
      headers: token ? { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' } : { Accept: 'text/event-stream' },
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new SseUnavailableError(`SSE request failed with HTTP ${response.status}`);
    }
    if (!response.body) {
      throw new SseUnavailableError('SSE response body is unavailable');
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      const events = buffer.split(/\r?\n\r?\n/);
      buffer = events.pop() || '';
      for (const event of events) {
        const name = event.match(/^event:(.+)$/m)?.[1]?.trim();
        const data = event.match(/^data:(.+)$/m)?.[1]?.trim();
        if (name === 'task_status' && data) {
          const task = JSON.parse(data) as AgentTask;
          onUpdate?.(task);
          if (terminalStatuses.includes(task.status)) {
            assertTaskSucceeded(task);
            return task;
          }
        }
      }
      if (done) break;
    }
    throw new SseUnavailableError('SSE connection closed before the task completed');
  } catch (error) {
    if (signal?.aborted) throw abortReason(signal);
    if (didTimeout) throw timeoutError();
    if (isAbortError(error) || error instanceof AgentTaskTerminalError || error instanceof SseUnavailableError) throw error;

    const message =
      error instanceof TypeError || (error instanceof Error && /failed to fetch|network/i.test(error.message))
        ? 'SSE network connection is unavailable'
        : 'SSE stream could not be read';
    throw new SseUnavailableError(message, { cause: error });
  } finally {
    window.clearTimeout(timeout);
    signal?.removeEventListener('abort', forwardAbort);
  }
}

function waitForDelay(delayMs: number, signal?: AbortSignal) {
  if (signal?.aborted) {
    return Promise.reject(abortReason(signal));
  }

  return new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      signal?.removeEventListener('abort', handleAbort);
      resolve();
    }, Math.max(0, delayMs));
    const handleAbort = () => {
      window.clearTimeout(timeout);
      reject(abortReason(signal!));
    };
    signal?.addEventListener('abort', handleAbort, { once: true });
  });
}

export const toAgentSteps = (task?: AgentTask): AgentStep[] => {
  if (!task?.steps?.length) {
    return [];
  }

  return task.steps.map((step) => ({
    agent: step.agent,
    title: step.agent,
    status: step.status,
    message: step.message,
  }));
};
