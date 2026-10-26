import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
const directory = 'docs/screenshots/integrations';
await mkdir(directory, { recursive: true });
const browser = await chromium.launch();
const results = [];
const errors = [];
try {
    const page = await browser.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => {
        if (message.type() === 'error') errors.push(message.text());
    });
    page.on('response', (response) => {
        if (response.url().startsWith('http://127.0.0.1:4327') && response.status() >= 400)
            errors.push(`${response.status()} ${response.url()}`);
    });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    for (const width of [1440, 1280, 768, 390, 360]) {
        await page.setViewportSize({
            width,
            height: width >= 1280 ? 1000 : width === 768 ? 1024 : 844,
        });
        await page.goto('http://127.0.0.1:4327/');
        await page.evaluate(() => document.fonts.ready);
        await page.screenshot({ path: `${directory}/hero-${width}.png` });
        await page
            .locator('#integrations')
            .screenshot({ path: `${directory}/coming-soon-${width}.png` });
        await page.getByRole('tab', { name: /Microsoft Teams/ }).click();
        await page.locator('#integrations').screenshot({ path: `${directory}/teams-${width}.png` });
        await page.getByRole('tab', { name: /Discord/ }).click();
        await page
            .locator('#integrations')
            .screenshot({ path: `${directory}/discord-${width}.png` });
        const metrics = await page.evaluate(() => ({
            width: innerWidth,
            scrollWidth: document.documentElement.scrollWidth,
            height: document.querySelector('#integrations').getBoundingClientRect().height,
        }));
        if (metrics.scrollWidth > metrics.width) throw new Error(`Overflow at ${width}`);
        results.push(metrics);
        await page.getByRole('tab', { name: /Slack/ }).click();
        await page.screenshot({ path: `${directory}/full-${width}.png`, fullPage: true });
    }
    const context = await browser.newContext({
        javaScriptEnabled: false,
        viewport: { width: 390, height: 844 },
    });
    const native = await context.newPage();
    await native.goto('http://127.0.0.1:4327/');
    await native.evaluate(() => document.fonts.ready);
    await native.locator('#integration-teams summary').click();
    await native.locator('#integrations').screenshot({ path: `${directory}/no-js-390.png` });
    await context.close();
    if (errors.length) throw new Error(errors.join('\n'));
    console.log(results);
    await writeFile(
        'docs/reports/integrations-visual.json',
        JSON.stringify(
            {
                checked: new Date().toISOString(),
                method: 'Chromium, local static production preview; reduced motion for captures',
                results,
                errors,
            },
            null,
            2,
        ),
    );
} finally {
    await browser.close();
}
