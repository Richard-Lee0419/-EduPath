const ACTIVE_TASK_STORAGE_PREFIX = 'edupath_active_task:';
const DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000;

interface PersistedActiveTask {
  taskId: string;
  savedAt: number;
}

const storageKey = (scope: string) => `${ACTIVE_TASK_STORAGE_PREFIX}${encodeURIComponent(scope)}`;

export function persistActiveTask(scope: string, taskId: string) {
  if (!scope || !taskId) return;

  const record: PersistedActiveTask = {
    taskId,
    savedAt: Date.now(),
  };

  try {
    localStorage.setItem(storageKey(scope), JSON.stringify(record));
  } catch {
    // The task still runs when storage is unavailable; only refresh recovery is disabled.
  }
}

export function restoreActiveTask(scope: string, maxAgeMs = DEFAULT_MAX_AGE_MS): string | null {
  if (!scope) return null;

  try {
    const raw = localStorage.getItem(storageKey(scope));
    if (!raw) return null;

    const record = JSON.parse(raw) as Partial<PersistedActiveTask>;
    const isValid =
      typeof record.taskId === 'string' &&
      record.taskId.length > 0 &&
      typeof record.savedAt === 'number' &&
      Date.now() - record.savedAt <= maxAgeMs;

    if (isValid) return record.taskId!;

    localStorage.removeItem(storageKey(scope));
  } catch {
    try {
      localStorage.removeItem(storageKey(scope));
    } catch {
      // Ignore inaccessible browser storage and continue without recovery.
    }
  }

  return null;
}

export function clearActiveTask(scope: string, expectedTaskId?: string) {
  if (!scope) return;

  try {
    if (expectedTaskId) {
      const raw = localStorage.getItem(storageKey(scope));
      if (raw) {
        const record = JSON.parse(raw) as Partial<PersistedActiveTask>;
        if (record.taskId && record.taskId !== expectedTaskId) return;
      }
    }

    localStorage.removeItem(storageKey(scope));
  } catch {
    // Storage cleanup must not mask the task's real terminal result.
  }
}
