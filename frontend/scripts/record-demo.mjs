import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const require = createRequire(import.meta.url);
const result = spawnSync(process.execPath, [require.resolve('@playwright/test/cli'), 'test', '--grep', 'student learning loop'], {
  stdio: 'inherit', env: { ...process.env, EDUPATH_DEMO_RECORDING: '1' },
});
if (result.status !== 0) process.exit(result.status || 1);
const report = JSON.parse(readFileSync('test-results/results.json', 'utf8'));
if (report.stats.expected !== 1 || report.stats.unexpected || report.stats.flaky || report.stats.skipped) {
  throw new Error('Recording must come from exactly one successful, unretried test.');
}
const directory = readdirSync('test-results', { withFileTypes: true }).find(entry =>
  entry.isDirectory() && existsSync(resolve('test-results', entry.name, '11-persisted-profile.png')));
if (!directory) throw new Error('Completed recording artifacts were not found.');
const source = resolve('test-results', directory.name);
const destination = resolve('../docs/evidence');
mkdirSync(destination, { recursive: true });
for (const [from, to] of [
  ['video.webm', 'core-learning-flow.webm'],
  ['04-resource-package.png', 'demo-resource-package.png'],
  ['09-assessment-and-replan.png', 'demo-assessment.png'],
  ['11-persisted-profile.png', 'demo-profile.png'],
]) copyFileSync(resolve(source, from), resolve(destination, to));
writeFileSync(resolve(destination, 'browser-recording.json'), `${JSON.stringify({
  status: 'passed', verified_at_utc: new Date().toISOString(),
  scope: 'Chromium UI -> Vite proxy -> Java/H2 -> AI; no HTTP mocks',
  mode: 'deterministic_fallback', paid_model_calls: 0,
  duration_seconds: Math.round(report.stats.duration / 1000),
  duration_scope: 'Playwright run duration, including setup; video length can differ',
  viewport: { width: 1440, height: 1000 },
  assertions: ['profile v1 persists', 'three requested resource types rendered',
    'resource content and evidence open', 'path v1 persists', 'incomplete quiz is refused in UI',
    'three wrong answers score 0', 'path v1 to v2 persists', 'profile v2 persists'],
  limitations: ['synthetic account; no real student feedback', 'not real-model quality or production deployment verification'],
}, null, 2)}\n`, 'utf8');
console.log('Verified recording and three screenshots copied to docs/evidence/.');
