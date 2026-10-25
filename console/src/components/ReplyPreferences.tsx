import { useState } from 'react'
import type { App } from '../api/types'
import { useUpdateApp } from '../api/hooks'
import { ErrorBox, Field } from './ui'
import { Select } from './Select'

export function AIInstructionsField({
  value,
  onChange,
}: {
  value: string
  onChange: (value: string) => void
}) {
  return (
    <Field
      label="Instrukce pro AI návrhy"
      hint="Tón odpovědí, jazyk, podpis a čemu se vyhnout. Prázdné pole ponechá výchozí chování. Návrhy před odesláním zkontrolujete."
    >
      <textarea
        value={value}
        rows={4}
        maxLength={10000}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Např. odpovídej vstřícně, stručně a bez slibů konkrétních termínů."
      />
    </Field>
  )
}
export function ReplyPreferences({
  org,
  app,
  onSaved,
  pending,
}: {
  org: string
  app: App
  onSaved: () => void
  pending: boolean
}) {
  const update = useUpdateApp(org)
  const [locale, setLocale] = useState(app.locale.toLowerCase())
  const [instructions, setInstructions] = useState(app.aiInstructions ?? '')
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (update.isPending || pending) return
        update.mutate(
          {
            id: app.id,
            body: { name: app.name, locale, aiInstructions: instructions },
          },
          { onSuccess: onSaved },
        )
      }}
    >
      <Field
        label="Jazyk přehledů"
        hint="Výchozí jazyk zpráv a nových týmových kanálů. Jazyk odpovědi na recenzi určuje její text a vaše AI instrukce."
      >
        <Select
          ariaLabel="Jazyk přehledů"
          value={locale}
          onChange={setLocale}
          options={[
            { value: 'cs', label: 'Čeština' },
            { value: 'en', label: 'English' },
          ]}
        />
      </Field>
      <AIInstructionsField value={instructions} onChange={setInstructions} />
      <p className="small muted">
        Automatické odesílání se tímto krokem nezapíná. Další nastavení najdete
        u aplikace.
      </p>
      <ErrorBox error={update.error} />
      <button type="submit" disabled={update.isPending || pending}>
        {update.isPending || pending
          ? 'Ukládám…'
          : 'Uložit preference a pokračovat'}
      </button>
    </form>
  )
}
