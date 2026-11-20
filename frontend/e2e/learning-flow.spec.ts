import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type APIResponse, type Page, type TestInfo } from '@playwright/test';

const backend = process.env.EDUPATH_BACKEND_URL || 'http://127.0.0.1:8080';
const ai = process.env.EDUPATH_AI_URL || 'http://127.0.0.1:8000';
const recording = process.env.EDUPATH_DEMO_RECORDING === '1';

async function login(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('请输入用户名或邮箱').fill(username);
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(password);
  await page.locator('form').getByRole('button', { name: /进入知径/ }).click();
  await expect(page).not.toHaveURL(/\/login$/);
}

async function hold(page: Page, info: TestInfo, label: string, seconds = 6) {
  if (!recording) return;
  await page.screenshot({ path: info.outputPath(`${label}.png`), fullPage: false });
  await page.waitForTimeout(seconds * 1000);
}

function coursePdf(firstTopic: string, secondTopic: string): Buffer {
  const stream = (text: string) => {
    const body = `BT /F1 16 Tf 72 720 Td (${text}) Tj ET\n`;
    return `<< /Length ${Buffer.byteLength(body)} >>\nstream\n${body}endstream`;
  };
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Count 2 /Kids [3 0 R 5 0 R] >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R >>',
    stream(firstTopic),
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>',
    stream(secondTopic),
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = ['0000000000 65535 f \n'];
  objects.forEach((object, i) => {
    offsets.push(`${String(Buffer.byteLength(pdf)).padStart(10, '0')} 00000 n \n`);
    pdf += `${i + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = Buffer.byteLength(pdf);
  pdf += `xref\n0 ${offsets.length}\n${offsets.join('')}trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf);
}

async function responseData(response: APIResponse) {
  const body = await response.json();
  expect(response.ok(), JSON.stringify(body)).toBeTruthy();
  return body.data;
}

async function classStudent(request: APIRequestContext) {
  const password = randomUUID();
  const session = await responseData(await request.post(`${backend}/api/auth/register`, {
    data: { email: `course-${randomUUID().slice(0, 8)}@example.test`, password },
  }));
  return {
    user: session.user,
    password,
    headers: { Authorization: `Bearer ${session.access_token || session.token}` },
  };
}

async function successfulClassTask(request: APIRequestContext, task: { task_id: string }, headers: Record<string, string>) {
  const path = `${backend}/api/agent/tasks/${task.task_id}`;
  let snapshot;
  await expect.poll(async () => {
    snapshot = await responseData(await request.get(path, { headers }));
    return ['success', 'failed', 'cancelled'].includes(snapshot.status);
  }, { timeout: 12_000 }).toBeTruthy();
  expect(snapshot.status, snapshot.error_message).toBe('success');
  return snapshot.result;
}

test.beforeEach(async ({ request, baseURL }) => {
  for (const value of [backend, ai, baseURL!]) {
    expect(['localhost', '127.0.0.1', '[::1]']).toContain(new URL(value).hostname);
  }
  const response = await request.get(`${ai}/runtime`);
  expect(response.ok()).toBeTruthy();
  const runtime = await response.json();
  expect(runtime.llm.configured).toBe(false);
  expect(runtime.environment).not.toMatch(/prod/);
  // Use a disposable local database. No request/response mocks and no paid model calls.
});

test('student learning loop survives page reloads', async ({ page, request }, info) => {
  test.setTimeout(recording ? 240_000 : 60_000);
  const email = `demo-${randomUUID().slice(0, 8)}@example.test`;
  const password = randomUUID();
  const registered = await request.post(`${backend}/api/auth/register`, { data: { email, password } });
  expect(registered.ok()).toBeTruthy();
  const account = await registered.json();
  expect(account.code).toBe(0);
  await login(page, account.data.user.username, password);

  await page.goto('/profile-chat');
  await hold(page, info, '01-profile-before', 4);
  await page.getByPlaceholder('输入学习反馈...').fill('我想学习二叉树遍历，递归调用栈不熟悉，偏好图示和讲义，每天学习30分钟。');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.locator('.profile-analysis-chip.is-version')).toContainText('v1');
  await expect(page.getByText(/SafetyAgent 已独立复核通过/)).toBeVisible();
  await hold(page, info, '02-profile-ready', 10);
  await page.reload();
  await expect(page.locator('.profile-analysis-chip.is-version')).toContainText('v1');

  await page.goto('/resource-generate');
  for (const name of ['代码实验', '流程图', '拓展阅读']) {
    await page.getByRole('button', { name, exact: true }).click();
  }
  await page.getByRole('combobox').selectOption('basic');
  await hold(page, info, '03-resource-request', 5);
  await page.getByRole('button', { name: '生成个性化资源包', exact: true }).click();
  await expect(page.getByLabel('当前生成进度 100%')).toBeVisible();
  await expect(page.locator('.generated-resource-card')).toHaveCount(3);
  await expect(page.locator('.model-runtime-card')).toContainText('确定性');
  await page.locator('.resource-pack').scrollIntoViewIfNeeded();
  await hold(page, info, '04-resource-package', 10);
  await page.getByRole('button', { name: '查看资源', exact: true }).first().click();
  const detail = page.getByRole('dialog');
  await expect(detail).toBeVisible();
  await expect(detail).toContainText('SafetyAgent');
  await expect(detail).toContainText(/RAG|证据/);
  await hold(page, info, '05-resource-detail', 10);
  await page.getByRole('button', { name: '关闭资源详情' }).click();

  await page.goto('/learning-path');
  await page.getByRole('button', { name: '重新生成路径', exact: true }).click();
  await expect(page.locator('.path-version')).toContainText('当前版本 v1');
  await hold(page, info, '06-learning-path', 10);
  await page.reload();
  await expect(page.locator('.path-version')).toContainText('当前版本 v1');

  await page.goto('/quiz');
  await expect(page.locator('.quiz-question')).toHaveCount(3);
  await page.getByRole('button', { name: '提交并生成评估', exact: true }).click();
  await expect(page.locator('.quiz-error')).toHaveText('请先完成全部 3 道题');
  await hold(page, info, '07-quiz-boundary', 4);
  for (const question of await page.locator('.quiz-question').all()) {
    await question.getByRole('radio').nth(1).check();
    if (recording) await page.waitForTimeout(1500);
  }
  await hold(page, info, '08-quiz-answers', 4);
  await page.getByRole('button', { name: '提交并生成评估', exact: true }).click();
  await expect(page.locator('.evaluation-result')).toBeVisible();
  await expect(page.locator('.path-update-card')).toContainText('路径 v1 → v2');
  await expect(page.locator('.evaluation-result')).toContainText('0 / 3 题正确');
  await page.locator('.evaluation-result').scrollIntoViewIfNeeded();
  await hold(page, info, '09-assessment-and-replan', 10);
  await page.goto('/learning-path');
  await expect(page.locator('.path-version')).toContainText('测评驱动 · v1 → v2');
  await hold(page, info, '10-persisted-replan', 8);
  await page.goto('/profile-chat');
  await expect(page.locator('.profile-analysis-chip.is-version')).toContainText('v2');
  await hold(page, info, '11-persisted-profile', 6);

  await page.goto('/tutor');
  const ask = async (question: string) => {
    await page.locator('.tutor-composer input').fill(question);
    await page.locator('.tutor-composer button[type=submit]').click();
    await expect(page.getByText('回答片段与证据映射')).toBeVisible();
    await expect(page.locator('.tutor-error-line')).toHaveCount(0);
  };
  await ask('二叉树前序遍历的访问顺序是什么？');
  await ask('Cache 的 Index 位数怎么计算？');
  const token = await page.evaluate(() => localStorage.getItem('edupath_token'));
  const sessions = await request.get(`${backend}/api/tutor/sessions`, { headers: { Authorization: `Bearer ${token}` } });
  expect(sessions.ok()).toBeTruthy();
  const { data } = await sessions.json();
  expect(data.items).toHaveLength(2);
  expect(new Set(data.items.map((item: { course_id: number }) => item.course_id)).size).toBe(2);

  if (recording) return;
  await test.step('student studies an uploaded PDF with private page citations', async () => {
    const collection = `${backend}/api/tutor/personal-pdfs`;
    const owner = { Authorization: `Bearer ${token}` };
    const pdf = coursePdf('The first chapter describes basic lenses.', 'Spectral calibration uses a reference lamp.');
    await page.getByRole('button', { name: '打开个人 PDF 辅导' }).click();
    const file = page.getByLabel('上传课程 PDF');
    const submit = page.getByRole('button', { name: '上传 PDF', exact: true });
    const select = page.getByLabel('提问使用的资料');
    await file.setInputFiles({ name: 'invalid.pdf', mimeType: 'application/pdf', buffer: Buffer.from('invalid') });
    await submit.click();
    await expect(page.getByRole('alert')).toContainText('PDF 无法解析');
    await expect(select).toHaveCount(0);

    await file.setInputFiles({ name: '光学校准讲义.pdf', mimeType: 'application/pdf', buffer: pdf });
    await submit.click();
    await expect(select).toContainText('光学校准讲义.pdf（2 页）');
    const id = await select.inputValue();
    const unrelated = coursePdf('The chapter covers glacier erosion.', 'Ice abrasion changes the valley.');
    await file.setInputFiles({ name: '冰川讲义.pdf', mimeType: 'application/pdf', buffer: unrelated });
    await submit.click();
    await expect(select).toContainText('冰川讲义.pdf（2 页）');

    const askPdf = async () => {
      await page.getByLabel('想弄懂哪一部分？').fill('How does spectral calibration work?');
      await page.getByRole('button', { name: '按这份 PDF 提问' }).click();
    };
    await askPdf();
    await expect(page.getByRole('heading', { name: '暂时无法回答' })).toBeVisible();
    await select.selectOption(id);
    await askPdf();
    await expect(page.getByText('Spectral calibration uses a reference lamp.')).toBeVisible();
    await page.getByRole('button', { name: '查看 PDF 第 2 页' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByTitle('课程 PDF 原文')).toHaveAttribute('src', /^blob:.*#page=2$/);
    await page.getByRole('button', { name: '关闭预览' }).click();

    const original = await request.get(`${collection}/${id}/content`, { headers: owner });
    expect(original.headers()['cache-control']).toContain('no-store');
    expect(original.headers()['content-type']).toBe('application/pdf');
    expect(await original.body()).toEqual(pdf);

    const another = await request.post(`${backend}/api/auth/register`, {
      data: { email: `other-${randomUUID().slice(0, 8)}@example.test`, password: randomUUID() },
    });
    expect(another.ok()).toBeTruthy();
    const otherAccount = await another.json();
    const other = { Authorization: `Bearer ${otherAccount.data.access_token || otherAccount.data.token}` };
    const otherList = await request.get(collection, { headers: other });
    expect(otherList.ok()).toBeTruthy();
    expect((await otherList.json()).data).toEqual([]);
    const question = { question: 'How does spectral calibration work?' };
    expect((await request.post(`${collection}/${id}/ask`, { headers: other, data: question })).status()).toBe(404);
    expect((await request.get(`${collection}/${id}/content`, { headers: other })).status()).toBe(404);
    expect((await request.delete(`${collection}/${id}`, { headers: other })).status()).toBe(404);
    expect((await request.get(`${collection}/${id}/content`)).status()).toBe(401);

    await page.reload();
    await page.getByRole('button', { name: '打开个人 PDF 辅导' }).click();
    await expect(select).toContainText('光学校准讲义.pdf');
    await select.selectOption(id);
    page.once('dialog', (confirm) => confirm.accept());
    await page.getByRole('button', { name: '删除此资料' }).click();
    await expect(select.locator('option')).toHaveCount(1);
    expect((await request.post(`${collection}/${id}/ask`, { headers: owner, data: question })).status()).toBe(404);
    expect((await request.get(`${collection}/${id}/content`, { headers: owner })).status()).toBe(404);
  });
});

test('teacher sees a Safety-refused upload as failed', async ({ page, request }) => {
  await login(page, 'teacher', '123456');
  await page.goto('/knowledge-base');
  const filename = `browser-refused-${randomUUID()}.md`;
  const upload = page.waitForResponse(response => response.url().includes('/api/kb/upload') && response.request().method() === 'POST');
  await page.locator('input[type=file]').setInputFiles({
    name: filename, mimeType: 'text/markdown', buffer: Buffer.from('# Safety refusal fixture\n作弊。仅用于拒绝路径验证。', 'utf8'),
  });
  const response = await upload;
  expect(response.status()).toBe(200);
  const result = await response.json();
  expect(result.data.index_status).toBe('failed');
  expect(result.data.safety_status).toBe('review_required');
  const documentId = result.data.document_id;
  try {
    await expect(page.getByRole('alert')).toContainText('资料未进入可检索知识库');
    await expect(page.locator('.document-row').filter({ hasText: filename })).toContainText('failed');
    await page.reload();
    await expect(page.locator('.document-row').filter({ hasText: filename })).toContainText('failed');
  } finally {
    const token = await page.evaluate(() => localStorage.getItem('edupath_token') || '');
    expect(token).not.toBe('');
    const cleanup = await request.delete(`${backend}/api/kb/documents/${documentId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(cleanup.ok()).toBeTruthy();
  }

  if (recording) return;
  await test.step('teacher publishes PDF materials and enrolled students use the complete course', async () => {
    const token = await page.evaluate(() => localStorage.getItem('edupath_token'));
    const teacher = { Authorization: `Bearer ${token}` };
    const member = await classStudent(request);
    const classmate = await classStudent(request);
    const outsider = await classStudent(request);
    const className = `光谱实验班 ${randomUUID().slice(0, 6)}`;
    const name = '光谱校准实验';
    await page.goto('/teacher-insights');
    const manager = page.getByRole('region', { name: '班级课程管理' });
    await manager.getByText('班级与成员', { exact: true }).click();
    await manager.getByLabel('班级名称').fill(className);
    const classCreated = page.waitForResponse(response => response.url().endsWith('/api/classes')
      && response.request().method() === 'POST');
    await manager.getByRole('button', { name: '创建班级', exact: true }).click();
    const classroom = (await (await classCreated).json()).data.class_info;
    for (const student of [member, classmate]) {
      await manager.getByLabel('学生用户名').fill(student.user.username);
      await manager.getByRole('button', { name: '添加学生', exact: true }).click();
      await expect(manager.getByRole('status')).toContainText(`学生「${student.user.username}」已加入班级。`);
    }

    await manager.getByLabel('课程名称').fill(name);
    await manager.getByLabel('课程介绍').fill('以参考灯和测量波长为例学习光谱校准。');
    await manager.getByLabel('知识点（每行一项）').fill('Spectral calibration\nReference lamp');
    const created = page.waitForResponse(response => response.url().endsWith('/api/teaching-courses')
      && response.request().method() === 'POST');
    await manager.getByRole('button', { name: '创建课程草稿' }).click();
    const course = (await (await created).json()).data;
    expect(course.status).toBe('draft');
    const courseId = course.id;
    const catalog = async (headers?: Record<string, string>) => responseData(await request.get(`${backend}/api/courses`, { headers }));
    expect((await catalog(member.headers)).map((item: { id: number }) => item.id)).not.toContain(courseId);

    await manager.getByRole('checkbox', { name: className }).check();
    await expect(manager.getByRole('button', { name: '发布课程', exact: true })).toBeDisabled();
    const lecture = 'Spectral calibration uses a reference lamp. The reference lamp provides a known wavelength. Compare the measured wavelength to the known wavelength.';
    const pdf = coursePdf('This handout introduces a laboratory instrument.', lecture);
    const appendix = coursePdf('Keep the optical bench level.', 'Record ambient light conditions before each observation.');
    const file = manager.getByLabel('选择 PDF（可多选）');
    await file.setInputFiles([
      { name: '校准讲义.pdf', mimeType: 'application/pdf', buffer: pdf },
      { name: '实验附录.pdf', mimeType: 'application/pdf', buffer: appendix },
    ]);
    await manager.getByRole('button', { name: '上传课程 PDF' }).click();
    await expect(manager.getByRole('button', { name: '查看原文', exact: true })).toHaveCount(2);
    await manager.getByRole('checkbox', { name: className }).check();
    await manager.getByRole('button', { name: '发布课程', exact: true }).click();
    await expect(manager.getByRole('button', { name: '撤回课程' })).toBeVisible();

    for (const student of [member, classmate]) {
      expect((await catalog(student.headers)).map((item: { id: number }) => item.id)).toContain(courseId);
    }
    for (const headers of [undefined, outsider.headers]) {
      expect((await catalog(headers)).map((item: { id: number }) => item.id)).not.toContain(courseId);
    }
    const documentsUrl = `${backend}/api/kb/documents?courseId=${courseId}`;
    const documents = await responseData(await request.get(documentsUrl, { headers: member.headers }));
    const document = documents.find((item: { filename: string }) => item.filename === '校准讲义.pdf');
    const appendixDoc = documents.find((item: { filename: string }) => item.filename === '实验附录.pdf');
    expect(documents).toHaveLength(2);
    expect(document.object_key).toBeUndefined();
    expect(document.object_url).toBeUndefined();
    const originalUrl = `${backend}/api/kb/documents/${document.document_id}/content`;
    expect(await (await request.get(originalUrl, { headers: classmate.headers })).body()).toEqual(pdf);
    expect((await request.get(originalUrl, { headers: outsider.headers })).status()).toBe(404);
    expect((await request.get(documentsUrl, { headers: outsider.headers })).status()).toBe(404);
    expect((await request.get(`${backend}/api/courses/${courseId}/knowledge-points`)).status()).toBe(404);
    const reindex = await responseData(await request.post(`${backend}/api/kb/documents/${document.document_id}/reindex`, { headers: teacher }));
    expect((await successfulClassTask(request, reindex, teacher)).index_status).toBe('indexed');
    await responseData(await request.delete(`${backend}/api/kb/documents/${appendixDoc.document_id}`, { headers: teacher }));
    expect((await request.get(`${backend}/api/kb/documents/${appendixDoc.document_id}/content`, { headers: member.headers })).status()).toBe(404);

    await page.evaluate(() => localStorage.clear());
    await login(page, member.user.username, member.password);
    await page.goto(`/knowledge-base?course=${courseId}`);
    const entry = page.getByRole('navigation', { name: '班级课程学习入口' });
    for (const label of ['智能辅导', '生成学习资源', '课程测验', '学习路径']) {
      await expect(entry.getByRole('link', { name: label })).toBeVisible();
    }
    await entry.getByRole('link', { name: '智能辅导' }).click();
    await expect(page.getByLabel('选择智能辅导课程')).toHaveValue(String(courseId));
    const question = 'What is spectral calibration and how do I use a reference lamp?';
    await page.locator('.tutor-composer input').fill(question);
    await page.locator('.tutor-composer button[type=submit]').click();
    await expect(page.getByText('回答片段与证据映射')).toBeVisible();
    await page.getByRole('button', { name: '查看 PDF 第 2 页' }).first().click();
    await expect(page.getByTitle('课程 PDF 原文')).toHaveAttribute('src', /^blob:.*#page=2$/);

    const post = (path: string, data: object, headers = member.headers) =>
      request.post(`${backend}${path}`, { headers, data });
    const tutorData = { course_id: courseId, question };
    const resourceData = { course_id: courseId, knowledge_point_ids: [course.topics[0].id], resource_types: ['lecture'] };
    const quizData = { course_id: courseId, question_count: 1 };
    const pathData = { course_ids: [courseId], target: 'Spectral calibration', days: 3, daily_minutes: 20 };
    for (const [path, payload] of [
      ['/api/tutor/chat', tutorData], ['/api/kb/search', { course_id: courseId, query: question }], ['/api/agent/resource-task', resourceData],
      ['/api/quiz/generate', quizData], ['/api/path/generate', pathData],
    ] as const) {
      expect((await post(path, payload, outsider.headers)).status(), path).toBe(404);
    }

    async function runTask(path: string, payload: object) {
      const task = await responseData(await post(path, payload));
      return { id: task.task_id, result: await successfulClassTask(request, task, member.headers) };
    }
    const resource = await runTask('/api/agent/resource-task', resourceData);
    expect(resource.result.resources).toHaveLength(1);
    expect(resource.result.evidence).toEqual(expect.arrayContaining([
      expect.objectContaining({ document_id: document.document_id, page: 2 }),
    ]));
    const path = await runTask('/api/path/generate', pathData);
    expect(path.result.path_id).toBeTruthy();
    const current = await responseData(await request.get(`${backend}/api/path/current?course_id=${courseId}`, { headers: member.headers }));
    expect(current.course_ids).toContain(courseId);
    const quiz = await runTask('/api/quiz/generate', quizData);
    expect(quiz.result.questions).toHaveLength(1);
    const first = quiz.result.questions[0];
    expect(first.answer).toBeUndefined();
    const score = await runTask('/api/quiz/submit', { quiz_id: quiz.result.quiz_id, answers: [{ question_id: first.question_id, answer: 'A' }] });
    expect(score.result.score).toEqual(expect.any(Number));
    const insight = await responseData(await request.get(`${backend}/api/classes/${classroom.id}/insights?course_id=${courseId}`, { headers: teacher }));
    expect(insight.summary.student_count).toBe(2);

    await responseData(await request.put(`${backend}/api/teaching-courses/${courseId}/publication`, {
      headers: teacher, data: { class_ids: [classroom.id], published: false },
    }));
    expect((await catalog(member.headers)).map((item: { id: number }) => item.id)).not.toContain(courseId);
    expect((await request.get(originalUrl, { headers: member.headers })).status()).toBe(404);
    expect((await request.get(`${backend}/api/agent/tasks/${resource.id}`, { headers: member.headers })).status()).toBe(404);
  });
});

test('administrator issues a teacher invite and the teacher opens the workbench', async ({ page, browser, baseURL }) => {
  await login(page, 'admin', '123456');
  await page.goto('/user-center');
  const access = page.getByRole('region', { name: '教师邀请码与评委链接' });
  const email = `teacher-${randomUUID().slice(0, 8)}@example.test`;
  await access.getByLabel('教师邮箱（可选）').fill(email);
  await access.getByRole('button', { name: '生成一次性邀请码' }).click();
  const code = access.getByLabel('教师邀请码', { exact: true });
  await expect(code).toHaveValue(/^ti_/);

  const context = await browser.newContext({ baseURL });
  try {
    const teacher = await context.newPage();
    await teacher.goto('/login');
    await teacher.getByRole('button', { name: '教师邀请码', exact: true }).click();
    const form = teacher.getByRole('form', { name: '教师邀请码注册' });
    await form.getByLabel('教师邀请码').fill(await code.inputValue());
    await form.getByLabel('邮箱').fill(email);
    await form.getByPlaceholder('设置登录密码').fill('Classroom-preview-password');
    await form.getByPlaceholder('再次输入密码').fill('Classroom-preview-password');
    await form.getByRole('button', { name: '开通教师账号' }).click();
    await expect(teacher).toHaveURL(/\/teacher-insights$/);
    await expect(teacher.getByRole('region', { name: '班级课程管理' })).toBeVisible();
  } finally {
    await context.close();
  }
});

test('review link opens both roles in the same course and keeps the administrator session separate', async ({ page, request }, info) => {
  await login(page, 'admin', '123456');
  const adminToken = await page.evaluate(() => localStorage.getItem('edupath_token'));
  const config = await responseData(await request.get(`${backend}/api/admin/access/review-links`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  }));
  const reviewer = await page.context().newPage();
  try {
    if (!config.enabled) {
      await page.goto('/user-center');
      await expect(page.getByText('在独立演示环境开启评委入口后，可以在这里生成链接。')).toBeVisible();
      const response = await request.post(`${backend}/api/auth/review`, { data: { code: 'disabled', role: 'teacher' } });
      expect(response.status()).toBe(404);
      return;
    }

    await page.goto('/user-center');
    const access = page.getByRole('region', { name: '教师邀请码与评委链接' });
    const issued = page.waitForResponse(response => response.url().endsWith('/api/admin/access/review-links')
      && response.request().method() === 'POST');
    await access.getByRole('button', { name: '生成评委链接' }).click();
    const link = await responseData(await issued);
    const url = access.getByLabel('评委专属链接', { exact: true });
    await expect(url).toHaveValue(/\/login#review=rv_/);
    await reviewer.goto(await url.inputValue());
    await expect(reviewer).toHaveURL(/\/login$/);
    const reviewCard = reviewer.getByRole('region', { name: '评委体验' });
    const submit = reviewer.getByRole('form', { name: '账号登录' }).getByRole('button', { name: /进入知径/ });
    const box = await reviewCard.boundingBox();
    const submitBox = await submit.boundingBox();
    expect(box?.y).toBeGreaterThan(submitBox!.y + submitBox!.height);
    await reviewer.screenshot({ path: info.outputPath('review-login-layout.png'), animations: 'disabled' });
    for (const width of [390, 1024]) {
      await reviewer.setViewportSize({ width, height: 844 });
      await expect(reviewCard.getByRole('button', { name: /进入教师工作台/ })).toBeVisible();
      expect(await reviewer.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    }
    await reviewer.setViewportSize({ width: 1440, height: 1000 });
    await reviewCard.getByRole('button', { name: /进入教师工作台/ }).click();
    await expect(reviewer).toHaveURL(/\/teacher-insights$/);
    await expect(reviewer.getByText('评委体验 · 教师端')).toBeVisible();
    expect(await reviewer.evaluate(() => JSON.parse(localStorage.getItem('edupath_user')!).role)).toBe('admin');

    const manager = reviewer.getByRole('region', { name: '班级课程管理' });
    const name = `评委体验 · 光谱校准 ${randomUUID().slice(0, 6)}`;
    await manager.getByLabel('课程名称').fill(name);
    await manager.getByLabel('知识点（每行一项）').fill('Spectral calibration\nReference lamp');
    await manager.getByRole('button', { name: '创建课程草稿' }).click();
    await manager.getByLabel('选择 PDF（可多选）').setInputFiles({
      name: '评委示例讲义.pdf',
      mimeType: 'application/pdf',
      buffer: coursePdf(
        'This handout introduces a laboratory instrument.',
        'Spectral calibration uses a reference lamp. The reference lamp provides a known wavelength.',
      ),
    });
    await manager.getByRole('button', { name: '上传课程 PDF' }).click();
    await expect(manager.getByRole('button', { name: '查看原文', exact: true })).toHaveCount(1);
    await manager.getByRole('checkbox', { name: link.class_name }).check();
    await manager.getByRole('button', { name: '发布课程', exact: true }).click();
    await expect(manager.getByRole('button', { name: '撤回课程' })).toBeVisible();

    await reviewer.getByRole('button', { name: '切换体验身份' }).click();
    await reviewCard.getByRole('button', { name: /进入学生端/ }).click();
    await expect(reviewer).toHaveURL(/\/learning-home$/);
    const course = reviewer.getByRole('region', { name: '我的班级课程' }).getByRole('article').filter({ hasText: name });
    await expect(course).toBeVisible();
    await course.getByRole('link', { name: '进入课程' }).click();
    await expect(reviewer.getByText('评委示例讲义.pdf', { exact: true })).toBeVisible();
    await reviewer.getByRole('link', { name: '智能辅导' }).click();
    await reviewer.locator('.tutor-composer input').fill('What is spectral calibration and what is a reference lamp?');
    await reviewer.locator('.tutor-composer button[type=submit]').click();
    await expect(reviewer.getByRole('button', { name: '查看 PDF 第 2 页' }).first()).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('edupath_token'))).toBe(adminToken);
  } finally {
    await reviewer.close();
  }
});
