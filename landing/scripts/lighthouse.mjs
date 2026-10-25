import lighthouse from 'lighthouse';
import { chromium } from '@playwright/test';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';

// Measure an indexable production artifact, then restore safe noindex preview output.
const build = (production = false) =>
    new Promise((resolve, reject) => {
        const child = spawn('npm', ['run', production ? 'build:production' : 'build'], {
            stdio: 'inherit',
        });
        child.on('exit', (code) =>
            code === 0 ? resolve() : reject(new Error(`Build failed: ${code}`)),
        );
    });
await build(true);
await mkdir('docs/reports', { recursive: true });
const chrome = await chromium.launch({
    args: ['--remote-debugging-port=9227'],
});
try {
    const summaries = [];
    for (let run = 1; run <= 3; run++) {
        const result = await lighthouse(
            'http://127.0.0.1:4327/',
            {
                port: 9227,
                output: ['html', 'json'],
                onlyCategories: ['performance', 'accessibility', 'best-practices', 'seo'],
                logLevel: 'error',
            },
            {
                extends: 'lighthouse:default',
                settings: {
                    formFactor: 'mobile',
                    throttlingMethod: 'simulate',
                    screenEmulation: {
                        mobile: true,
                        width: 390,
                        height: 844,
                        deviceScaleFactor: 1,
                        disabled: false,
                    },
                },
            },
        );
        await writeFile(`docs/reports/lighthouse-mobile-${run}.html`, result.report[0]);
        await writeFile(`docs/reports/lighthouse-mobile-${run}.json`, result.report[1]);
        const lhr = result.lhr;
        const summary = {
            run,
            version: lhr.lighthouseVersion,
            userAgent: lhr.userAgent,
            fetchTime: lhr.fetchTime,
            categories: Object.fromEntries(
                Object.entries(lhr.categories).map(([key, value]) => [
                    key,
                    Math.round(value.score * 100),
                ]),
            ),
            lcp: lhr.audits['largest-contentful-paint'].numericValue,
            cls: lhr.audits['cumulative-layout-shift'].numericValue,
            fcp: lhr.audits['first-contentful-paint'].numericValue,
            tbt: lhr.audits['total-blocking-time'].numericValue,
            settings: lhr.configSettings,
        };
        summaries.push(summary);
        console.log(JSON.stringify(summary, null, 2));
    }
    await writeFile('docs/reports/lighthouse-summary.json', JSON.stringify(summaries, null, 2));
} finally {
    await chrome.close();
    await build(false);
}
