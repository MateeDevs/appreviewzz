import { replyNames, replyAvailability } from './integrations';

/** Public destinations only. Never put credentials in this file. */
export const site = {
  name: 'appreviewzz',
  origin: 'https://appreviewzz.com',
  register: 'https://console.appreviewzz.com/registrace',
  login: 'https://console.appreviewzz.com/login',
  contact: 'mailto:info@matee.cz?subject=Let%E2%80%99s%20talk%20about%20appreviewzz',
  email: 'info@matee.cz',
  maker: 'https://matee.cz/',
  title: 'appreviewzz — App reviews. Answered. Understood.',
  description: `Edit, approve and send app review replies back to the store from ${replyNames}. ${replyAvailability}. Understand the feedback behind your next release.`,
};
export const indexable = import.meta.env.INDEXABLE === 'true';
