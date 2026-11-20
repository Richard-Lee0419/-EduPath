import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { expect, test, type APIRequestContext } from '@playwright/test';

const backend = process.env.EDUPATH_BACKEND_URL || 'http://127.0.0.1:8080';
const practicePath = '/api/evaluation/cache-practice';

test.use({
  channel: process.env.EDUPATH_BROWSER_CHANNEL || undefined,
  video: 'off',
});

test.beforeEach(({ baseURL }) => {
  for (const url of [baseURL!, backend]) {
    expect(['localhost', '127.0.0.1', '[::1]']).toContain(new URL(url).hostname);
  }
});

async function register(request: APIRequestContext) {
  const email = `cache-${randomUUID().slice(0, 8)}@example.test`;
  const password = randomUUID();
  const response = await request.post(`${backend}/api/auth/register`, { data: { email, password } });
  expect(response.ok()).toBeTruthy();
  const { data } = await response.json();
  const login = await request.post(`${backend}/api/auth/login`, {
    data: { username: data.user.username, password },
  });
  expect(login.ok()).toBeTruthy();
  const session = await login.json();
  return {
    username: data.user.username as string,
    password,
    headers: { Authorization: `Bearer ${session.data.access_token || session.data.token}` },
  };
}

test('Cache 诊断、课程依据和新题复测在刷新后可回看', async ({ page, request }, info) => {
  const student = await register(request);
  await page.goto('/login');
  await page.getByPlaceholder('请输入用户名或邮箱').fill(student.username);
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(student.password);
  await page.locator('form').getByRole('button', { name: /进入知径/ }).click();
  await expect(page).not.toHaveURL(/\/login$/);
  await page.goto('/evaluation');
  const practice = page.getByRole('region', { name: 'Cache 地址拆分专项练习' });
  await expect(page.getByText('尚未评估', { exact: true })).toBeVisible();
  await expect(practice.getByRole('heading', { name: '首次诊断', exact: true })).toBeVisible();
  const tag = practice.getByLabel('Tag 位数');
  const index = practice.getByLabel('Index 位数');
  const offset = practice.getByLabel('Offset 位数');
  await tag.focus();
  await page.keyboard.type('20');
  await page.keyboard.press('Tab');
  await expect(index).toBeFocused();
  await page.keyboard.type('8');
  await page.keyboard.press('Tab');
  await expect(offset).toBeFocused();
  await page.keyboard.type('4');
  await page.keyboard.press('Tab');
  await expect(practice.getByRole('button', { name: '提交诊断' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(practice.getByText(/把总块数直接当成组数/)).toBeVisible();
  await practice.getByText('查看课程依据', { exact: true }).click();
  await expect(practice.locator('blockquote')).toContainText('组数 S 为 C ÷ (B × W)');
  if (process.env.EDUPATH_P2_EVIDENCE === '1') {
    await practice.screenshot({ path: info.outputPath('01-diagnosis.png') });
  }

  await practice.getByRole('button', { name: '开始新题复测' }).click();
  await expect(practice.locator('form')).toContainText('2 路组相联');
  await practice.getByLabel('Tag 位数').fill('21');
  await practice.getByLabel('Index 位数').fill('7');
  await practice.getByLabel('Offset 位数').fill('4');
  await practice.getByRole('button', { name: '提交复测' }).click();
  await expect(practice.getByText('复测已记录')).toBeVisible();
  await page.reload();
  await expect(practice.getByRole('heading', { name: '首次诊断结果' })).toBeVisible();
  await expect(practice.getByRole('heading', { name: '新题复测结果' })).toBeVisible();
  await expect(practice.getByText('全部正确', { exact: true })).toBeVisible();
  await expect(practice.getByText(/Tag 21 位 · Index 7 位 · Offset 4 位/)).toBeVisible();
  if (process.env.EDUPATH_CACHE_TEST_EXPECT_AGENTARTS === '1') {
    const receipts = practice.getByText(/AgentArts 执行编号/);
    await expect(receipts).toHaveCount(2);
    const response = await request.get(backend + practicePath, { headers: student.headers });
    expect(response.ok()).toBeTruthy();
    const { data } = await response.json();
    for (const attempt of [data.initial, data.retest]) {
      expect(attempt.feedback.provenance).toEqual({
        source: 'agentarts',
        request_id: expect.stringMatching(/^[0-9a-f-]{36}$/),
        execution_id: expect.stringMatching(/^[A-Za-z0-9_-]+$/),
      });
      await expect(practice.getByText(attempt.feedback.provenance.execution_id, { exact: true })).toBeVisible();
    }
    expect(data.initial.feedback.provenance.request_id).not.toBe(data.retest.feedback.provenance.request_id);
    if (process.env.EDUPATH_P2_EVIDENCE === '1') {
      await writeFile(info.outputPath('agentarts-executions.json'), JSON.stringify({
        initial: data.initial.feedback.provenance,
        retest: data.retest.feedback.provenance,
      }, null, 2));
    }
  }
  if (process.env.EDUPATH_P2_EVIDENCE === '1') {
    await practice.screenshot({ path: info.outputPath('02-reloaded.png') });
  }
});

test('专项作答具备隔离、乱序校验和并发幂等保护', async ({ request }) => {
  const student = await register(request);
  const other = await register(request);
  const read = (headers: Record<string, string>) => request.get(backend + practicePath, { headers });
  const commit = (data: object) => request.post(backend + practicePath + '/attempts', {
    headers: student.headers,
    data,
  });
  expect((await read({})).status()).toBe(401);
  const fresh = await (await read(student.headers)).json();
  expect(fresh.data.stage).toBe('initial');
  expect(fresh.data.next_exercise.expected).toBeUndefined();
  expect((await commit({ exercise_id: 'cache-v1-offset', tag: 22, index: 5, offset: 5 })).status()).toBe(409);
  const initial = { exercise_id: 'cache-v1-initial', tag: 21, index: 6, offset: 5 };
  expect((await commit({ ...initial, index: 6.1 })).status()).toBe(400);

  const results = await Promise.all([commit(initial), commit(initial)]);
  expect(results.map((response) => response.status())).toEqual([200, 200]);
  const [a, b] = await Promise.all(results.map((response) => response.json()));
  expect(a.data.initial).toEqual(b.data.initial);
  expect(a.data.initial.feedback.mistake).toBe('offset_bit_count');
  expect(a.data.focus).toContain('字节偏移从 0 开始');
  expect(a.data.next_exercise.block_bytes).toBe(32);
  expect((await commit({ ...initial, tag: 22 })).status()).toBe(409);

  const otherStudent = await (await read(other.headers)).json();
  expect(otherStudent.data.stage).toBe('initial');
  expect(otherStudent.data.initial ?? null).toBeNull();

  const followUp = await commit({ exercise_id: 'cache-v1-offset', tag: 22, index: 5, offset: 5 });
  expect(followUp.status()).toBe(200);
  const result = await followUp.json();
  expect(result.data.stage).toBe('complete');
  expect(result.data.retest.feedback.correct).toBe(true);
  const replay = await commit({ exercise_id: 'cache-v1-offset', tag: 22, index: 5, offset: 5 });
  expect((await replay.json()).data).toEqual(result.data);
});
