import { useEffect, useState } from 'react';
import Badge from '@cloudscape-design/components/badge';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Container from '@cloudscape-design/components/container';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import KeyValuePairs from '@cloudscape-design/components/key-value-pairs';
import Link from '@cloudscape-design/components/link';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Spinner from '@cloudscape-design/components/spinner';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Table from '@cloudscape-design/components/table';

import { ActionsMenu, DataTable } from '../components/DataTable';
import { emptyHost, HostForm, hostBody, type HostFormValue } from '../components/HostForm';
import { FetchToolbar, ServerErrors, ServerSelect, useServers } from '../components/ServerFilter';
import { TemplateLinkDialog } from '../components/TemplateLinkDialog';
import { WriteDialog } from '../components/WriteDialog';
import { api, errorText } from '../lib/api';
import { hasRole, t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import { failedServers, flatten, hostHref, label, query, truncatedServers, type MultiResult, type ServerRef, type WithServer } from '../lib/zabbix';
import { flashNextPage, Shell, useFlash } from '../shell/Shell';

interface HostInterface {
  type: number;
  main: boolean;
  address: string;
  useIp: boolean;
  ip: string;
  dns: string;
  port: string;
  available: number;
  error: string;
}

interface Host {
  hostId: number;
  host: string;
  name: string;
  status: number;
  maintenance: boolean;
  proxy: string | null;
  groups: string[];
  groupIds: number[];
  interfaces: HostInterface[];
}

interface HostDetail {
  host: Host;
  description: string;
  tags: { tag: string; value: string }[];
  templates: string[];
  itemCount: number;
  triggerCount: number;
  problemCount: number;
}

const LIMIT = 200;

function mainInterface(h: Host): HostInterface | undefined {
  return h.interfaces.find((i) => i.main) ?? h.interfaces[0];
}

function Availability({ host }: { host: Host }) {
  const main = mainInterface(host);
  if (!main) return <>-</>;
  const type = main.available === 1 ? 'success' : main.available === 2 ? 'error' : 'pending';
  return <StatusIndicator type={type}>{label('available', main.available)}</StatusIndicator>;
}

function HostStatus({ host }: { host: Host }) {
  return (
    <SpaceBetween direction="horizontal" size="xxs">
      {host.status === 0 ? <StatusIndicator type="success">{t('host.status.0')}</StatusIndicator> : <StatusIndicator type="stopped">{t('host.status.1')}</StatusIndicator>}
      {host.maintenance && <Badge color="blue">{t('host.maintenance')}</Badge>}
    </SpaceBetween>
  );
}

const writableFor = (servers: ServerRef[]) => servers.filter((s) => s.writable && (s.admin || hasRole('OPERATOR')));

// ---- dialogs shared by the list and the detail page ----

type Dialog =
  | { kind: 'create'; serverId: number | null; value: HostFormValue }
  | { kind: 'edit'; serverId: number; hostId: number; expected: { host: string; name: string }; value: HostFormValue }
  | { kind: 'delete'; serverId: number; serverName: string; host: Host }
  | { kind: 'templates'; serverId: number; host: Host };

function editDialog(serverId: number, d: HostDetail): Dialog {
  const main = d.host.interfaces.find((i) => i.main && i.type === 1);
  return {
    kind: 'edit',
    serverId,
    hostId: d.host.hostId,
    expected: { host: d.host.host, name: d.host.name },
    value: {
      host: d.host.host,
      name: d.host.name,
      description: d.description,
      status: d.host.status,
      groupIds: d.host.groupIds,
      tags: d.tags.map((x) => ({ tag: x.tag, value: x.value })),
      useIp: main ? main.useIp : true,
      ip: main?.ip ?? '',
      dns: main?.dns ?? '',
      port: main?.port ?? '10050',
    },
  };
}

function HostDialogs({ dialog, setDialog, writable, serversLoading, onDone }: {
  dialog: Dialog | null;
  setDialog: (d: Dialog | null) => void;
  writable: ServerRef[];
  serversLoading: boolean;
  onDone: (kind: Dialog['kind'], message: string) => void;
}) {
  if (!dialog) return null;
  if (dialog.kind === 'templates') {
    return (
      <TemplateLinkDialog
        serverId={dialog.serverId}
        hostId={dialog.host.hostId}
        host={dialog.host.host}
        onClose={() => setDialog(null)}
        onDone={(_, mode, name) =>
          onDone('templates', t(mode === 'link' ? 'template.linked' : mode === 'unlink' ? 'template.unlinked' : 'template.cleared', { name, host: dialog.host.name }))
        }
      />
    );
  }
  if (dialog.kind === 'delete') {
    return (
      <WriteDialog
        title={t('host.deleteTitle')}
        formKey="delete"
        danger
        run={(preview) => api.del(`/api/zabbix/servers/${dialog.serverId}/hosts/${dialog.host.hostId}` + query({ expectedHost: dialog.host.host, preview }))}
        onClose={() => setDialog(null)}
        onDone={() => onDone('delete', t('host.deleted', { name: dialog.host.name }))}
      >
        <Box>{t('host.deleteConfirm', { name: dialog.host.name, server: dialog.serverName })}</Box>
      </WriteDialog>
    );
  }
  const form = dialog;
  return (
    <WriteDialog
      title={form.kind === 'create' ? t('host.create') : t('host.edit')}
      formKey={JSON.stringify(form)}
      run={(preview) =>
        form.kind === 'create'
          ? api.post(`/api/zabbix/servers/${form.serverId}/hosts`, { ...hostBody(form.value), preview })
          : api.put(`/api/zabbix/servers/${form.serverId}/hosts/${form.hostId}`, {
              ...hostBody(form.value),
              expectedHost: form.expected.host,
              expectedName: form.expected.name,
              preview,
            })
      }
      onClose={() => setDialog(null)}
      onDone={() => onDone(form.kind, t(form.kind === 'create' ? 'host.created' : 'host.updated', { name: form.value.name || form.value.host }))}
    >
      <SpaceBetween size="m">
        {form.kind === 'create' && (
          <FormField label={t('field.server')} description={t('host.writableServers')}>
            <ServerSelect servers={writable} loading={serversLoading} selected={form.serverId} onChange={(id) => setDialog({ ...form, serverId: id, value: { ...form.value, groupIds: [] } })} />
          </FormField>
        )}
        <HostForm serverId={form.serverId} value={form.value} editing={form.kind === 'edit'} onChange={(v) => setDialog({ ...form, value: v })} />
      </SpaceBetween>
    </WriteDialog>
  );
}

// ---- detail page ----

function HostDetailPage({ serverId, hostId }: { serverId: number; hostId: number }) {
  const { servers, loading: serversLoading } = useServers();
  const [detail, setDetail] = useState<HostDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [flash, push] = useFlash();
  const writable = writableFor(servers);
  const canWrite = writable.some((s) => s.serverId === serverId);
  const server = servers.find((s) => s.serverId === serverId);

  const load = () =>
    api.get<HostDetail>(`/api/zabbix/servers/${serverId}/hosts/${hostId}`)
      .then((d) => {
        setDetail(d);
        document.title = `${d.host.name} - ${t('nav.hosts')}`;
      })
      .catch((e) => setError(errorText(e)));
  useEffect(() => {
    void load();
  }, [serverId, hostId]);

  const h = detail?.host;
  return (
    <Shell active="/hosts" flash={flash} breadcrumbs={[{ text: t('nav.hosts'), href: '/hosts' }, { text: h?.name ?? '...', href: `/hosts?server=${serverId}&id=${hostId}` }]}>
      <SpaceBetween size="l">
        <Header
          variant="h1"
          description={server ? `${server.name} (Zabbix ${server.zabbixVersion})` : undefined}
          actions={detail && h && (
            <ActionsMenu
              items={canWrite ? [
                { id: 'templates', text: t('template.button') },
                { id: 'delete', text: t('action.delete') },
              ] : []}
              onItemClick={(id) => setDialog(id === 'templates' ? { kind: 'templates', serverId, host: h } : { kind: 'delete', serverId, serverName: server?.name ?? '', host: h })}
              extra={<><Button href={url(`/latest?server=${serverId}&host=${hostId}`)}>{t('host.showLatest')}</Button><Button href={url(`/items?server=${serverId}&host=${hostId}`)}>{t('host.showItems')}</Button></>}
              primary={canWrite && <Button variant="primary" onClick={() => setDialog(editDialog(serverId, detail))}>{t('action.edit')}</Button>}
            />
          )}
        >
          {h?.name ?? t('common.loading')}
        </Header>
        {error && <Box color="text-status-error">{error}</Box>}
        {!detail && !error && <Spinner size="large" />}
        {detail && h && (
          <>
            <Container header={<Header variant="h2">{t('detail.overview')}</Header>}>
              <KeyValuePairs
                columns={4}
                items={[
                  { label: t('field.hostName'), value: h.host },
                  { label: t('field.status'), value: <HostStatus host={h} /> },
                  { label: t('field.availability'), value: <Availability host={h} /> },
                  { label: t('field.proxy'), value: h.proxy ?? '-' },
                  { label: t('field.hostGroups'), value: h.groups.join(', ') || '-' },
                  { label: t('field.templates'), value: detail.templates.join(', ') || '-' },
                  { label: t('field.items'), value: <Link href={url(`/items?server=${serverId}&host=${hostId}`)}>{detail.itemCount}</Link> },
                  { label: t('field.triggers'), value: detail.triggerCount },
                  { label: t('nav.problems'), value: detail.problemCount },
                  { label: t('field.description'), value: detail.description || '-' },
                ]}
              />
            </Container>
            <Table
              header={<Header variant="h2" counter={`(${h.interfaces.length})`}>{t('field.interfaces')}</Header>}
              items={h.interfaces}
              wrapLines
              empty={<Box textAlign="center" color="text-body-secondary">-</Box>}
              columnDefinitions={[
                { id: 'type', header: t('field.type'), cell: (i) => label('interface', i.type) + (i.main ? ' *' : '') },
                { id: 'addr', header: t('field.address'), cell: (i) => `${i.address}:${i.port}` },
                { id: 'avail', header: t('field.availability'), cell: (i) => <StatusIndicator type={i.available === 1 ? 'success' : i.available === 2 ? 'error' : 'pending'}>{label('available', i.available)}</StatusIndicator> },
                { id: 'error', header: t('dashboard.error'), cell: (i) => i.error || '-' },
              ]}
            />
            <Table
              header={<Header variant="h2" counter={`(${detail.tags.length})`}>{t('field.tags')}</Header>}
              items={detail.tags}
              wrapLines
              empty={<Box textAlign="center" color="text-body-secondary">{t('host.noTags')}</Box>}
              columnDefinitions={[
                { id: 'tag', header: t('field.tag'), cell: (x) => x.tag },
                { id: 'value', header: t('field.value'), cell: (x) => x.value },
              ]}
            />
          </>
        )}
      </SpaceBetween>
      <HostDialogs
        dialog={dialog}
        setDialog={setDialog}
        writable={writable}
        serversLoading={serversLoading}
        onDone={(kind, message) => {
          setDialog(null);
          if (kind === 'delete') {
            flashNextPage('success', message);
            window.location.href = url('/hosts');
            return;
          }
          push('success', message);
          void load();
        }}
      />
    </Shell>
  );
}

// ---- list page ----

function HostsPage() {
  const { servers, loading: serversLoading } = useServers();
  // ?group=&groupServer= : hosts of one host group (link of the host group list)
  const [group, setGroup] = useState(() => {
    const p = new URLSearchParams(window.location.search);
    return p.get('group') && p.get('groupServer') ? { groupId: Number(p.get('group')), serverId: Number(p.get('groupServer')), name: p.get('groupName') ?? p.get('group')! } : null;
  });
  const [selected, setSelected] = useState<number[]>(group ? [group.serverId] : []);
  const [text, setText] = useState('');
  const [rows, setRows] = useState<WithServer<Host>[]>([]);
  const [failed, setFailed] = useState<{ serverName: string; error: string }[]>([]);
  const [truncated, setTruncated] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [flash, push] = useFlash();
  const writable = writableFor(servers);

  const search = async () => {
    setLoading(true);
    try {
      const r = await api.get<MultiResult<Host>>('/api/zabbix/hosts' + query({ q: text, group: group?.groupId, limit: LIMIT }, group ? [group.serverId] : selected));
      const list = flatten(r);
      setRows(list);
      setFailed(failedServers(r));
      setTruncated(truncatedServers(r, LIMIT));
    } catch (e) {
      push('error', errorText(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void search();
  }, [group]);


  return (
    <Shell active="/hosts" flash={flash} contentType="table" breadcrumbs={[{ text: t('nav.hosts'), href: '/hosts' }]}>
      <SpaceBetween size="m">
        <ServerErrors failed={failed} truncated={truncated} limit={LIMIT} />
        <DataTable
          tableId="hosts"
          title={t('nav.hosts')}
          description={group ? (
            <SpaceBetween direction="horizontal" size="xs">
              <span>{t('host.groupFiltered', { group: group.name })}</span>
              <Link onFollow={() => { setGroup(null); window.history.replaceState(null, '', url('/hosts')); }}>{t('table.clearFilter')}</Link>
            </SpaceBetween>
          ) : t('host.pageHelp')}
          items={rows}
          loading={loading}
          trackBy={(h) => `${h.serverId}:${h.hostId}`}
          actions={writable.length > 0 && (
            <Button variant="primary" onClick={() => setDialog({ kind: 'create', serverId: writable[0].serverId, value: emptyHost() })}>{t('host.create')}</Button>
          )}
          toolbar={
            <FetchToolbar servers={servers} serversLoading={serversLoading} selected={selected} onSelected={setSelected}
              text={text} onText={setText} placeholder={t('host.searchPlaceholder')} loading={loading} onFetch={() => void search()} />
          }
          empty={<Box textAlign="center" color="text-body-secondary">{t('host.empty')}</Box>}
          defaultSort={{ column: 'name' }}
          columns={[
            { id: 'name', header: t('field.name'), cell: (h) => <Link href={hostHref(h.serverId, h.hostId)}>{h.name}</Link>, filterValue: (h) => h.name, isRowHeader: true, width: 240 },
            { id: 'host', header: t('field.hostName'), cell: (h) => h.host, filterValue: (h) => h.host, hidden: true },
            { id: 'server', header: t('field.server'), cell: (h) => h.serverName, filterValue: (h) => h.serverName, enumerated: true },
            { id: 'address', header: t('field.address'), cell: (h) => mainInterface(h)?.address ?? '-', filterValue: (h) => mainInterface(h)?.address ?? '' },
            { id: 'avail', header: t('field.availability'), cell: (h) => <Availability host={h} />, filterValue: (h) => { const m = mainInterface(h); return m ? label('available', m.available) : ''; }, enumerated: true },
            { id: 'status', header: t('field.status'), cell: (h) => <HostStatus host={h} />, filterValue: (h) => t(`host.status.${h.status}`) + (h.maintenance ? ` ${t('host.maintenance')}` : ''), enumerated: true },
            { id: 'groups', header: t('field.hostGroups'), cell: (h) => h.groups.join(', '), filterValue: (h) => h.groups.join(', ') },
            { id: 'proxy', header: t('field.proxy'), cell: (h) => h.proxy ?? '', filterValue: (h) => h.proxy ?? '', enumerated: true },
          ]}
        />
      </SpaceBetween>
      <HostDialogs
        dialog={dialog}
        setDialog={setDialog}
        writable={writable}
        serversLoading={serversLoading}
        onDone={(_, message) => {
          setDialog(null);
          push('success', message);
          void search();
        }}
      />
    </Shell>
  );
}

const params = new URLSearchParams(window.location.search);
const detailId = params.get('id');
mount(detailId && params.get('server') ? <HostDetailPage serverId={Number(params.get('server'))} hostId={Number(detailId)} /> : <HostsPage />);
