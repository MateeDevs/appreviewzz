import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

for (const width of [1440, 1280, 768, 390, 360]) {
  test(`layout, links and accessibility at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(message.text());
    });
    page.on('response', (response) => {
      if (response.url().startsWith('http://127.0.0.1:4327') && response.status() >= 400)
        errors.push(`${response.status()} ${response.url()}`);
    });
    await page.goto('/');
    await expect(page.locator('h1')).toHaveCount(1);
    await expect(page.locator('h1')).toContainText('App reviews.');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    const badAnchors = await page
      .locator('a[href^="#"]')
      .evaluateAll((links) =>
        links
          .filter((link) => !document.querySelector(link.getAttribute('href')!))
          .map((link) => link.outerHTML),
      );
    expect(badAnchors).toEqual([]);
    expect(await page.locator('a[href="#"]').count()).toBe(0);
    for (const section of [
      '#how-it-works',
      '#integrations',
      '#understand',
      '#faq',
      '.final-cta',
      '.site-footer',
    ]) {
      await page.locator(section).scrollIntoViewIfNeeded();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
    }
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa', 'best-practice'])
      .analyze();
    expect(
      results.violations.map((item) => ({
        id: item.id,
        nodes: item.nodes.map((node) => node.target),
      })),
    ).toEqual([]);
    expect(errors).toEqual([]);
  });
}

test('reply demo: editable draft, queued, publication, store error, retry and reset', async ({
  page,
}) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Simulate Microsoft Teams reply workflow' }).click();
  await expect(
    page.getByRole('button', { name: 'Simulate Microsoft Teams reply workflow' }),
  ).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#channel-context')).toHaveText('Simulated: Microsoft Teams');
  await page.getByRole('button', { name: 'Simulate Slack reply workflow' }).click();
  await page
    .locator('#reply-draft')
    .fill('Thanks for reporting the offline map issue. Our team is investigating.');
  await page.getByRole('button', { name: 'Approve reply', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('approved and queued');
  await expect(page.getByRole('status')).toContainText('not been published');
  await page.getByRole('button', { name: 'Simulate store error' }).click();
  await expect(page.getByRole('status')).toContainText('store rejected');
  await expect(page.locator('#reply-draft')).toBeFocused();
  await page.locator('#reply-draft').fill('Thanks. Please share your device and iOS version.');
  await page.getByRole('button', { name: 'Approve reply', exact: true }).click();
  await page.getByRole('button', { name: 'Simulate publication' }).click();
  await expect(page.getByRole('status')).toContainText('Simulation: published');
  await expect(page.getByRole('status')).toContainText('Please share your device');
  await expect(page.getByRole('status')).toContainText('Nothing was actually sent');
  await page.getByRole('button', { name: 'Start again' }).click();
  await expect(page.getByRole('status')).toBeEmpty();
  await page.locator('#reply-draft').fill('   ');
  await expect(page.getByRole('button', { name: 'Approve reply', exact: true })).toBeDisabled();
});

test('topics expose consistent underlying sample reviews', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: /Route planning/ }).click();
  await expect(page.locator('#source-title')).toHaveText('Behind “Route planning”');
  await expect(page.locator('#source-count')).toHaveText('2 of 8 sample reviews');
  await expect(page.locator('#source-list blockquote')).toHaveCount(2);
  await page.getByRole('button', { name: /Sign-in issues/ }).click();
  await expect(page.locator('#source-list')).toContainText('changing my password');
  await page.getByRole('button', { name: /Offline access/ }).click();
  await expect(page.locator('#source-list blockquote')).toHaveCount(3);
});

test('mobile menu and FAQ work with keyboard and Escape', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  const menu = page.locator('.mobile-menu summary');
  await menu.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('navigation', { name: 'Mobile navigation' })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(
    page
      .getByRole('navigation', { name: 'Mobile navigation' })
      .getByRole('link', { name: 'How it works' }),
  ).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu).toBeFocused();
  await expect(page.locator('.mobile-menu')).not.toHaveAttribute('open', '');
  await menu.press('Enter');
  await page
    .getByRole('navigation', { name: 'Mobile navigation' })
    .getByRole('link', { name: 'FAQ', exact: true })
    .click();
  await expect(page.locator('.mobile-menu')).not.toHaveAttribute('open', '');
  const faq = page.locator('.faq-list summary').first();
  await faq.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.faq-list details').first()).toHaveAttribute('open', '');
  await expect(page.locator('.faq-list details').first()).toContainText('off by default');
  await page.keyboard.press('Enter');
  await expect(page.locator('.faq-list details').first()).not.toHaveAttribute('open', '');
});

test('CTA destinations, metadata, assets and safe preview indexing', async ({ page, request }) => {
  await page.goto('/');
  const ctas = page.getByRole('link', { name: /Get started/ });
  expect(await ctas.count()).toBeGreaterThan(2);
  for (const cta of await ctas.all())
    await expect(cta).toHaveAttribute('href', 'https://console.appreviewzz.com/registrace');
  await expect(page.getByRole('link', { name: 'Log in ↗' }).first()).toHaveAttribute(
    'href',
    'https://console.appreviewzz.com/login',
  );
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    'href',
    'https://appreviewzz.com/',
  );
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', 'noindex, nofollow');
  for (const path of [
    '/og.png',
    '/favicon.svg',
    '/fonts/geist-variable.woff2',
    '/robots.txt',
    '/sitemap.xml',
  ])
    expect((await request.get(path)).status()).toBe(200);
  expect(await (await request.get('/robots.txt')).text()).toContain('Disallow: /');
});

test('no-JavaScript content and native disclosure fallback', async ({ browser }) => {
  const context = await browser.newContext({
    javaScriptEnabled: false,
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4327/');
  await expect(page.locator('h1')).toBeVisible();
  await expect(page.locator('#reply-draft')).toBeVisible();
  await expect(page.locator('#approve-demo')).not.toBeVisible();
  await expect(page.locator('#source-list blockquote')).toHaveCount(3);
  await page.locator('.mobile-menu summary').click();
  await expect(page.getByRole('navigation', { name: 'Mobile navigation' })).toBeVisible();
  await page.locator('.faq-list summary').first().click();
  await expect(page.locator('.faq-list details').first()).toHaveAttribute('open', '');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await context.close();
});

test('200% text zoom equivalent, reduced motion and visible keyboard focus', async ({ page }) => {
  await page.setViewportSize({ width: 720, height: 500 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).scrollBehavior)).toBe(
    'auto',
  );
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.keyboard.press('Enter');
  expect(await page.evaluate(() => location.hash)).toBe('#main');
  const draft = page.locator('#reply-draft');
  await draft.fill('A long response. '.repeat(50));
  await page.getByRole('button', { name: 'Approve reply', exact: true }).click();
  await page.getByRole('button', { name: 'Simulate publication' }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.keyboard.press('Tab');
  await draft.focus();
  const outline = await draft.evaluate((element) => ({
    style: getComputedStyle(element).outlineStyle,
    width: parseFloat(getComputedStyle(element).outlineWidth),
  }));
  expect(outline.style).toBe('solid');
  expect(outline.width).toBeGreaterThanOrEqual(3);
});

test('expanded FAQ, mobile navigation and queued demo remain accessible', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await page.locator('.mobile-menu summary').click();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.locator('.mobile-menu summary').press('Escape');
  for (const summary of await page.locator('.faq-list summary').all()) await summary.click();
  await page.locator('#approve-demo').click();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
        .analyze()
    ).violations,
  ).toEqual([]);
});
