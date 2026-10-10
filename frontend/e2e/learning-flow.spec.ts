import { randomUUID } from 'node:crypto';
import { expect, test, type Page, type TestInfo } from '@playwright/test';

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
});
