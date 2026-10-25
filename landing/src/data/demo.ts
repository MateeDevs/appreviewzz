/** Entirely synthetic, consistent sample data. No customer or production data. */
export const demo = {
  app: 'Atlas',
  version: '3.8',
  period: '12–18 Oct 2026',
  review: {
    title: 'My saved maps won’t open offline',
    text: 'I love planning trips with Atlas, but since the latest update my saved maps won’t load without a connection. Please bring offline maps back.',
    platform: 'App Store',
    stars: 2,
    date: '18 Oct',
  },
  draft:
    'Thanks for letting us know about offline maps in 3.8. We’re looking into this with our team. Could you share your device and iOS version so we can investigate?',
  topics: [
    {
      id: 'offline',
      label: 'Offline access',
      count: 12,
      previous: 3,
      direction: 'Growing',
      reviews: [
        {
          stars: 2,
          platform: 'App Store',
          date: '18 Oct',
          text: 'I love planning trips with Atlas, but since the latest update my saved maps won’t load without a connection. Please bring offline maps back.',
        },
        {
          stars: 1,
          platform: 'Google Play',
          date: '17 Oct',
          text: 'Downloaded a map before my hike. It disappeared as soon as I lost reception.',
        },
        {
          stars: 2,
          platform: 'App Store',
          date: '16 Oct',
          text: 'Version 3.8 keeps asking me to connect when I open a saved route.',
        },
      ],
    },
    {
      id: 'planning',
      label: 'Route planning',
      count: 8,
      previous: 7,
      direction: 'Stable',
      reviews: [
        {
          stars: 5,
          platform: 'Google Play',
          date: '18 Oct',
          text: 'Planning a weekend route is so much easier now. Love the new waypoints.',
        },
        {
          stars: 4,
          platform: 'App Store',
          date: '15 Oct',
          text: 'Great route planner. I’d like to rearrange stops by dragging them.',
        },
      ],
    },
    {
      id: 'login',
      label: 'Sign-in issues',
      count: 2,
      previous: 6,
      direction: 'Falling',
      reviews: [
        {
          stars: 2,
          platform: 'Google Play',
          date: '14 Oct',
          text: 'Still having trouble signing in after changing my password.',
        },
        {
          stars: 3,
          platform: 'App Store',
          date: '13 Oct',
          text: 'Sign-in works again after the update, but I had to reset my password.',
        },
      ],
    },
  ],
};
