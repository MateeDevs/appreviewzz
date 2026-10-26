import type { APIRoute } from 'astro';
import { site } from '../data/site';
export const GET: APIRoute = () =>
  new Response(
    `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>${site.origin}/</loc></url><url><loc>${site.origin}/cs/</loc></url></urlset>`,
    { headers: { 'Content-Type': 'application/xml; charset=utf-8' } },
  );
