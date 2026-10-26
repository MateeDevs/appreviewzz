import { translator, type Locale } from './index';
import {
  integrations,
  replyIntegrations,
  replyReadiness,
  statusLabel,
  type Integration,
} from '../data/integrations';

/** Localized copy is derived from the same readiness and capability configuration. */
export function getIntegrationCopy(locale: Locale) {
  const t = translator(locale);
  const availability = (items: Integration[]) =>
    items.map((item) => `${item.name}: ${t(statusLabel(item.status))}`).join(' · ');
  return {
    replyNames: replyIntegrations.map((item) => item.name).join(locale === 'cs' ? ' a ' : ' and '),
    replyAvailability: availability(replyIntegrations),
    integrationAvailability: availability(integrations),
    replyReadiness: t(replyReadiness),
  };
}
