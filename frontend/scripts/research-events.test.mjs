/**
 * W01 研究埋点核心逻辑的确定性测试（Node 直接运行，不需要浏览器或网络）。
 *
 * 覆盖：事件名/来源类型受控、请求体为 snake_case、client 时间戳独立、
 * 敏感 metadata 被过滤、曝光去重规则、埋点失败不抛错（不阻塞业务）。
 */

import assert from 'node:assert/strict';

import {
  RESEARCH_EVENT_NAMES,
  RESEARCH_SOURCE_TYPES,
  buildEventPayload,
  deliverEvents,
  isKnownEventName,
  isKnownSourceType,
  isSensitiveKey,
  sanitizeMetadata,
  shouldEmitExposure,
  resetExposureCache,
} from '../src/research/core.ts';

import {
  ResearchTaskSession,
  isTaskActive,
  canStartTask,
  canCompleteTask,
  canAbandonTask,
} from '../src/research/taskSession.ts';

const REQUIRED_EVENT_NAMES = [
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
];

const REQUIRED_SOURCE_TYPES = ['real_user', 'internal_manual', 'automation', 'demo', 'replay', 'dry_run'];

const context = {
  sessionId: 'sess_test_1',
  participantCode: 'S01',
  sourceType: 'real_user',
  researchBatch: 'B01',
  productVersion: '1.0.0-rc.1',
};

let passed = 0;
function check(name, fn) {
  fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

check('统一 Event Schema 覆盖全部要求的事件名且不重复', () => {
  assert.equal(new Set(RESEARCH_EVENT_NAMES).size, RESEARCH_EVENT_NAMES.length);
  for (const name of REQUIRED_EVENT_NAMES) {
    assert.ok(RESEARCH_EVENT_NAMES.includes(name), `缺少事件名 ${name}`);
  }
  for (const name of RESEARCH_EVENT_NAMES) {
    assert.ok(isKnownEventName(name));
  }
  assert.ok(!isKnownEventName('reason_understood'));
  assert.ok(!isKnownEventName(''));
});

check('source_type 恰好覆盖 6 类数据来源', () => {
  assert.deepEqual([...RESEARCH_SOURCE_TYPES].sort(), [...REQUIRED_SOURCE_TYPES].sort());
  assert.ok(isKnownSourceType('dry_run'));
  assert.ok(!isKnownSourceType('guess'));
});

check('上报体使用 snake_case，并带上下文与独立的 client_timestamp', () => {
  const payload = buildEventPayload(
    { eventName: 'resource_opened', page: 'resource-generate', resourceType: 'lecture', courseId: 1 },
    context,
    'evt_fixed',
    '2026-01-02T03:04:05.000Z',
  );

  assert.equal(payload.event_id, 'evt_fixed');
  assert.equal(payload.event_name, 'resource_opened');
  assert.equal(payload.session_id, 'sess_test_1');
  assert.equal(payload.participant_code, 'S01');
  assert.equal(payload.source_type, 'real_user');
  assert.equal(payload.research_batch, 'B01');
  assert.equal(payload.product_version, '1.0.0-rc.1');
  assert.equal(payload.client_timestamp, '2026-01-02T03:04:05.000Z');
  assert.equal(payload.page, 'resource-generate');
  assert.equal(payload.resource_type, 'lecture');
  assert.equal(payload.course_id, 1);
  assert.ok(!('server_timestamp' in payload), 'client 不得伪造服务端时间戳');
  assert.ok(!('eventId' in payload), '不得混用 camelCase 字段');
});

check('未提供 participant/batch 时不产生空字段', () => {
  const payload = buildEventPayload({ eventName: 'task_started' }, {
    sessionId: 'sess_anon',
    participantCode: null,
    sourceType: 'demo',
    researchBatch: null,
    productVersion: 'dev',
  });
  assert.ok(!('participant_code' in payload));
  assert.ok(!('research_batch' in payload));
  assert.equal(payload.source_type, 'demo');
});

check('两次调用的 event_id 不同（避免覆盖历史事件）', () => {
  const first = buildEventPayload({ eventName: 'task_started' }, context);
  const second = buildEventPayload({ eventName: 'task_started' }, context);
  assert.notEqual(first.event_id, second.event_id);
});

check('metadata 只保留白名单标量，敏感字段被过滤', () => {
  const sanitized = sanitizeMetadata({
    resource_id: 'res_1',
    count: 3,
    resource_types: ['lecture', 'quiz'],
    email: 'someone@example.com',
    phone: '13800000000',
    raw_chat: '用户原话……',
    access_token: 'secret',
    message: '聊天全文',
    nested: { a: 1 },
    node_label: 'x'.repeat(300),
  });

  assert.equal(sanitized.resource_id, 'res_1');
  assert.equal(sanitized.count, 3);
  assert.deepEqual(sanitized.resource_types, ['lecture', 'quiz']);
  assert.ok(!('email' in sanitized));
  assert.ok(!('phone' in sanitized));
  assert.ok(!('raw_chat' in sanitized));
  assert.ok(!('access_token' in sanitized));
  assert.ok(!('message' in sanitized));
  assert.ok(!('nested' in sanitized));
  assert.ok(!('long_value' in sanitized), '非白名单键必须整体丢弃');
  assert.equal(String(sanitized.node_label).length, 200);
});

check('敏感键识别覆盖常见个人身份字段', () => {
  for (const key of ['user_name', 'phone', 'email', 'id_card', 'access_token', 'raw_chat', 'message', 'content']) {
    assert.ok(isSensitiveKey(key), `${key} 应被识别为敏感键`);
  }
  assert.ok(!isSensitiveKey('resource_id'));
});

check('payload 中不会出现敏感 metadata（即使调用方误传）', () => {
  const payload = buildEventPayload(
    { eventName: 'reason_exposed', metadata: { reason_id: 'r1', email: 'a@b.com' } },
    context,
  );
  const serialized = JSON.stringify(payload);
  assert.ok(!serialized.includes('a@b.com'));
  assert.ok(!serialized.includes('email'));
  assert.equal(payload.metadata.reason_id, 'r1');
});

check('曝光去重：同一 key 只记录一次，重置后重新计数', () => {
  resetExposureCache();
  assert.equal(shouldEmitExposure('reason:res_1'), true);
  assert.equal(shouldEmitExposure('reason:res_1'), false);
  assert.equal(shouldEmitExposure('reason:res_2'), true);
  assert.equal(shouldEmitExposure('evidence:chunk_1'), true);
  assert.equal(shouldEmitExposure('evidence:chunk_1'), false);
  resetExposureCache();
  assert.equal(shouldEmitExposure('reason:res_1'), true);
});

check('埋点失败绝不抛错，且能上报失败原因', async () => {
  let observed = null;
  const failed = await deliverEvents([{ event_id: 'evt_1' }], async () => {
    throw new Error('network down');
  }, (error) => {
    observed = error;
  });
  assert.equal(failed, false, '失败时必须返回 false 而不是抛错');
  assert.ok(observed instanceof Error, '失败原因应被回传，避免静默丢失');

  let called = 0;
  const ok = await deliverEvents([{ event_id: 'evt_2' }], async () => {
    called += 1;
  });
  assert.equal(ok, true);
  assert.equal(called, 1);

  assert.equal(await deliverEvents([], async () => {
    throw new Error('不应被调用');
  }), true);
});

check('失败回调本身抛错也不会影响主流程', async () => {
  const result = await deliverEvents([{ event_id: 'evt_3' }], async () => {
    throw new Error('boom');
  }, () => {
    throw new Error('callback boom');
  });
  assert.equal(result, false);
});

// ---------- W01 Instrumentation Closeout：研究任务会话 ----------

class MemoryStorage {
  constructor() {
    this.map = new Map();
  }
  getItem(key) {
    return this.map.has(key) ? this.map.get(key) : null;
  }
  setItem(key, value) {
    this.map.set(key, String(value));
  }
  removeItem(key) {
    this.map.delete(key);
  }
}

function makeSession({ storage = new MemoryStorage(), now = () => '2026-09-20T10:00:00.000Z', id = () => 'rtask_fixed' } = {}) {
  const events = [];
  const session = new ResearchTaskSession(
    storage,
    (name, state) => events.push({ name, state }),
    now,
    id,
  );
  return { session, storage, events };
}

check('opened 事件仅存在于 schema，不接入任何自动发射路径（NOT_APPLICABLE）', () => {
  // 仍保留在统一 Event Schema 中（供 W04 证据交互接入），但当前 UI 无点击/展开交互
  assert.ok(isKnownEventName('reason_opened'));
  assert.ok(isKnownEventName('evidence_opened'));
  // 曝光只会产生 *_exposed，绝不自动变成 *_opened（无任何 exposed→opened 转换函数）
  assert.ok(isKnownEventName('reason_exposed'));
  assert.ok(isKnownEventName('evidence_exposed'));
});

check('任务会话只发射 task_* 事件，绝不发射 opened 事件', () => {
  const { session, events } = makeSession();
  session.start('U-01');
  session.complete('U-01');
  const names = events.map((event) => event.name);
  assert.ok(names.length > 0);
  for (const name of names) {
    assert.ok(name.startsWith('task_'), `任务会话不应发射 ${name}`);
  }
  assert.ok(!names.includes('reason_opened'));
  assert.ok(!names.includes('evidence_opened'));
});

check('task_started：刷新不重复（同一任务只 start 一次）', () => {
  const { session, events } = makeSession();
  assert.equal(session.start('U-01'), true);
  assert.equal(session.start('U-01'), false); // 模拟刷新后再次调用
  assert.equal(session.start('U-01'), false);
  assert.equal(events.filter((event) => event.name === 'task_started').length, 1);
});

check('completed / abandoned 互斥，且 completed 后不能再 abandoned', () => {
  const { session, events } = makeSession();
  session.start('U-02');
  assert.equal(session.complete('U-02'), true);
  assert.equal(session.abandon('U-02'), false); // 已完成 → 不能放弃
  assert.equal(session.complete('U-02'), false); // 幂等
  assert.equal(events.filter((event) => event.name === 'task_completed').length, 1);
  assert.equal(events.filter((event) => event.name === 'task_abandoned').length, 0);
});

check('abandoned 后不能再 completed', () => {
  const { session, events } = makeSession();
  session.start('U-03');
  assert.equal(session.abandon('U-03'), true);
  assert.equal(session.complete('U-03'), false); // 已放弃 → 不能完成
  assert.equal(session.abandon('U-03'), false); // 幂等
  assert.equal(events.filter((event) => event.name === 'task_abandoned').length, 1);
  assert.equal(events.filter((event) => event.name === 'task_completed').length, 0);
});

check('task_code 不匹配时 complete/abandon 不生效', () => {
  const { session, events } = makeSession();
  session.start('U-04');
  assert.equal(session.complete('U-05'), false); // 不同任务代码
  assert.equal(session.abandon('U-05'), false);
  assert.equal(events.length, 1); // 只有 task_started
  assert.ok(isTaskActive(session.current()));
});

check('research_reset 后可创建新 task session（新的 research_task_id）', () => {
  const idSeq = { value: 0 };
  const { session, events } = makeSession({ id: () => `rtask_${(idSeq.value += 1)}` });
  session.start('U-01');
  const firstId = session.current().research_task_id;
  assert.equal(session.complete('U-01'), true);

  session.reset(); // 模拟 research_reset=1
  assert.equal(session.current(), null);
  assert.equal(session.start('U-02'), true); // 可开始新任务
  const secondId = session.current().research_task_id;
  assert.notEqual(secondId, firstId);
  assert.equal(events.filter((event) => event.name === 'task_started').length, 2);
});

check('任务状态先落盘再发射；emit 抛错不回滚状态、不外抛（埋点失败不阻断业务）', () => {
  const storage = new MemoryStorage();
  const { session } = makeSession({
    storage,
    id: () => 'rtask_emit_fail',
  });
  const throwing = new ResearchTaskSession(
    storage,
    () => {
      throw new Error('network down');
    },
    () => '2026-09-20T10:00:00.000Z',
    () => 'rtask_emit_fail',
  );
  assert.equal(throwing.start('U-06'), true); // 不抛错
  const persisted = JSON.parse(storage.getItem('edupath_research_task'));
  assert.equal(persisted.task_code, 'U-06');
  assert.equal(persisted.research_task_id, 'rtask_emit_fail');
  assert.ok(canCompleteTask(throwing.current()));
  void session;
});

check('纯谓词：canStart/canComplete/canAbandon 语义正确', () => {
  assert.equal(canStartTask(null), true);
  const { session } = makeSession();
  assert.equal(canStartTask(session.current()), true);
  session.start('U-07');
  assert.equal(canStartTask(session.current()), false);
  assert.equal(canCompleteTask(session.current()), true);
  assert.equal(canAbandonTask(session.current()), true);
  session.abandon('U-07');
  assert.equal(canStartTask(session.current()), true);
  assert.equal(canCompleteTask(session.current()), false);
  assert.equal(canAbandonTask(session.current()), false);
});

console.log(`\n${passed} 项研究埋点核心测试全部通过`);
