import { useEffect, useState } from 'react';
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
import { emptyItem, ItemForm, itemBody, type ItemFormValue } from '../components/ItemForm';
import { FetchToolbar, ServerErrors, ServerSelect, useServers } from '../components/ServerFilter';
import { WriteDialog } from '../components/WriteDialog';
import { api, errorText } from '../lib/api';
import { formatDateTime, hasRole, t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import { hostHref, itemHref, label, query, type ServerRef } from '../lib/zabbix';
import { flashNextPage, Shell, useFlash } from '../shell/Shell';

interface Item {
  itemId: number;
  hostId: number;
  host: string;
  hostName: string;
  name: string;
  key: string;
  type: number;
  valueType: number;
  delay: string;
  units: string;
  status: number;
  state: number;
  error: string;
  lastClock: string | null;
  lastValue: string | null;
}

interface ItemDetail {
  item: Item;
  history: string;
  trends: string;
  description: string;
  editable: boolean;
  tags: { tag: string; value: string }[];
}

/** Rows per request (the API maximum) and the most rows fetched for one list. */
const PAGE = 200;
const MAX_ROWS = 1000;

/** Float values (value type 0) are shown with at most 4 decimals, as in Zabbix. */
function formatValue(i: Item): string {
  if (i.valueType === 0 && i.lastValue !== null) {
    const n = Number(i.lastValue);
    if (Number.isFinite(n)) return n.toLocaleString(undefined, { maximumFractionDigits: 4 });
  }
  return i.lastValue ?? '';
}

function lastValue(i: Item): string {
  return i.lastValue === null ? '-' : `${formatValue(i)}${i.units ? ' ' + i.units : ''}`;
}

function statusText(i: Item): string {
  if (i.status !== 0) return t('item.status.1');
  return i.state === 1 ? t('item.state.1') : t('item.status.0');
}

function ItemStatus({ i }: { i: Item }) {
  if (i.status !== 0) return <StatusIndicator type="stopped">{t('item.status.1')}</StatusIndicator>;
  if (i.state === 1) return <StatusIndicator type="error">{t('item.state.1')}</StatusIndicator>;
  return <StatusIndicator type="success">{t('item.status.0')}</StatusIndicator>;
}

const writableFor = (servers: ServerRef[]) => servers.filter((s) => s.writable && (s.admin || hasRole('OPERATOR')));

// ---- dialogs shared by the list and the detail page ----

type Dialog =
  | { kind: 'create'; serverId: number | null; value: ItemFormValue }
  | { kind: 'edit'; serverId: number; itemId: number; expected: { key: string; name: string }; value: ItemFormValue }
  | { kind: 'delete'; serverId: number; item: Item };

function editDialog(serverId: number, d: ItemDetail): Dialog {
  return {
    kind: 'edit',
    serverId,
    itemId: d.item.itemId,
    expected: { key: d.item.key, name: d.item.name },
    value: {
      hostId: d.item.hostId,
      name: d.item.name,
      key: d.item.key,
      type: d.item.type,
      valueType: d.item.valueType,
      delay: d.item.delay,
      history: d.history,
      trends: d.trends,
      units: d.item.units,
      description: d.description,
      status: d.item.status,
      tags: d.tags.map((x) => ({ tag: x.tag, value: x.value })),
    },
  };
}

function ItemDialogs({ dialog, setDialog, writable, serversLoading, onDone }: {
  dialog: Dialog | null;
  setDialog: (d: Dialog | null) => void;
  writable: ServerRef[];
  serversLoading: boolean;
  onDone: (kind: Dialog['kind'], message: string) => void;
}) {
  if (!dialog) return null;
  if (dialog.kind === 'delete') {
    return (
      <WriteDialog
        title={t('item.deleteTitle')}
        formKey="delete"
        danger
        run={(preview) => api.del(`/api/zabbix/servers/${dialog.serverId}/items/${dialog.item.itemId}` + query({ expectedKey: dialog.item.key, preview }))}
        onClose={() => setDialog(null)}
        onDone={() => onDone('delete', t('item.deleted', { name: dialog.item.name }))}
      >
        <Box>{t('item.deleteConfirm', { name: dialog.item.name, host: dialog.item.hostName })}</Box>
      </WriteDialog>
    );
  }
  const form = dialog;
  return (
    <WriteDialog
      title={form.kind === 'create' ? t('item.create') : t('item.edit')}
      formKey={JSON.stringify(form)}
      run={(preview) =>
        form.kind === 'create'
          ? api.post(`/api/zabbix/servers/${form.serverId}/items`, { ...itemBody(form.value), preview })
          : api.put(`/api/zabbix/servers/${form.serverId}/items/${form.itemId}`, {
              ...itemBody(form.value),
              expectedKey: form.expected.key,
              expectedName: form.expected.name,
              preview,
            })
      }
      onClose={() => setDialog(null)}
      onDone={() => onDone(form.kind, t(form.kind === 'create' ? 'item.created' : 'item.updated', { name: form.value.name }))}
    >
      <SpaceBetween size="m">
        {form.kind === 'create' && (
          <FormField label={t('field.server')} description={t('item.writableServers')}>
            <ServerSelect servers={writable} loading={serversLoading} selected={form.serverId} onChange={(id) => setDialog({ ...form, serverId: id, value: { ...form.value, hostId: null } })} />
          </FormField>
        )}
        <ItemForm serverId={form.serverId} value={form.value} editing={form.kind === 'edit'} onChange={(v) => setDialog({ ...form, value: v })} />
      </SpaceBetween>
    </WriteDialog>
  );
}

// ---- detail page ----

function ItemDetailPage({ serverId, itemId }: { serverId: number; itemId: number }) {
  const { servers, loading: serversLoading } = useServers();
  const [d, setD] = useState<ItemDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [flash, push] = useFlash();
  const writable = writableFor(servers);
  const canWrite = writable.some((s) => s.serverId === serverId) && d?.editable === true;
  const server = servers.find((s) => s.serverId === serverId);

  const load = () =>
    api.get<ItemDetail>(`/api/zabbix/servers/${serverId}/items/${itemId}`)
      .then((r) => {
        setD(r);
        document.title = `${r.item.name} - ${t('nav.items')}`;
      })
      .catch((e) => setError(errorText(e)));
  useEffect(() => {
    void load();
  }, [serverId, itemId]);

  const i = d?.item;
  return (
    <Shell
      active="/items"
      flash={flash}
      breadcrumbs={[
        { text: t('nav.items'), href: `/items?server=${serverId}` },
        ...(i ? [{ text: i.hostName, href: `/items?server=${serverId}&host=${i.hostId}` }] : []),
        { text: i?.name ?? '...', href: `/items?server=${serverId}&id=${itemId}` },
      ]}
    >
      <SpaceBetween size="l">
        <Header
          variant="h1"
          description={i && <Box variant="code">{i.key}</Box>}
          actions={d && i && (
            <ActionsMenu
              items={canWrite ? [{ id: 'delete', text: t('action.delete') }] : []}
              onItemClick={() => setDialog({ kind: 'delete', serverId, item: i })}
              extra={<Button href={hostHref(serverId, i.hostId)}>{t('item.showHost')}</Button>}
              primary={canWrite && <Button variant="primary" onClick={() => setDialog(editDialog(serverId, d))}>{t('action.edit')}</Button>}
            />
          )}
        >
          {i?.name ?? t('common.loading')}
        </Header>
        {error && <Box color="text-status-error">{error}</Box>}
        {!d && !error && <Spinner size="large" />}
        {d && i && (
          <>
            {!d.editable && writable.some((s) => s.serverId === serverId) && <Box color="text-body-secondary">{t('item.notEditable')}</Box>}
            <Container header={<Header variant="h2">{t('detail.overview')}</Header>}>
              <KeyValuePairs
                columns={4}
                items={[
                  { label: t('field.host'), value: <Link href={hostHref(serverId, i.hostId)}>{i.hostName}</Link> },
                  { label: t('field.server'), value: server?.name ?? '-' },
                  { label: t('field.type'), value: label('itemType', i.type) },
                  { label: t('field.valueType'), value: label('valueType', i.valueType) },
                  { label: t('field.status'), value: <ItemStatus i={i} /> },
                  { label: t('field.interval'), value: i.delay || '-' },
                  { label: t('field.history'), value: d.history },
                  { label: t('field.trends'), value: d.trends || '-' },
                  { label: t('field.lastValue'), value: lastValue(i) },
                  { label: t('field.lastCheck'), value: formatDateTime(i.lastClock) },
                  { label: t('field.units'), value: i.units || '-' },
                  { label: t('dashboard.error'), value: i.error || '-' },
                  { label: t('field.description'), value: d.description || '-' },
                ]}
              />
            </Container>
            <Table
              header={<Header variant="h2" counter={`(${d.tags.length})`}>{t('field.tags')}</Header>}
              items={d.tags}
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
      <ItemDialogs
        dialog={dialog}
        setDialog={setDialog}
        writable={writable}
        serversLoading={serversLoading}
        onDone={(kind, message) => {
          setDialog(null);
          if (kind === 'delete') {
            flashNextPage('success', message);
            window.location.href = url(`/items?server=${serverId}&host=${i?.hostId ?? ''}`);
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

function ItemsPage() {
  const params = new URLSearchParams(window.location.search);
  const { servers, loading: serversLoading } = useServers();
  const [server, setServer] = useState<number | null>(params.get('server') ? Number(params.get('server')) : null);
  const hostId = params.get('host') ? Number(params.get('host')) : null;
  const [text, setText] = useState('');
  const [rows, setRows] = useState<Item[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [loading, setLoading] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [flash, push] = useFlash();
  const writable = writableFor(servers);
  const canWrite = server !== null && writable.some((s) => s.serverId === server);
  const serverName = servers.find((s) => s.serverId === server)?.name ?? '';

  useEffect(() => {
    if (server === null && servers.length > 0) setServer(servers[0].serverId);
  }, [servers]);

  /** Pages of the API until a short page or MAX_ROWS rows. */
  const search = async () => {
    if (server === null) return;
    setLoading(true);
    try {
      const list: Item[] = [];
      for (let offset = 0; offset < MAX_ROWS; offset += PAGE) {
        const page = await api.get<Item[]>(`/api/zabbix/servers/${server}/items` + query({ host: hostId, q: text, limit: PAGE, offset }));
        list.push(...page);
        if (page.length < PAGE) break;
      }
      setRows(list);
      setTruncated(list.length >= MAX_ROWS);
    } catch (e) {
      push('error', errorText(e));
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void search();
  }, [server]);

  const hostName = hostId ? rows[0]?.hostName : undefined;
  return (
    <Shell
      active="/items"
      flash={flash}
      contentType="table"
      breadcrumbs={[
        { text: t('nav.items'), href: server ? `/items?server=${server}` : '/items' },
        ...(hostId && server ? [{ text: hostName ?? String(hostId), href: `/items?server=${server}&host=${hostId}` }] : []),
      ]}
    >
      <SpaceBetween size="m">
        <ServerErrors failed={[]} truncated={truncated ? [serverName] : []} limit={MAX_ROWS} />
        <DataTable
          tableId="items"
          title={t('nav.items')}
          description={hostId ? t('item.hostFiltered', { host: hostName ?? hostId }) : t('item.pageHelp')}
          items={rows}
          loading={loading}
          trackBy="itemId"
          actions={canWrite && (
            <Button variant="primary" onClick={() => setDialog({ kind: 'create', serverId: server, value: emptyItem(hostId) })}>{t('item.create')}</Button>
          )}
          toolbar={
            <FetchToolbar servers={servers} serversLoading={serversLoading} text={text} onText={setText} placeholder={t('item.searchPlaceholder')} loading={loading} onFetch={() => void search()}>
              <ServerSelect servers={servers} loading={serversLoading} selected={server} onChange={(id) => { setServer(id); window.history.replaceState(null, '', `?server=${id}`); }} />
            </FetchToolbar>
          }
          empty={<Box textAlign="center" color="text-body-secondary">{server === null ? t('filter.chooseServer') : t('item.empty')}</Box>}
          defaultSort={{ column: 'host' }}
          columns={[
            { id: 'host', header: t('field.host'), cell: (i) => server !== null ? <Link href={hostHref(server, i.hostId)}>{i.hostName}</Link> : i.hostName, filterValue: (i) => i.hostName, enumerated: true },
            { id: 'name', header: t('field.name'), cell: (i) => server !== null ? <Link href={itemHref(server, i.itemId)}>{i.name}</Link> : i.name, filterValue: (i) => i.name, isRowHeader: true, width: 260 },
            { id: 'key', header: t('field.key'), cell: (i) => <Box variant="code">{i.key}</Box>, filterValue: (i) => i.key, width: 220 },
            { id: 'value', header: t('field.lastValue'), cell: lastValue, filterValue: lastValue, sortValue: (i) => (i.lastValue !== null && Number.isFinite(Number(i.lastValue)) ? Number(i.lastValue) : lastValue(i)) },
            { id: 'clock', header: t('field.lastCheck'), cell: (i) => formatDateTime(i.lastClock), sortValue: (i) => i.lastClock ?? '' },
            { id: 'type', header: t('field.type'), cell: (i) => label('itemType', i.type), filterValue: (i) => label('itemType', i.type), enumerated: true },
            { id: 'delay', width: 110, header: t('field.interval'), cell: (i) => i.delay, filterValue: (i) => i.delay, enumerated: true },
            { id: 'status', header: t('field.status'), cell: (i) => <span title={i.error}><ItemStatus i={i} /></span>, filterValue: statusText, enumerated: true },
          ]}
        />
      </SpaceBetween>
      <ItemDialogs
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
mount(detailId && params.get('server') ? <ItemDetailPage serverId={Number(params.get('server'))} itemId={Number(detailId)} /> : <ItemsPage />);
