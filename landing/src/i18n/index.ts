import cs from './cs.json';

export type Locale = 'en' | 'cs';
export const localePath = (locale: Locale) => (locale === 'cs' ? '/cs/' : '/');
export const pageLocale = (): Locale => (document.documentElement.lang === 'cs' ? 'cs' : 'en');

/** English source strings are the fallback for new content and unsupported languages. */
export function translator(locale: Locale) {
  return (source: string, values: Record<string, string | number> = {}) => {
    const translated =
      locale === 'cs' ? ((cs as Record<string, string>)[source] ?? source) : source;
    return translated.replace(/\{([^}]+)\}/g, (match, key: string) =>
      Object.hasOwn(values, key) ? String(values[key]) : match,
    );
  };
}
