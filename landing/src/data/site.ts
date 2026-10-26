import { replyNames, replyAvailability } from './integrations';

// Preview/staging builds stay on staging; build:production sets the production origin.
const consoleOrigin = import.meta.env.CONSOLE_ORIGIN || 'https://console.staging.appreviewzz.com';

/** Public destinations only. Never put credentials in this file. */
export const site = {
  name: 'appreviewzz',
  origin: 'https://appreviewzz.com',
  register: new URL('/registrace', consoleOrigin).href,
  login: new URL('/login', consoleOrigin).href,
  contact: 'mailto:info@matee.cz?subject=Let%E2%80%99s%20talk%20about%20appreviewzz',
  email: 'info@matee.cz',
  maker: 'https://matee.cz/',
  title: 'appreviewzz — App reviews. Answered. Understood.',
  description: `Edit, approve and send app review replies back to the store from ${replyNames}. ${replyAvailability}. Understand the feedback behind your next release.`,
};
export const indexable = import.meta.env.INDEXABLE === 'true';
