import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

for (const locale of ['cs-CZ', 'cs', 'en-US', 'de-DE']) {
  test(`browser language ${locale} selects the expected landing language`, async ({ browser }) => {
    const context = await browser.newContext({ locale });
    const page = await context.newPage();
    await page.goto('http://127.0.0.1:4327/?source=test#integrations');
    const czech = locale.startsWith('cs');
    await expect(page.locator('html')).toHaveAttribute('lang', czech ? 'cs' : 'en');
    await expect(page).toHaveURL(
      `http://127.0.0.1:4327/${czech ? 'cs/' : ''}?source=test#integrations`,
    );
    await expect(page.locator('h1')).toContainText(czech ? 'Recenze aplikací.' : 'App reviews.');
    await context.close();
  });
}

test('manual language selection overrides the system and survives reload and a new visit', async ({
  browser,
}) => {
  const context = await browser.newContext({ locale: 'cs-CZ' });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4327/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'cs');
  await page.getByRole('link', { name: 'English', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.goto('http://127.0.0.1:4327/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.getByRole('link', { name: 'Čeština', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'cs');
  await page.goto('http://127.0.0.1:4327/');
  await expect(page).toHaveURL('http://127.0.0.1:4327/cs/');
  await context.close();
});

test('saved Czech preference overrides an English system and switching preserves the current section', async ({
  page,
}) => {
  await page.addInitScript(() => {
    if (!localStorage.getItem('appreviewzz.language'))
      localStorage.setItem('appreviewzz.language', 'cs');
  });
  await page.goto('/?source=test');
  await expect(page.locator('html')).toHaveAttribute('lang', 'cs');
  await page.locator('.desktop-nav a[href="#understand"]').click();
  await page.getByRole('link', { name: 'English', exact: true }).click();
  await expect(page).toHaveURL('http://127.0.0.1:4327/?source=test#understand');
});

test('unrecognized stored language falls back to the browser preference', async ({ browser }) => {
  const context = await browser.newContext({ locale: 'cs-CZ' });
  await context.addInitScript(() => localStorage.setItem('appreviewzz.language', 'fr'));
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4327/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'cs');
  await context.close();
});

test('blocked local storage still allows automatic detection and manual navigation', async ({
  browser,
}) => {
  const context = await browser.newContext({ locale: 'cs-CZ' });
  await context.addInitScript(() => {
    Object.defineProperty(window, 'localStorage', {
      get() {
        throw new DOMException('Storage unavailable', 'SecurityError');
      },
    });
  });
  const page = await context.newPage();
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('http://127.0.0.1:4327/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'cs');
  await page.getByRole('link', { name: 'English', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page).toHaveURL('http://127.0.0.1:4327/?lang=en');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  expect(errors).toEqual([]);
  await context.close();
});

test('Czech reply and analysis demos use Czech for interactive states and reset', async ({
  page,
}) => {
  await page.goto('/cs/');
  await page.getByRole('button', { name: 'Simulovat odpověď přes Microsoft Teams' }).click();
  await expect(page.locator('#channel-context')).toHaveText('Simulace: Microsoft Teams');
  await page.locator('#reply-draft').fill('Děkujeme za zpětnou vazbu.');
  await page.getByRole('button', { name: 'Schválit odpověď', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('schválená a ve frontě');
  await page.getByRole('button', { name: 'Simulovat chybu obchodu' }).click();
  await expect(page.getByRole('status')).toContainText('obchod odpověď odmítl');
  await page.getByRole('button', { name: 'Schválit odpověď', exact: true }).click();
  await page.getByRole('button', { name: 'Simulovat zveřejnění' }).click();
  await expect(page.getByRole('status')).toContainText('Děkujeme za zpětnou vazbu.');
  await expect(page.getByRole('status')).toContainText('Ve skutečnosti se nic neodeslalo.');
  await page.getByRole('button', { name: 'Začít znovu' }).click();
  await expect(page.locator('#reply-draft')).toHaveValue(/Děkujeme za upozornění/);
  await page.getByRole('button', { name: /Plánování tras/ }).click();
  await expect(page.locator('#source-title')).toHaveText('Recenze k tématu „Plánování tras“');
  await expect(page.locator('#source-count')).toHaveText('Ukázkové recenze: 2 z 8');
  await expect(page.locator('#source-list')).toContainText('víkendové trasy');
  await page.getByRole('button', { name: /Problémy s přihlášením/ }).click();
  await expect(page.locator('#source-list')).toContainText('Po změně hesla');
  await page.getByRole('tab', { name: /Discord/ }).click();
  await expect(page.getByRole('tabpanel')).toContainText('Integraci Discord připravujeme');
});

for (const width of [1440, 1280, 1024, 768, 390, 360]) {
  test(`Czech layout and accessibility at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/cs/');
    await expect(page.getByRole('link', { name: 'English', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Čeština', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );
    if (width <= 1000) {
      await page.locator('.mobile-menu summary').click();
      await expect(page.getByRole('navigation', { name: 'Mobilní navigace' })).toContainText(
        'Jak to funguje',
      );
      await page.locator('.mobile-menu summary').press('Escape');
    }
    for (const section of [
      '.site-header',
      '#how-it-works',
      '#integrations',
      '#understand',
      '#faq',
      '.final-cta',
    ]) {
      await page.locator(section).scrollIntoViewIfNeeded();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
    }
    expect(
      (
        await new AxeBuilder({ page })
          .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
          .analyze()
      ).violations,
    ).toEqual([]);
  });
}

test('Czech static content, language links and FAQ work without JavaScript', async ({
  browser,
}) => {
  const context = await browser.newContext({
    javaScriptEnabled: false,
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4327/cs/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'cs');
  await expect(page.locator('#reply-draft')).toHaveValue(/Děkujeme/);
  await page.locator('.faq-list summary').first().click();
  await expect(page.locator('.faq-list details').first()).toContainText(
    've výchozím stavu vypnutá',
  );
  await page.getByRole('link', { name: 'English', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.getByRole('link', { name: 'Čeština', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'cs');
  await context.close();
});

test('both languages have localized metadata, canonical links and sitemap entries', async ({
  page,
  request,
}) => {
  await page.goto('/cs/');
  await expect(page).toHaveTitle('appreviewzz — Recenze aplikací. Zodpovězené. Pochopené.');
  await expect(page.locator('meta[name="description"]')).toHaveAttribute(
    'content',
    /Upravujte, schvalujte/,
  );
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    'href',
    'https://appreviewzz.com/cs/',
  );
  await expect(page.locator('meta[property="og:locale"]')).toHaveAttribute('content', 'cs_CZ');
  for (const [language, path] of [
    ['en', '/'],
    ['cs', '/cs/'],
    ['x-default', '/'],
  ]) {
    await expect(page.locator(`link[rel="alternate"][hreflang="${language}"]`)).toHaveAttribute(
      'href',
      `https://appreviewzz.com${path}`,
    );
  }
  const structuredData = JSON.parse(
    (await page.locator('script[type="application/ld+json"]').textContent()) || '{}',
  );
  expect(structuredData.description).toContain('Upravujte, schvalujte');
  expect(structuredData.url).toBe('https://appreviewzz.com/cs/');
  const sitemap = await (await request.get('/sitemap.xml')).text();
  expect(sitemap).toContain('<loc>https://appreviewzz.com/</loc>');
  expect(sitemap).toContain('<loc>https://appreviewzz.com/cs/</loc>');
});
