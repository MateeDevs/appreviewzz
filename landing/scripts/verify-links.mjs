import { chromium } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
const browser = await chromium.launch();
try {
    const page = await browser.newPage();
    await page.goto('http://127.0.0.1:4327/');
    const urls = await page
        .locator('a[href^="https://"]')
        .evaluateAll((links) => [...new Set(links.map((link) => link.href))]);
    const results = [];
    for (const url of urls) {
        const response = await page.goto(url, {
            waitUntil: 'domcontentloaded',
        });
        if (url.includes('console.appreviewzz.com')) await page.locator('form').waitFor();
        results.push({
            url,
            finalUrl: page.url(),
            status: response.status(),
            title: await page.title(),
            form: url.includes('console.appreviewzz.com')
                ? await page.locator('form').innerText()
                : undefined,
        });
    }
    await writeFile(
        'docs/reports/external-links.json',
        JSON.stringify(
            {
                checked: new Date().toISOString(),
                method: 'Chromium browser GET, no form submissions',
                results,
            },
            null,
            2,
        ),
    );
    console.log(results);
    if (results.some((result) => result.status >= 400)) process.exitCode = 1;
} finally {
    await browser.close();
}
