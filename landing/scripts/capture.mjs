import { chromium } from '@playwright/test';
import { readFile, mkdir } from 'node:fs/promises';
const base = 'http://127.0.0.1:4327';
const browser = await chromium.launch();
await mkdir('docs/screenshots', { recursive: true });
await mkdir('docs/variants', { recursive: true });
const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
});
await page.goto(base);
await page.evaluate(() => document.fonts.ready);
await page.screenshot({ path: 'docs/variants/hero-a.png' });
await page.addStyleTag({
    content: await readFile('docs/variants/hero-b.css', 'utf8'),
});
await page.screenshot({ path: 'docs/variants/hero-b.png' });
for (const [name, width, height] of [
    ['desktop', 1440, 1000],
    ['laptop', 1280, 900],
    ['tablet', 768, 1024],
    ['mobile', 390, 844],
    ['mobile-small', 360, 800],
]) {
    await page.setViewportSize({ width, height });
    await page.goto(base);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: `docs/screenshots/${name}-hero.png` });
    await page.screenshot({
        path: `docs/screenshots/${name}-full.png`,
        fullPage: true,
    });
    console.log(
        name,
        await page.evaluate(() => ({
            width: innerWidth,
            bodyWidth: document.documentElement.scrollWidth,
            height: document.documentElement.scrollHeight,
        })),
    );
}
// 200% browser zoom reflow: 1440 physical pixels / 2 = 720 CSS pixels.
await page.setViewportSize({ width: 720, height: 500 });
await page.goto(base);
await page.screenshot({
    path: 'docs/screenshots/desktop-200-percent.png',
    fullPage: true,
});
console.log(
    '200% zoom reflow equivalent',
    await page.evaluate(() => ({
        client: document.documentElement.clientWidth,
        scroll: document.documentElement.scrollWidth,
        inner: innerWidth,
    })),
);
await page.setViewportSize({ width: 390, height: 844 });
await page.goto(base);
await page.locator('.mobile-menu summary').click();
await page.screenshot({ path: 'docs/screenshots/mobile-menu.png' });
await page.locator('.mobile-menu summary').press('Escape');
await page.locator('#approve-demo').click();
await page.screenshot({ path: 'docs/screenshots/mobile-demo-queued.png' });
await browser.close();
