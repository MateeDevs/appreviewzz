import { chromium } from '@playwright/test';
const browser = await chromium.launch();
const page = await browser.newPage({
    viewport: { width: 1200, height: 630 },
    deviceScaleFactor: 1,
});
await page.goto('http://127.0.0.1:4327/');
await page.setContent(`<html><head><style>
@font-face{font-family:Geist;src:url('http://127.0.0.1:4327/fonts/geist-variable.woff2')}*{box-sizing:border-box}body{margin:0;background:#f5f4f0;color:#172724;font-family:Geist,Arial,sans-serif;padding:53px 65px}header{display:flex;align-items:center;gap:9px;font-size:27px;font-weight:650;letter-spacing:-1px}h1{font-size:88px;line-height:1.05;font-weight:520;letter-spacing:-5px;margin:42px 0 30px}h1 span{color:#146c60}p{font-size:20px;color:#57685b;line-height:1.5}aside{position:absolute;right:40px;top:90px;color:#146c60}footer{position:absolute;bottom:45px;left:65px;border-top:1px solid #c6d3bf;padding-top:19px;width:1070px;display:flex;justify-content:space-between;font-size:15px;color:#5e725a}
</style></head><body><header><svg width="38" height="38" viewBox="0 0 40 40" fill="none" stroke="#146c60" stroke-width="3" stroke-linecap="round"><path d="M8 16v8m9-14v20m9-16v12"/><circle cx="32" cy="9" r="2.5" fill="#146c60" stroke="none"/></svg> appreviewzz</header><h1>App reviews.<br/>Answered.<br/><span>Understood.</span></h1><aside><svg width="440" height="440" viewBox="0 0 500 500" fill="none"><g stroke="#c7d7c0"><circle cx="250" cy="250" r="100"/><circle cx="250" cy="250" r="150"/><circle cx="250" cy="250" r="200"/><circle cx="250" cy="250" r="245"/></g><path d="M30 250h130l25-35 30 75 35-140 35 200 30-100h155" stroke="#146c60" stroke-width="3"/></svg></aside><footer><span>App Store & Google Play → Slack & Microsoft Teams</span><span>appreviewzz.com</span></footer></body></html>`);
await page.evaluate(() => document.fonts.ready);
await page.screenshot({ path: 'public/og.png' });
await browser.close();
