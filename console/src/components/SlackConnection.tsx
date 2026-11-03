import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useLocation, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import { useConnectSlack, useConnectSlackChannel, useCredentials, useDeleteCredential, useSlackChannels } from '../api/hooks'
import type { App, Channel, Credential } from '../api/types'
import { ErrorBox, Field, Loading, Modal } from './ui'
import { Select } from './Select'

export function SlackChannelSetup({ org, app, connectedChannels }: {
  org: string
  app: App
  connectedChannels: Channel[]
}) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const credentials = useCredentials(org)
  const [workspace, setWorkspace] = useState('')
  const [target, setTarget] = useState('')
  const [search, setSearch] = useState('')
  const [manual, setManual] = useState(false)
  const [token, setToken] = useState('')
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<Credential | null>(null)
  const remove = useDeleteCredential(org)
  const installs = (credentials.data ?? []).filter((item) => item.type === 'SLACK_INSTALL')
  const returned = installs.find((item) => item.id === params.get('slackCredential'))
  const existing = connectedChannels.find((channel) => channel.type === 'SLACK' && channel.enabled)
  const credentialId = installs.find((item) => item.id === workspace)?.id || returned?.id || installs.find((item) => item.id === existing?.credentialId)?.id || installs[0]?.id || ''
  const selectedInstall = installs.find((item) => item.id === credentialId)
  const alreadyConnected = connectedChannels.some((channel) => channel.type === 'SLACK' && channel.enabled && channel.credentialId === credentialId)
  const conversations = useSlackChannels(org, credentialId, !manual)
  const channels = [...new Map((conversations.data?.pages.flatMap((page) => page.channels) ?? [])
    .map((channel) => [channel.id, channel])).values()].sort((a, b) => a.name.localeCompare(b.name))
  const connect = useConnectSlackChannel(org, app.id)
  const tokenConnect = useConnectSlack(org)
  const oauth = useMutation({
    mutationFn: () => {
      const query = new URLSearchParams(location.search)
      query.delete('slack')
      query.delete('slackCredential')
      if (location.pathname.includes('/aplikace/')) query.set('tab', 'klice')
      const returnTo = location.pathname + (query.size ? `?${query}` : '')
      return api.get<{ url: string }>(`/api/orgs/${org}/slack/install-url?${new URLSearchParams({ returnTo })}`)
    },
    onSuccess: ({ url }) => window.location.assign(url),
  })

  const changeWorkspace = (value: string) => {
    setWorkspace(value)
    setTarget('')
    setSearch('')
    connect.reset()
  }

  const workspaceConnection = selectedInstall ? (
    <div className="row">
      <p className="small">Workspace: <strong>{selectedInstall.hint ?? selectedInstall.label}</strong></p>
      <button type="button" className="danger" disabled={connect.isPending || remove.isPending} onClick={() => {
        remove.reset()
        setRemoving(selectedInstall)
      }}>Odpojit workspace</button>
    </div>
  ) : null

  const disconnectDialog = removing ? (
    <Modal title="Odpojit Slack workspace" onClose={() => setRemoving(null)}>
      <div className="stack">
        <p>Opravdu odpojit <strong>{removing.hint ?? removing.label}</strong> pro celou tuto organizaci?</p>
        <p className="small muted">Nejprve odpojte všechny kanály, které toto připojení používají, včetně kanálů ostatních aplikací v organizaci. Aplikace appreviewz zůstane ve Slacku nainstalovaná.</p>
        <ErrorBox error={remove.error} />
        <div className="row">
          <button type="button" className="danger" disabled={remove.isPending} onClick={() => {
            remove.mutate(removing.id, { onSuccess: () => {
              setRemoving(null)
              setWorkspace('')
              setTarget('')
              setSearch('')
              setAdding(false)
              connect.reset()
              oauth.reset()
              if (params.get('slackCredential') === removing.id) {
                setParams((query) => {
                  query.delete('slack')
                  query.delete('slackCredential')
                  return query
                }, { replace: true })
              }
            } })
          }}>{remove.isPending ? 'Odpojuji…' : 'Odpojit workspace'}</button>
          <button type="button" className="secondary" disabled={remove.isPending} onClick={() => setRemoving(null)}>Zrušit</button>
        </div>
      </div>
    </Modal>
  ) : null

  if (connect.isSuccess || (alreadyConnected && !adding)) return (
    <div className="stack optional-connection">
      {connect.isSuccess ? <p className="notice" role="status">Hotovo — kanál je připojený a zkušební zpráva dorazila. Nové recenze sem budou chodit automaticky.</p> : null}
      {workspaceConnection}
      <button type="button" className="link" onClick={() => {
        connect.reset()
        setManual(false)
        setAdding(true)
      }}>Připojit další kanál nebo workspace</button>
      {disconnectDialog}
    </div>
  )

  return (
    <div className="stack optional-connection">
      <h3>Slack</h3>
      {remove.isSuccess ? <p className="notice" role="status">Slack workspace byl odpojen. Můžete ho znovu připojit přes Slack.</p> : null}
      {params.get('slack') === 'cancelled' ? (
        <p className="notice" role="status">Připojení jste zrušili. Až budete chtít, můžete ho spustit znovu.</p>
      ) : null}
      {params.get('slack') === 'failed' ? (
        <p className="error" role="alert">Workspace se nepodařilo připojit. Zkuste připojení přes Slack znovu.</p>
      ) : null}
      {returned && params.get('slack') === 'connected' && !alreadyConnected ? (
        <p className="notice" role="status">Workspace je připojený. Teď vyberte kanál pro {app.name}.</p>
      ) : null}
      <ErrorBox error={credentials.error} />
      {credentials.isPending ? <Loading /> : null}
      {installs.length === 0 ? (
        <p className="small muted">Povolte Appreviewzz ve svém Slack workspace. Pak se vrátíte sem a vyberete kanál.</p>
      ) : (
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          if (!target.trim() || !credentialId || connect.isPending) return
          const selected = channels.find((channel) => channel.id === target)
          connect.mutate({ credentialId, targetRef: target.trim(), label: selected ? `#${selected.name}` : undefined }, {
            onSuccess: () => setTarget(''),
          })
        }}>
          {installs.length > 1 ? (
            <Field label="Workspace">
              <Select value={credentialId} options={installs.map((item) => ({ value: item.id, label: item.hint ?? item.label }))}
                onChange={changeWorkspace} ariaLabel="Slack workspace" disabled={connect.isPending} />
            </Field>
          ) : null}
          {workspaceConnection}
          {manual ? (
            <Field label="ID kanálu" hint="U privátního kanálu nejprve přidejte Appreviewzz přes nabídku Integrace v kanálu nebo /invite @appreviewz.">
              <input value={target} onChange={(event) => setTarget(event.target.value)} required placeholder="C… nebo G…" disabled={connect.isPending} />
            </Field>
          ) : (
            <>
              {channels.length > 10 ? (
                <Field label="Hledat kanál">
                  <input type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Například app-reviews" />
                </Field>
              ) : null}
              <Field label="Kanál pro recenze" hint={`Připojením odešlete do vybraného kanálu zkušební zprávu pro ${app.name}.`}>
                <Select value={target} options={channels.filter((channel) => channel.id === target || channel.name.toLowerCase().includes(search.toLowerCase().replace(/^#/, '')))
                  .map((channel) => ({ value: channel.id, label: `#${channel.name}` }))}
                  onChange={setTarget} ariaLabel="Kanál pro recenze" placeholder={conversations.isPending ? 'Načítám kanály…' : 'Vyberte kanál'}
                  disabled={conversations.isPending || connect.isPending || channels.length === 0} />
              </Field>
              <ErrorBox error={conversations.error} />
              {conversations.isError ? <button type="button" className="secondary" onClick={() => void conversations.refetch()}>Načíst kanály znovu</button> : null}
              {conversations.hasNextPage ? (
                <button type="button" className="link" disabled={conversations.isFetchingNextPage} onClick={() => void conversations.fetchNextPage()}>
                  {conversations.isFetchingNextPage ? 'Načítám…' : 'Načíst další kanály'}
                </button>
              ) : null}
              {conversations.isSuccess && channels.length === 0 ? <p className="small muted">Zatím tu nejsou žádné veřejné kanály. Vytvořte kanál ve Slacku a obnovte seznam, nebo použijte ID privátního kanálu.</p> : null}
            </>
          )}
          <ErrorBox error={connect.error} />
          <div className="row">
            <button type="submit" disabled={!target.trim() || !credentialId || connect.isPending}>
              {connect.isPending ? 'Připojuji a ověřuji…' : 'Připojit a ověřit kanál'}
            </button>
            <button type="button" className="link" disabled={connect.isPending} onClick={() => {
              setManual(!manual)
              setTarget('')
              connect.reset()
            }}>{manual ? 'Vybrat z veřejných kanálů' : 'Privátní kanál / zadat ID'}</button>
          </div>
        </form>
      )}
      <ErrorBox error={oauth.error} />
      <button type="button" className={installs.length ? 'secondary' : ''} disabled={oauth.isPending || credentials.isPending || connect.isPending}
        onClick={() => oauth.mutate()}>
        {oauth.isPending ? 'Otevírám Slack…' : installs.length ? 'Připojit jiný workspace / obnovit oprávnění' : 'Připojit přes Slack'}
      </button>
      <p className="small muted">Pokud váš Slack vyžaduje schválení aplikací, instalaci nejprve potvrdí správce workspace.</p>
      <details className="optional">
        <summary>Pokročilé: vlastní Slack bot token</summary>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          tokenConnect.mutate({ token }, { onSuccess: () => setToken('') })
        }}>
          <Field label="Bot token workspace" hint="Pro vlastní instalaci Slack aplikace. V běžném připojení stačí tlačítko výše.">
            <input type="password" autoComplete="off" value={token} onChange={(event) => setToken(event.target.value)} required placeholder="xoxb-…" />
          </Field>
          <ErrorBox error={tokenConnect.error} />
          <button type="submit" className="secondary" disabled={tokenConnect.isPending}>Připojit token</button>
        </form>
      </details>
      {disconnectDialog}
    </div>
  )
}
