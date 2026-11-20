import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext } from '@playwright/test';

const backend = process.env.EDUPATH_BACKEND_URL || 'http://127.0.0.1:8080';
const path = '/api/evaluation/tree-practice';
const cachePath = '/api/evaluation/cache-practice';

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
  const email = `tree-${randomUUID().slice(0, 8)}@example.test`;
  const password = randomUUID();
  const response = await request.post(backend + '/api/auth/register', { data: { email, password } });
  expect(response.ok()).toBeTruthy();
  const { data } = await response.json();
  return {
    username: data.user.username as string,
    password,
    headers: { Authorization: `Bearer ${data.token}` },
  };
}

test('二叉树复测可切换课程，刷新后恢复两次作答', async ({ page, request }, info) => {
  const student = await register(request);
  await page.goto('/login');
  await page.getByPlaceholder('请输入用户名或邮箱').fill(student.username);
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(student.password);
  await page.locator('form').getByRole('button', { name: /进入知径/ }).click();
  await expect(page).not.toHaveURL(/\/login$/);
  await page.goto('/evaluation');
  const picker = page.getByRole('group', { name: '选择专项练习' });
  const treeButton = picker.getByRole('button', { name: '二叉树递归遍历', exact: true });
  await treeButton.click();
  await expect(treeButton).toHaveAttribute('aria-pressed', 'true');
  const practice = page.getByRole('region', { name: '二叉树递归遍历专项练习' });
  await expect(practice.getByRole('table', { name: '二叉树的左右孩子' })).toBeVisible();
  await expect(practice.getByRole('row', { name: 'B 无 D', exact: true })).toBeVisible();
  await practice.getByLabel('访问序列').fill('B，D，A，E，C，F');
  await practice.getByRole('button', { name: '提交诊断' }).click();
  await expect(practice.getByText(/你的序列与中序遍历一致/)).toBeVisible();
  await expect(practice.getByText('下一题继续练前序遍历。', { exact: false })).toBeVisible();
  await expect(practice.getByText('首次诊断已保存。打开新题后仍可查看首答与课程依据。')).toBeVisible();
  await expect(practice.locator('form')).toHaveCount(0);
  await expect(practice.getByRole('heading', { name: '诊断与补救' })).toBeFocused();
  const evidence = practice.getByText('查看课程依据', { exact: true });
  await page.keyboard.press('Tab');
  await expect(evidence).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(practice.locator('blockquote')).toContainText('左→根→右');
  if (process.env.EDUPATH_P3_EVIDENCE === '1') {
    await practice.evaluate((element) => element.scrollIntoView({ block: 'center' }));
    await practice.screenshot({ path: info.outputPath('01-tree-diagnosis.png') });
  }

  const openRetest = practice.getByRole('button', { name: '开始新题复测' });
  await page.keyboard.press('Tab');
  await expect(openRetest).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(practice.getByRole('heading', { name: '新题复测', exact: true })).toBeFocused();
  await expect(practice.locator('form')).toContainText('前序遍历');
  await expect(practice.getByRole('heading', { name: '首次诊断结果' })).toBeVisible();
  await expect(practice.locator('blockquote')).toBeVisible();
  await page.reload();
  await expect(practice.getByRole('heading', { name: '首次诊断结果' })).toBeVisible();
  await expect(practice.getByRole('heading', { name: '新题复测结果' })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await openRetest.click();
  await expect(practice.getByRole('row', { name: 'M J R', exact: true })).toBeVisible();
  const tableBounds = await practice.getByRole('table').boundingBox();
  expect(tableBounds).not.toBeNull();
  expect(tableBounds!.x).toBeGreaterThanOrEqual(0);
  expect(tableBounds!.x + tableBounds!.width).toBeLessThanOrEqual(390);
  await practice.getByLabel('访问序列').fill('m -> j -> h -> l -> r -> t');
  await practice.getByRole('button', { name: '提交复测' }).click();
  await expect(practice.getByText('复测已记录')).toBeVisible();

  await page.setViewportSize({ width: 1440, height: 1000 });
  await picker.getByRole('button', { name: 'Cache 地址拆分', exact: true }).click();
  const cache = page.getByRole('region', { name: 'Cache 地址拆分专项练习' });
  await expect(cache.getByRole('heading', { name: '首次诊断', exact: true })).toBeVisible();

  await treeButton.click();
  await page.reload();
  await expect(treeButton).toHaveAttribute('aria-pressed', 'true');
  await expect(practice.getByRole('heading', { name: '首次诊断结果' })).toBeVisible();
  await expect(practice.getByRole('heading', { name: '新题复测结果' })).toBeVisible();
  await expect(practice.getByText('全部正确', { exact: true })).toBeVisible();
  await expect(practice.getByText(/M → J → H → L → R → T/).first()).toBeVisible();
  if (process.env.EDUPATH_P3_EVIDENCE === '1') {
    await practice.evaluate((element) => element.scrollIntoView({ block: 'center' }));
    await practice.screenshot({ path: info.outputPath('02-tree-reloaded.png') });
  }
});

test('右先于左走不同的复测分支，重复提交与跨学生隔离', async ({ request }) => {
  const student = await register(request);
  const other = await register(request);
  const endpoint = backend + path;
  const post = (exerciseId: string, sequence: string[]) => request.post(endpoint + '/attempts', {
    headers: student.headers,
    data: { exercise_id: exerciseId, sequence },
  });
  expect((await request.get(endpoint)).status()).toBe(401);
  const start = await (await request.get(endpoint, { headers: student.headers })).json();
  expect(start.data.next_exercise.expected).toBeUndefined();
  expect((await post('tree-v1-direction', ['P', 'Z', 'M', 'B', 'R'])).status()).toBe(409);
  expect((await post('tree-v1-initial', ['AF'])).status()).toBe(400);

  const incorrect = ['A', 'C', 'F', 'E', 'B', 'D'];
  const responses = await Promise.all([post('tree-v1-initial', incorrect), post('tree-v1-initial', incorrect)]);
  expect(responses.map((response) => response.status())).toEqual([200, 200]);
  const [first, repeated] = await Promise.all(responses.map((response) => response.json()));
  expect(first.data.initial).toEqual(repeated.data.initial);
  expect(first.data.initial.feedback.mistake).toBe('right_before_left');
  expect(first.data.initial.feedback.citation.section).toBe('固定左右子树顺序');
  expect(first.data.next_exercise.id).toBe('tree-v1-direction');
  expect((await post('tree-v1-initial', ['A'])).status()).toBe(409);

  const completed = await post('tree-v1-direction', ['P', 'Z', 'M', 'B', 'R']);
  expect(completed.status()).toBe(200);
  expect((await completed.json()).data.retest.feedback.correct).toBe(true);
  expect((await (await request.get(endpoint, { headers: other.headers })).json()).data.stage).toBe('initial');
  expect((await (await request.get(backend + cachePath, { headers: student.headers })).json()).data.stage).toBe('initial');
});

test('首答误用中序时，新题继续按前序判分', async ({ request }) => {
  const student = await register(request);
  const submit = (id: string, sequence: string[]) => request.post(backend + path + '/attempts', {
    headers: student.headers, data: { exercise_id: id, sequence },
  });

  const initial = await submit('tree-v1-initial', ['B', 'D', 'A', 'E', 'C', 'F']);
  expect(initial.status()).toBe(200);
  const { data } = await initial.json();
  expect(data.initial.feedback.mistake).toBe('visit_timing');
  expect(data.next_exercise).toMatchObject({ id: 'tree-v2-timing', order: 'preorder' });
  const retest = await submit(data.next_exercise.id, ['H', 'J', 'L', 'M', 'R', 'T']);
  expect(retest.status()).toBe(200);
  const result = (await retest.json()).data;
  expect(result.stage).toBe('complete');
  expect(result.retest.feedback.correct).toBe(false);
  expect(result.retest.feedback.mistake).toBe('visit_timing');
  expect(result.retest.feedback.expected).toEqual(['M', 'J', 'H', 'L', 'R', 'T']);
});
