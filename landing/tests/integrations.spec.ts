import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import sharp from 'sharp';
import { mkdir } from 'node:fs/promises';

const fixture = 'http://127.0.0.1:4328/';
test.beforeAll(async () => {
  const directory = new URL('./fixtures/dist/integrations/slack/', import.meta.url);
  await mkdir(directory, { recursive: true });
  const label = Buffer.from(
    '<svg width="960" height="600"><rect width="960" height="600" fill="#e1ece2"/><text x="80" y="120" font-size="40" fill="#172724">TEST ONLY · Synthetic review fixture</text><text x="80" y="220" font-size="28">Not a Slack or Teams screenshot</text></svg>',
  );
  await sharp(label).png().toFile(new URL('test-only.png', directory).pathname);
  await sharp(label).jpeg().toFile(new URL('test-only-reply.jpg', directory).pathname);
});

test('integration navigation and keyboard tabs preserve truthful availability', async ({
  page,
}) => {
  await page.goto('/');
  await page
    .getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: 'Integrations', exact: true })
    .click();
  await expect(page).toHaveURL(/#integrations$/);
  const slack = page.getByRole('tab', { name: /Slack/ });
  const teams = page.getByRole('tab', { name: /Microsoft Teams/ });
  await slack.focus();
  await expect(slack).toContainText('Coming soon');
  await page.keyboard.press('ArrowDown');
  await expect(teams).toBeFocused();
  await expect(teams).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('tabpanel')).toContainText('Microsoft Teams is coming soon');
  await expect(page.getByRole('tabpanel')).toContainText('Direct replies · planned');
  await page.keyboard.press('End');
  await expect(page.getByRole('tab', { name: /Discord/ })).toBeFocused();
  await expect(page.getByRole('tabpanel')).toContainText('No direct replies yet');
  await expect(page.getByRole('tabpanel')).not.toContainText('Direct replies · planned');
  await page.keyboard.press('ArrowDown');
  await expect(slack).toBeFocused();
  await page.keyboard.press('Home');
  await expect(slack).toHaveAttribute('aria-selected', 'true');
  expect(
    parseFloat(await slack.evaluate((element) => getComputedStyle(element).outlineWidth)),
  ).toBeGreaterThanOrEqual(3);
  expect((await new AxeBuilder({ page }).include('#integrations').analyze()).violations).toEqual(
    [],
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('.mobile-menu summary').click();
  await page
    .getByRole('navigation', { name: 'Mobile navigation' })
    .getByRole('link', { name: 'Integrations', exact: true })
    .click();
  await expect(page.locator('.mobile-menu')).not.toHaveAttribute('open', '');
  await expect(page.getByRole('tabpanel')).toBeVisible();
});

test('availability and media are independent; gallery and dialog work', async ({ page }) => {
  await page.goto(fixture);
  const panel = page.getByRole('tabpanel');
  await panel.scrollIntoViewIfNeeded();
  await expect(page.getByRole('tab', { name: /Available image/ })).toContainText('Available');
  await expect(panel.locator('img').first()).toBeVisible();
  await expect(panel.locator('.integration-media').first()).toHaveClass(/media-ready/);
  await expect(panel).not.toContainText('Direct replies · planned');
  await page.getByRole('button', { name: /2. TEST ONLY: reply result/ }).click();
  await expect(panel.locator('[data-gallery-image="1"]')).toBeVisible();
  await expect(panel.locator('[data-gallery-image="0"]')).toBeHidden();
  const enlarge = page.getByRole('button', { name: /Enlarge Available image.*reply result/ });
  await enlarge.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Close' })).toBeFocused();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.keyboard.press('Tab');
  await expect(page.getByRole('region', { name: 'Full-size screenshot' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Close' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(enlarge).toBeFocused();
  await enlarge.click();
  await page.getByRole('button', { name: 'Close' }).click();
  await expect(enlarge).toBeFocused();
  await page.getByRole('tab', { name: /Available empty/ }).click();
  await expect(panel).toHaveAttribute('data-availability', 'available');
  await expect(panel).toContainText('Preview coming soon');
  await expect(panel).toContainText('The integration is available.');
  await page.getByRole('tab', { name: /Planned image/ }).click();
  await expect(page.getByRole('tab', { name: /Planned image/ })).toContainText('Coming soon');
  await expect(panel).toHaveAttribute('data-availability', 'coming-soon');
  await expect(panel).toContainText('Planned integration preview');
  await expect(panel.locator('.integration-media')).toHaveClass(/media-ready/);
  await expect(panel.getByRole('img')).toBeVisible();
  await page.getByRole('tab', { name: /Planned empty/ }).click();
  await expect(panel).toContainText('Planned empty is coming soon');
  await expect(panel.locator('img')).toHaveCount(0);
});

test('missing or undecodable image falls back without changing availability or frame size', async ({
  page,
}) => {
  await page.route('**/intentionally-missing.png', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: 'This is not an image' }),
  );
  await page.goto(fixture);
  for (const [name, status] of [
    ['Broken image', 'available'],
    ['Planned broken', 'coming-soon'],
  ]) {
    await page.getByRole('tab', { name: new RegExp(name) }).click();
    const panel = page.getByRole('tabpanel');
    const media = panel.locator('.integration-media');
    await media.scrollIntoViewIfNeeded();
    const before = await media.boundingBox();
    await expect(panel.getByText('Preview unavailable', { exact: true })).toBeVisible();
    await expect(panel.locator('img')).toBeHidden();
    await expect(panel).toHaveAttribute('data-availability', status);
    await expect(panel.locator('[data-enlarge]')).toBeHidden();
    const after = await media.boundingBox();
    expect(after?.height).toBeCloseTo(before!.height, 0);
    expect(after!.width / after!.height).toBeCloseTo(960 / 600, 1);
  }
});

for (const width of [1440, 1280, 768, 390, 360]) {
  test(`media and fallback fit at ${width}px and remain accessible`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto(fixture);
    for (const name of [
      'Available image',
      'Available empty',
      'Planned image',
      'Planned empty',
      'Broken image',
      'Planned broken',
    ]) {
      await page.getByRole('tab', { name: new RegExp(name) }).click();
      await page.getByRole('tabpanel').scrollIntoViewIfNeeded();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      expect(
        (
          await new AxeBuilder({ page })
            .include('#integrations')
            .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
            .analyze()
        ).violations,
      ).toEqual([]);
    }
  });
}

test('no JavaScript: native service disclosures and real media stay usable', async ({
  browser,
}) => {
  const context = await browser.newContext({
    javaScriptEnabled: false,
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4327/');
  await expect(page.locator('.integration-sidebar')).toBeHidden();
  for (const id of ['slack', 'teams', 'discord']) {
    const panel = page.locator(`#integration-${id}`);
    if ((await panel.getAttribute('open')) !== '') await panel.locator('summary').click();
    await expect(panel.locator('.integration-empty')).toBeVisible();
    await expect(panel).toContainText('coming soon');
  }
  await page.goto(fixture);
  await expect(page.locator('#integration-available-image img').first()).toBeVisible();
  await expect(page.locator('#integration-available-image img').nth(1)).toBeVisible();
  await expect(page.locator('[data-enlarge]').first()).toBeHidden();
  await expect(
    page.getByRole('link', { name: /Open original screenshot/ }).first(),
  ).toHaveAttribute('href', '/integrations/slack/test-only.png');
  await page.locator('#integration-available-empty summary').click();
  await expect(page.getByText('Preview coming soon', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await context.close();
});
