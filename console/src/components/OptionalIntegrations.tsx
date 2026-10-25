import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { useCreateChannel, useCredentials, useInvite } from '../api/hooks'
import type { App } from '../api/types'
import { ErrorBox, Field } from './ui'
import { Select } from './Select'

export function SlackOAuth({ org }: { org: string }) {
  const client = useQueryClient()
  const install = useMutation({
    mutationFn: () =>
      api.get<{ url: string }>(`/api/orgs/${org}/slack/install-url`),
  })
  return (
    <div className="stack optional-connection">
      <h3>Připojit Slack</h3>
      <p className="small muted">
        Povolíte přístup aplikaci appreviewzz ve svém workspace. Potom vyberete
        kanál. Do dokončení výběru se doručování nepotvrzuje.
      </p>
      <ErrorBox error={install.error} />
      {install.data ? (
        <a
          className="button"
          href={install.data.url}
          target="_blank"
          rel="noreferrer"
        >
          Pokračovat ve Slacku ↗
        </a>
      ) : null}
      <div className="row">
        <button
          type="button"
          className="secondary"
          disabled={install.isPending}
          onClick={() => install.mutate()}
        >
          {install.isPending
            ? 'Připravuji…'
            : install.data
              ? 'Vygenerovat nový odkaz'
              : 'Připojit přes Slack'}
        </button>
        <button
          type="button"
          className="link"
          onClick={() =>
            void client.invalidateQueries({ queryKey: ['credentials', org] })
          }
        >
          Už jsem workspace připojil/a
        </button>
      </div>
    </div>
  )
}

export function TeamsConnection({ org, app }: { org: string; app: App }) {
  const client = useQueryClient()
  const credentials = useCredentials(org)
  const create = useCreateChannel(org, app.id)
  const [tenant, setTenant] = useState('')
  const [target, setTarget] = useState('')
  const connect = useMutation({
    mutationFn: () =>
      api.post<{ botReachable: boolean; botError?: string }>(
        `/api/orgs/${org}/teams/connect`,
        { tenantId: tenant },
      ),
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['credentials', org] }),
  })
  const installs =
    credentials.data?.filter((c) => c.type === 'TEAMS_BOT_REF') ?? []
  const [selected, setSelected] = useState('')
  return (
    <div className="stack optional-connection">
      <h3>Microsoft Teams</h3>
      <p className="small muted">
        Bot appreviewzz musí být povolený správcem vašeho Teams. Instalace
        serveru musí mít nakonfigurovanou registraci bota.
      </p>
      {installs.length === 0 ? (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (!connect.isPending) connect.mutate()
          }}
        >
          <Field
            label="Microsoft Entra tenant ID"
            hint="UUID z Microsoft Entra / Teams admin centra."
          >
            <input
              value={tenant}
              onChange={(e) => setTenant(e.target.value)}
              required
            />
          </Field>
          <ErrorBox error={connect.error} />
          {connect.data && !connect.data.botReachable ? (
            <p className="error" role="alert">
              {connect.data.botError ?? 'Bot zatím není dostupný.'}
            </p>
          ) : null}
          <button
            type="submit"
            className="secondary"
            disabled={connect.isPending}
          >
            {connect.isPending ? 'Připojuji…' : 'Připojit Teams tenant'}
          </button>
        </form>
      ) : (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (!create.isPending)
              create.mutate(
                {
                  type: 'TEAMS',
                  targetRef: target,
                  credentialId: selected || installs[0]!.id,
                  locale: app.locale.toLowerCase(),
                },
                { onSuccess: () => setTarget('') },
              )
          }}
        >
          <Field label="Teams tenant">
            <Select
              ariaLabel="Teams tenant"
              value={selected || installs[0]!.id}
              onChange={setSelected}
              options={installs.map((c) => ({
                value: c.id,
                label: c.hint ?? c.label,
              }))}
            />
          </Field>
          <Field
            label="Teams conversation ID"
            hint="Identifikátor konverzace kanálu, ve kterém je bot přidaný."
          >
            <input
              value={target}
              onChange={(e) => setTarget(e.target.value)}
              required
            />
          </Field>
          <ErrorBox error={create.error} />
          {create.isSuccess ? (
            <p role="status" className="notice">
              Kanál uložený. Doručení ověřte zkušební zprávou.
            </p>
          ) : null}
          <button
            type="submit"
            className="secondary"
            disabled={create.isPending}
          >
            Přidat Teams kanál
          </button>
        </form>
      )}
    </div>
  )
}

export function InviteColleague({ org }: { org: string }) {
  const invite = useInvite(org)
  const [email, setEmail] = useState('')
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (!invite.isPending)
          invite.mutate(
            { email, role: 'MEMBER' },
            { onSuccess: () => setEmail('') },
          )
      }}
    >
      <Field
        label="E-mail kolegy"
        hint="Člen může číst recenze a odpovídat. Další role upravíte v Organizaci."
      >
        <input
          type="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
      </Field>
      <ErrorBox error={invite.error} />
      {invite.isSuccess ? (
        <p className="notice" role="status">
          {invite.data.delivered === false
            ? 'Pozvánka je uložená, ale e-mail se nepodařilo odeslat. Zkontrolujte ji v Organizaci.'
            : 'Pozvánka byla odeslána.'}
        </p>
      ) : null}
      <button type="submit" className="secondary" disabled={invite.isPending}>
        {invite.isPending ? 'Odesílám…' : 'Pozvat kolegu'}
      </button>
    </form>
  )
}
