// Koncepty žijí jen v paměti této relace, nikdy v localStorage ani v URL.
const drafts = new Map<string, string>()
export const draftKey = (org: string, app: string, review: string) => JSON.stringify([org, app, review])
export const readDraft = (key: string) => drafts.get(key) ?? ''
export function writeDraft(key: string, body: string) {
  if (body) drafts.set(key, body)
  else drafts.delete(key)
}
export const hasDrafts = () => drafts.size > 0
export const clearDrafts = () => drafts.clear()
