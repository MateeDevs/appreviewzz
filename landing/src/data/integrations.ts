import type { IconName } from './icons';

export type IntegrationStatus = 'available' | 'coming-soon';
export interface IntegrationMedia {
  src: string;
  alt: string;
  caption: string;
  width: number;
  height: number;
}
export interface Integration {
  id: string;
  name: string;
  icon: IconName;
  status: IntegrationStatus;
  description: string;
  directReply: boolean;
  media?: IntegrationMedia[];
}

// Public readiness is independent of implemented capabilities and screenshot availability.
// Change status only after the integration is ready for public use.
export const integrations: Integration[] = [
  {
    id: 'slack',
    name: 'Slack',
    icon: 'slack',
    status: 'coming-soon',
    description:
      'Bring new reviews into your channel. Edit the AI draft, approve your reply and send it back to the store, right from Slack.',
    directReply: true,
  },
  {
    id: 'teams',
    name: 'Microsoft Teams',
    icon: 'teams',
    status: 'coming-soon',
    description:
      'Keep the review and your response together in a Teams card. Refine the AI draft and approve it for the store without leaving the conversation.',
    directReply: true,
  },
  {
    id: 'discord',
    name: 'Discord',
    icon: 'discord',
    status: 'coming-soon',
    description:
      'A new place for your team to follow app reviews. Review delivery is planned; direct replies are not currently supported.',
    directReply: false,
  },
];

export const statusLabel = (status: IntegrationStatus) =>
  status === 'available' ? 'Available' : 'Coming soon';

export const integrationAvailability = integrations
  .map((integration) => `${integration.name}: ${statusLabel(integration.status)}`)
  .join(' · ');

export const replyIntegrations = integrations.filter((integration) => integration.directReply);
export const replyNames = replyIntegrations.map((integration) => integration.name).join(' and ');
export const replyAvailability = replyIntegrations
  .map((integration) => `${integration.name}: ${statusLabel(integration.status)}`)
  .join(' · ');
export const replyReadiness = replyIntegrations.every(
  (integration) => integration.status === 'available',
)
  ? 'Available for your team.'
  : 'See integration availability before connecting.';
