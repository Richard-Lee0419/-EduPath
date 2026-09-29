/**
 * W03 资源 payload 解析层的确定性测试（Node 直接运行，不需要浏览器）。
 *
 * 覆盖：五类 payload 归一化、malformed/缺失回退、未知类型回退、
 * quiz 提交前无答案、codelab 不伪造执行结果、animation_script 命名边界。
 */

import assert from 'node:assert/strict';

import {
  parseResourcePayload,
  RENDERABLE_RESOURCE_TYPES,
} from '../src/resource/payload.ts';

let passed = 0;
function check(name, fn) {
  fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

const lecturePayload = {
  learning_objective: ['解释二叉树的定义'],
  prerequisites: ['二叉树'],
  sections: [{ heading: '概念边界', body: '二叉树是……' }],
  worked_examples: [{ title: '例1', problem: '前序遍历', solution: '根左右' }],
  common_mistakes: ['顺序混淆'],
  self_check: [{ question: '能复述吗？', hint: '先说输入' }],
  evidence_refs: ['ai_chunk_1'],
};

const mindmapPayload = {
  central_concept: '二叉树',
  nodes: [
    { id: 'root', label: '二叉树', parent_id: '' },
    { id: 'n1', label: '遍历', parent_id: 'root' },
  ],
  relations: [{ from: 'root', to: 'n1', label: '包含' }],
  pitfalls: ['顺序混淆'],
  mermaid: 'graph TD\n  root[(二叉树)]',
};

const quizPayload = {
  answers_hidden: true,
  questions: [
    {
      question_id: 'q1',
      question: '核心判断依据？',
      question_type: 'single_choice',
      options: [
        { key: 'A', text: '只看答案' },
        { key: 'B', text: '依据定义' },
      ],
      knowledge_point: '二叉树',
      difficulty: 'basic',
    },
  ],
};

const codelabPayload = {
  goal: '把规则转成代码',
  prerequisites: ['二叉树'],
  language: 'python',
  starter_code: "print('demo')",
  steps: ['运行', '修改', '对照'],
  sample_input: '内置示例',
  expected_output: 'demo',
  explanation: '输出反映执行结果',
  execution: { supported: false, note: '当前环境未提供在线代码执行。' },
};

const animationPayload = {
  learning_goal: '理解执行过程',
  media_type: 'animation_script',
  media_note: '本资源是动画教学脚本（分镜），不含已渲染的动画视频文件。',
  scenes: [
    {
      scene_no: '镜头 1',
      title: '目标展示',
      narration: '展示输入对象',
      visual_action: '中央示意',
      duration_seconds: 20,
    },
  ],
  states: ['输入展示'],
  evidence_refs: ['ai_chunk_1'],
};

check('五类 payload 均可被对应类型解析', () => {
  assert.equal(parseResourcePayload('lecture', lecturePayload)?.kind, 'lecture');
  assert.equal(parseResourcePayload('mindmap', mindmapPayload)?.kind, 'mindmap');
  assert.equal(parseResourcePayload('flowchart', mindmapPayload)?.kind, 'mindmap');
  assert.equal(parseResourcePayload('quiz', quizPayload)?.kind, 'quiz');
  assert.equal(parseResourcePayload('codelab', codelabPayload)?.kind, 'codelab');
  assert.equal(parseResourcePayload('animation_script', animationPayload)?.kind, 'animation_script');
});

check('payload 缺失 / null / malformed ⇒ 回退 markdown（返回 null）', () => {
  assert.equal(parseResourcePayload('lecture', null), null);
  assert.equal(parseResourcePayload('lecture', undefined), null);
  assert.equal(parseResourcePayload('lecture', {}), null);
  assert.equal(parseResourcePayload('quiz', { questions: 'not-a-list' }), null);
  assert.equal(parseResourcePayload('quiz', { questions: [{ no_fields: true }] }), null);
  assert.equal(parseResourcePayload('codelab', { execution: { supported: true } }), null);
});

check('未知类型（如历史 flowchart/reading 之外的脏数据）⇒ 回退', () => {
  assert.equal(parseResourcePayload('unknown_type', lecturePayload), null);
  assert.equal(parseResourcePayload('', lecturePayload), null);
});

check('quiz payload 提交前永不携带答案字段', () => {
  const parsed = parseResourcePayload('quiz', {
    ...quizPayload,
    // 即使上游误带答案字段，解析层也必须丢弃
    questions: [{ ...quizPayload.questions[0], correct_answer: 'B', explanation: '因为……' }],
  });
  assert.ok(parsed && parsed.kind === 'quiz');
  for (const question of parsed.questions) {
    assert.equal(question.correctAnswer, null);
    assert.ok(!('correct_answer' in question));
    assert.ok(!('explanation' in question));
    assert.equal(question.questionType, 'single_choice');
    assert.equal(question.options.length, 2);
  }
  assert.equal(parsed.answersHidden, true);
});

check('开放题（无 options）被识别为 open 类型', () => {
  const parsed = parseResourcePayload('quiz', {
    questions: [{ question_id: 'q2', question: '写出一个反例' }],
  });
  assert.ok(parsed && parsed.kind === 'quiz');
  assert.equal(parsed.questions[0].questionType, 'open');
  assert.deepEqual(parsed.questions[0].options, []);
});

check('codelab 永不伪造执行结果：supported 缺失也强制 false', () => {
  const parsed = parseResourcePayload('codelab', {
    goal: 'g',
    starter_code: 'x',
    execution: { supported: true, note: '运行成功' },
  });
  assert.ok(parsed && parsed.kind === 'codelab');
  assert.equal(parsed.executionSupported, false);
  const withoutExecution = parseResourcePayload('codelab', { goal: 'g', starter_code: 'x' });
  assert.ok(withoutExecution && withoutExecution.kind === 'codelab');
  assert.equal(withoutExecution.executionSupported, false);
});

check('animation_script 保持脚本命名边界，不声称成品动画', () => {
  const parsed = parseResourcePayload('animation_script', animationPayload);
  assert.ok(parsed && parsed.kind === 'animation_script');
  assert.ok(parsed.mediaNote.includes('脚本'));
  assert.ok(!parsed.mediaNote.includes('已生成动画'));
  assert.equal(parsed.scenes[0].durationSeconds, 20);
});

check('malformed duration 与缺失字段不崩', () => {
  const parsed = parseResourcePayload('animation_script', {
    scenes: [{ scene_no: '镜头 1', duration_seconds: 'not-a-number' }],
  });
  assert.ok(parsed && parsed.kind === 'animation_script');
  assert.equal(parsed.scenes[0].durationSeconds, null);
  assert.equal(parsed.scenes[0].title, '');
});

check('渲染类型集合与五类闭环一致', () => {
  assert.deepEqual([...RENDERABLE_RESOURCE_TYPES].sort(), [
    'animation_script',
    'codelab',
    'lecture',
    'mindmap',
    'quiz',
  ]);
});

console.log(`\n${passed} 项资源 payload 解析测试全部通过`);
