// Automated browser tests. Geolocation is SIMULATED here (labelled test only);
// Overpass responses are mocked with a small synthetic grid near Yerevan.
import { expect, test, type Page, type BrowserContext } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ll } from '../../scripts/fixture-lib';

const GRID = readFileSync(join(process.cwd(), 'tests/e2e/data/grid-overpass.json'), 'utf8');
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64');

async function mockNetwork(ctx: BrowserContext, overpass: 'ok' | 429 | 'offline' = 'ok') {
  await ctx.route(/tile\.openstreetmap\.org/, (r) => r.fulfill({ status: 200, contentType: 'image/png', body: PNG }));
  await ctx.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.fulfill({ status: 200, body: '' }));
  await ctx.route(/overpass|interpreter/, (r) => {
    if (overpass === 'ok') return r.fulfill({ status: 200, contentType: 'application/json', body: GRID });
    if (overpass === 429) return r.fulfill({ status: 429, body: 'rate limited' });
    return r.abort('internetdisconnected');
  });
}

async function mapClick(page: Page, x: number, y: number) {
  const [lon, lat] = ll(x, y);
  const px = await page.evaluate(([lon, lat]) => {
    const m = (window as unknown as { __coverageMap: { map: { project: (p: [number, number]) => { x: number; y: number } } } }).__coverageMap.map;
    const p = m.project([lon, lat]);
    return { x: p.x, y: p.y };
  }, [lon, lat]);
  const box = (await page.getByTestId('map').boundingBox())!;
  await page.mouse.click(box.x + px.x, box.y + px.y);
}

async function setup(page: Page) {
  await page.goto('/');
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await page.getByRole('button', { name: 'تنظیمات' }).click();
  await page.getByRole('button', { name: 'English' }).click();
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
  await page.getByRole('button', { name: 'Prepare' }).click();
  await page.evaluate(() => (window as unknown as { __coverageMap: { map: { jumpTo: (o: object) => void } } }).__coverageMap.map.jumpTo({ center: [44.50118, 40.18045], zoom: 16 }));
  await page.waitForTimeout(400);
}

async function drawZoneAndLoad(page: Page) {
  for (const [x, y] of [[-30, -30], [230, -30], [230, 130], [-30, 130]] as [number, number][]) await mapClick(page, x, y);
  await expect(page.getByTestId('zone-status')).toContainText('Area');
  await page.getByTestId('load-roads').click();
  await expect(page.getByTestId('graph-stats')).toContainText('Road network loaded');
}

async function chooseStartAndPlan(page: Page) {
  await page.getByTestId('pick-start').click();
  await mapClick(page, 10, 2);
  await expect(page.getByTestId('start-info')).toContainText('Աբովյան');
  await page.getByTestId('confirm-start').click();
  await page.getByTestId('plan').click();
  await expect(page.getByTestId('plan-summary')).toContainText('complete');
  await expect(page.getByTestId('plan-summary')).toContainText('✓');
}

test('zone validation: self-intersection, undo, circle radius limits', async ({ context, page }) => {
  await mockNetwork(context);
  await setup(page);
  for (const [x, y] of [[0, 0], [200, 100], [200, 0], [0, 100]] as [number, number][]) await mapClick(page, x, y);
  await expect(page.getByTestId('zone-status')).toContainText('cross');
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(page.getByTestId('zone-status')).toContainText('Area');
  await page.getByRole('button', { name: 'Circle' }).click();
  await mapClick(page, 100, 50);
  await page.getByTestId('radius').fill('5');
  await expect(page.getByTestId('zone-status')).toContainText('at least 20 m');
  await page.getByTestId('radius').fill('150');
  await expect(page.getByTestId('zone-status')).toContainText('Area');
  await expect(page.getByTestId('start-driving')).toBeDisabled();
  await expect(page.getByTestId('start-blockers')).toContainText('load the road network first');
});

test('provider quota error is a clear non-success state', async ({ context, page }) => {
  await mockNetwork(context, 429);
  await setup(page);
  for (const [x, y] of [[-30, -30], [230, -30], [230, 130]] as [number, number][]) await mapClick(page, x, y);
  await page.getByTestId('load-roads').click();
  await expect(page.getByTestId('graph-error')).toContainText('provider_quota_exceeded', { timeout: 30_000 });
  await expect(page.getByTestId('start-driving')).toBeDisabled();
});

test('network loss while loading roads', async ({ context, page }) => {
  await mockNetwork(context, 'offline');
  await setup(page);
  for (const [x, y] of [[-30, -30], [230, -30], [230, 130]] as [number, number][]) await mapClick(page, x, y);
  await page.getByTestId('load-roads').click();
  await expect(page.getByTestId('graph-error')).toContainText('network_unavailable', { timeout: 30_000 });
});

test('full flow with simulated GPS: plan, drive, direction-sensitive progress, pause, reload, resume', async ({ context, page }) => {
  await mockNetwork(context);
  await context.grantPermissions(['geolocation']);
  const [lon0, lat0] = ll(0, 0);
  await context.setGeolocation({ longitude: lon0, latitude: lat0, accuracy: 5 });
  await setup(page);
  await drawZoneAndLoad(page);
  await chooseStartAndPlan(page);
  if (process.env.SHOT_DIR) await page.screenshot({ path: process.env.SHOT_DIR + '/prepare.png' });
  // button repeat clicks must not create duplicate sessions
  await page.getByTestId('start-driving').dblclick();
  await expect(page.getByTestId('drive-panel')).toBeVisible();
  for (let x = 0; x <= 100; x += 10) {
    const [lon, lat] = ll(x, 0);
    await context.setGeolocation({ longitude: lon, latitude: lat, accuracy: 5 });
    await page.waitForTimeout(1050);
  }
  await expect(page.getByTestId('gps-state')).toContainText('Reliable');
  if (process.env.SHOT_DIR) await page.screenshot({ path: process.env.SHOT_DIR + '/drive.png' });
  await expect(page.getByTestId('progress')).not.toContainText('Confirmed: 0 m');
  const before = await page.getByTestId('progress').innerText();
  await page.getByTestId('pause').click();
  await expect(page.getByTestId('gps-state')).toContainText('Paused');
  await page.reload();
  await page.getByRole('button', { name: 'Resume' }).first().click();
  await expect(page.getByTestId('drive-panel')).toBeVisible();
  const after = await page.getByTestId('progress').innerText();
  expect(after.split('\n')[0]).toBe(before.split('\n')[0]);
  // resume driving, then reload while ACTIVE (simulated crash): must offer an explicit Resume
  await page.getByTestId('resume').click();
  await expect(page.getByTestId('pause')).toBeVisible();
  for (let y = 10; y <= 40; y += 10) {
    const [lon, lat] = ll(100, y);
    await context.setGeolocation({ longitude: lon, latitude: lat, accuracy: 5 });
    await page.waitForTimeout(1050);
  }
  await page.reload();
  await page.getByRole('button', { name: 'Resume' }).first().click();
  await expect(page.getByTestId('recovered')).toBeVisible();
  await expect(page.getByTestId('resume')).toBeVisible();
  await page.getByTestId('resume').click();
  await expect(page.getByTestId('gps-state')).not.toContainText('Paused');
  // sessions list shows exactly one session (no duplicate from double click)
  await page.getByRole('button', { name: 'Sessions' }).click();
  await expect(page.locator('.sessions li')).toHaveCount(1);
  // export then import as a copy
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Export' }).click()]);
  const path = await download.path();
  await page.getByTestId('import-file').setInputFiles(path!);
  await expect(page.getByRole('status')).toContainText('Session imported');
  await expect(page.locator('.sessions li')).toHaveCount(2);
  // corrupt import is rejected
  await page.getByTestId('import-file').setInputFiles({ name: 'bad.json', mimeType: 'application/json', buffer: Buffer.from('{"packageType":') });
  await expect(page.getByRole('status')).toContainText('corrupt_json');
});

test('permission denied shows explicit state', async ({ browser }) => {
  const context = await browser.newContext({ locale: 'en-US', permissions: [] });
  await mockNetwork(context);
  const page = await context.newPage();
  await page.addInitScript(() => {
    // Simulate a denied permission prompt deterministically.
    Object.defineProperty(navigator, 'geolocation', {
      value: {
        watchPosition: (_s: unknown, e: (err: { code: number; PERMISSION_DENIED: number; message: string }) => void) => { setTimeout(() => e({ code: 1, PERMISSION_DENIED: 1, message: 'denied' }), 50); return 1; },
        clearWatch: () => undefined,
        getCurrentPosition: (_s: unknown, e: (err: { code: number; PERMISSION_DENIED: number; message: string }) => void) => e({ code: 1, PERMISSION_DENIED: 1, message: 'denied' }),
      },
    });
  });
  await setup(page);
  await drawZoneAndLoad(page);
  await chooseStartAndPlan(page);
  await page.getByTestId('start-driving').click();
  await expect(page.getByTestId('gps-state')).toContainText('Location permission denied');
  await expect(page.getByTestId('maneuver')).toContainText('No guidance');
  await context.close();
});

test('off-road start is rejected; tab hide records an unverified gap; wake-lock refusal is visible', async ({ context, page }) => {
  await mockNetwork(context);
  await context.grantPermissions(['geolocation']);
  const [lon0, lat0] = ll(0, 0);
  await context.setGeolocation({ longitude: lon0, latitude: lat0, accuracy: 5 });
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'wakeLock', { value: { request: () => Promise.reject(new Error('NotAllowedError')) }, configurable: true });
  });
  await setup(page);
  await drawZoneAndLoad(page);
  // off-road start: 200 m away from any road (outside 150 m snap radius)
  await page.evaluate(() => (window as unknown as { __coverageMap: { map: { jumpTo: (o: object) => void } } }).__coverageMap.map.jumpTo({ zoom: 14.5 }));
  await page.waitForTimeout(300);
  await page.getByTestId('pick-start').click();
  await mapClick(page, 100, -330);
  await expect(page.getByText('No legal road within 150 m')).toBeVisible();
  await page.evaluate(() => (window as unknown as { __coverageMap: { map: { jumpTo: (o: object) => void } } }).__coverageMap.map.jumpTo({ center: [44.50118, 40.18045], zoom: 16 }));
  await page.waitForTimeout(300);
  await chooseStartAndPlan(page);
  await page.getByTestId('start-driving').click();
  await expect(page.getByTestId('drive-panel')).toContainText('Screen may sleep');
  // simulate the tab being hidden for 6 s, then visible again
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.waitForTimeout(6000);
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(page.getByTestId('drive-panel')).toContainText('Unverified gap recorded');
});

test('position is always shown and the driving banner gives turn-by-turn directions', async ({ context, page }) => {
  await mockNetwork(context);
  await context.grantPermissions(['geolocation']);
  // Start away from the planned start: on the far end of the grid.
  const [lon0, lat0] = ll(200, 50);
  await context.setGeolocation({ longitude: lon0, latitude: lat0, accuracy: 8 });
  await setup(page);
  // before any session: "you are here" is shown
  await expect(page.getByTestId('where-chip')).toContainText('You are here');
  await drawZoneAndLoad(page);
  await chooseStartAndPlan(page);
  await page.getByTestId('start-driving').click();
  await context.setGeolocation({ longitude: lon0 + 0.0000001, latitude: lat0, accuracy: 8 });
  const banner = page.getByTestId('nav-banner');
  await expect(banner).toBeVisible({ timeout: 15_000 });
  await expect(banner).toContainText(/\d+ m/);
  if (process.env.SHOT_DIR) await page.screenshot({ path: process.env.SHOT_DIR + '/nav.png' });
  // drive onto the route start; the banner keeps guiding
  for (const [x, y] of [[200, 30], [200, 10], [190, 0], [170, 0], [150, 0]] as [number, number][]) {
    const [lon, lat] = ll(x, y);
    await context.setGeolocation({ longitude: lon, latitude: lat, accuracy: 6 });
    await page.waitForTimeout(1050);
  }
  await expect(banner).toBeVisible();
  await expect(page.getByTestId('where-chip')).toContainText('You are here');
});
