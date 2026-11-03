#!/usr/bin/env node
// Jedna Slack App pro obě prostředí se stálým produkčním příjmem interakcí.
const staging = process.argv[3] ?? 'https://console.staging.appreviewzz.com'
const production = process.argv[4] ?? 'https://console.appreviewzz.com'
const ingress = process.argv[2] ?? process.env.SLACK_INGRESS_BASE_URL ?? production
function origin(value) {
  const url = new URL(value)
  if (url.protocol !== 'https:' || url.port || url.username || url.password ||
      url.search || url.hash || url.pathname !== '/') throw new Error('invalid origin')
  return url.origin
}
let bases
try {
  bases = { ingress: origin(ingress), staging: origin(staging), production: origin(production) }
} catch {
  process.stderr.write('Použití: node deploy/slack/render-manifest.mjs [HTTPS ingress] [HTTPS staging] [HTTPS produkce]\nVýchozí ingress je https://console.appreviewzz.com. Adresy musí být HTTPS origin bez cesty a nestandardního portu.\n')
  process.exit(1)
}
process.stdout.write(JSON.stringify({
  display_information: {
    name: 'appreviewz',
    description: 'App reviews and reply approval in your Slack channel.',
    background_color: '#172724',
  },
  features: { bot_user: { display_name: 'appreviewz', always_online: false } },
  oauth_config: {
    redirect_urls: [...new Set([bases.staging, bases.production])].map((base) => `${base}/slack/oauth/callback`),
    scopes: { bot: ['chat:write', 'chat:write.public', 'channels:read'] },
  },
  settings: {
    interactivity: { is_enabled: true, request_url: `${bases.ingress}/webhooks/slack/interactivity` },
    org_deploy_enabled: false,
    socket_mode_enabled: false,
    token_rotation_enabled: false,
  },
}, null, 2) + '\n')
