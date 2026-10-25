// Real browser tab zoom via a temporary local extension; no page CSS zoom.
// References: playwright.dev/docs/chrome-extensions and Chrome tabs.setZoom.
import { chromium } from '@playwright/test';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
const extension = await mkdtemp(join(tmpdir(), 'appreviewzz-zoom-extension-'));
await writeFile(
    join(extension, 'manifest.json'),
    JSON.stringify({
        manifest_version: 3,
        name: 'Local landing zoom verification',
        version: '1.0',
        permissions: ['tabs'],
        background: { service_worker: 'worker.js' },
    }),
);
await writeFile(join(extension, 'worker.js'), 'chrome.runtime.onInstalled.addListener(() => {});');
let context;
try {
    context = await chromium.launchPersistentContext('', {
        channel: 'chromium',
        headless: true,
        viewport: { width: 1440, height: 1000 },
        args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
    });
    const worker = context.serviceWorkers()[0] ?? (await context.waitForEvent('serviceworker'));
    const page = await context.newPage();
    await page.goto('http://127.0.0.1:4327/');
    const zoom = await worker.evaluate(async () => {
        const tabs = await chrome.tabs.query({ url: 'http://127.0.0.1:4327/*' });
        await chrome.tabs.setZoom(tabs[0].id, 2);
        return chrome.tabs.getZoom(tabs[0].id);
    });
    await page.waitForFunction(() => devicePixelRatio === 2);
    await page.evaluate(() => document.fonts.ready);
    const metrics = await page.evaluate(() => ({
        width: innerWidth,
        dpr: devicePixelRatio,
        scroll: document.documentElement.scrollWidth,
    }));
    console.log({ zoom, ...metrics });
    await writeFile(
        'docs/reports/browser-zoom.json',
        JSON.stringify(
            { method: 'Chromium tabs.setZoom(2), temporary isolated extension', zoom, ...metrics },
            null,
            2,
        ),
    );
    if (metrics.width !== 720 || metrics.scroll > metrics.width)
        throw new Error('200% zoom reflow failed');
    // CDP's physical contentSize avoids Playwright's full-page clip at non-100% tab zoom.
    const cdp = await context.newCDPSession(page);
    const layout = await cdp.send('Page.getLayoutMetrics');
    console.log('zoom layout', JSON.stringify(layout));
    const shot = await cdp.send('Page.captureScreenshot', {
        format: 'png',
        captureBeyondViewport: true,
        clip: {
            x: 0,
            y: 0,
            width: layout.contentSize.width,
            height: layout.contentSize.height,
            scale: 1,
        },
    });
    await writeFile('docs/screenshots/browser-200-percent.png', Buffer.from(shot.data, 'base64'));
} finally {
    await context?.close();
    await rm(extension, { recursive: true, force: true });
}
