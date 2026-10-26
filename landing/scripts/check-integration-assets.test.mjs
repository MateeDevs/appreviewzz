import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import sharp from 'sharp';
import { checkIntegrationAssets } from './check-integration-assets.mjs';

test('asset guard checks formats, missing/corrupt images, paths, dimensions and descriptions', async () => {
    const root = await mkdtemp(join(tmpdir(), 'landing-integration-assets-'));
    try {
        await mkdir(join(root, 'integrations/slack'), { recursive: true });
        const base = { id: 'slack', media: [] };
        for (const format of ['png', 'webp', 'jpeg']) {
            await sharp({ create: { width: 96, height: 60, channels: 3, background: '#eeeeee' } })
                .toFormat(format)
                .toFile(join(root, `integrations/slack/review.${format}`));
            const media = {
                src: `/integrations/slack/review.${format}`,
                width: 96,
                height: 60,
                alt: 'Test image',
                caption: 'Test only',
            };
            assert.deepEqual(await checkIntegrationAssets([{ ...base, media: [media] }], root), []);
        }
        await writeFile(join(root, 'integrations/slack/corrupt.png'), 'not an image');
        const media = {
            src: '/integrations/slack/review.png',
            width: 96,
            height: 60,
            alt: 'Test image',
            caption: 'Test only',
        };
        for (const broken of [
            { ...media, src: '/integrations/slack/missing.png' },
            { ...media, src: '/integrations/slack/corrupt.png' },
            { ...media, src: '/integrations/../../secret.png' },
            { ...media, src: 'https://example.com/image.png' },
            { ...media, width: 100 },
            { ...media, alt: '' },
            { ...media, caption: '' },
        ])
            assert.ok(
                (await checkIntegrationAssets([{ ...base, media: [broken] }], root)).length > 0,
            );
        assert.ok(
            (await checkIntegrationAssets([base, base], root)).some((error) =>
                error.includes('duplicate'),
            ),
        );
    } finally {
        await rm(root, { recursive: true, force: true });
    }
});
