import type { APIRoute } from 'astro';
import { site, indexable } from '../data/site';
export const GET: APIRoute = () =>
  new Response(
    indexable
      ? `User-agent: *\nAllow: /\nSitemap: ${site.origin}/sitemap.xml\n`
      : 'User-agent: *\nDisallow: /\n',
    { headers: { 'Content-Type': 'text/plain; charset=utf-8' } },
  );
