import type { APIRoute } from 'astro';

export const GET: APIRoute = () =>
  new Response(
    JSON.stringify({
      gitSha: process.env.CF_PAGES_COMMIT_SHA || process.env.GIT_SHA || 'unknown',
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );
