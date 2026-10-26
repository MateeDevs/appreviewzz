import { fileURLToPath } from 'node:url';
import { resolve, relative } from 'node:path';
import sharp from 'sharp';
import { integrations } from '../src/data/integrations.ts';

export async function checkIntegrationAssets(items, publicDir) {
    const errors = [];
    const ids = new Set();
    for (const item of items) {
        if (!/^[a-z][a-z0-9-]*$/.test(item.id) || ids.has(item.id))
            errors.push(`Invalid or duplicate integration id: ${item.id}`);
        ids.add(item.id);
        for (const media of item.media ?? []) {
            const label = `${item.id}: ${media.src}`;
            if (!/^\/integrations\/[a-z0-9_./-]+\.(webp|png|jpe?g)$/i.test(media.src)) {
                errors.push(`${label}: use a local /integrations/ WebP, PNG or JPEG path`);
                continue;
            }
            const path = resolve(publicDir, `.${media.src}`);
            if (relative(resolve(publicDir, 'integrations'), path).startsWith('..')) {
                errors.push(`${label}: path escapes public/integrations`);
                continue;
            }
            if (!media.alt?.trim() || !media.caption?.trim())
                errors.push(`${label}: alt and caption are required`);
            try {
                const image = sharp(path);
                const metadata = await image.metadata();
                await image.stats(); // Decode too: a readable header alone is not enough.
                if (
                    !Number.isInteger(media.width) ||
                    !Number.isInteger(media.height) ||
                    media.width <= 0 ||
                    media.height <= 0 ||
                    metadata.width !== media.width ||
                    metadata.height !== media.height
                )
                    errors.push(
                        `${label}: configure actual image dimensions (${metadata.width} × ${metadata.height})`,
                    );
            } catch {
                errors.push(`${label}: missing or unreadable image`);
            }
        }
    }
    return errors;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const errors = await checkIntegrationAssets(
        integrations,
        fileURLToPath(new URL('../public/', import.meta.url)),
    );
    if (errors.length) {
        console.error(errors.join('\n'));
        process.exitCode = 1;
    } else
        console.log(
            `Integration assets OK (${integrations.length} integrations, ${integrations.reduce((sum, item) => sum + (item.media?.length ?? 0), 0)} screenshots).`,
        );
}
