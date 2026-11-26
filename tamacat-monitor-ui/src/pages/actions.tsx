import { useEffect, useState } from 'react';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Container from '@cloudscape-design/components/container';
import Header from '@cloudscape-design/components/header';
import KeyValuePairs from '@cloudscape-design/components/key-value-pairs';
import Link from '@cloudscape-design/components/link';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Spinner from '@cloudscape-design/components/spinner';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Table from '@cloudscape-design/components/table';

import { ActionForm, emptyAction, type ActionFormValue } from '../components/ActionForm';
import { ActionsMenu, DataTable } from '../components/DataTable';
import { FetchToolbar, ServerSelect, useServers } from '../components/ServerFilter';
import { WriteDialog } from '../components/WriteDialog';
import { api, errorText } from '../lib/api';
import { t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import { actionHref, label, query, type ServerRef } from '../lib/zabbix';
import { flashNextPage, Shell, useFlash, type Flash } from '../shell/Shell';

interface Action {
  actionId: number;
  name: string;
  eventSource: number;
  status: number;
  escPeriod: string;
  conditionCount: number;
  operationCount: number;
}

interface ActionDetail {
  action: Action;
  evalType: number;
  formula: string;
  conditions: { conditionType: number; operator: number; value: string; value2: string; valueName: string | null }[];
  operations: {
    operationType: number;
    recovery: number;
    stepFrom: number;
    stepTo: number;
    escPeriod: string;
    mediaType: string | null;
    userGroups: string[];
    users: string[];
    subject: string | null;
  }[];
}

function conditionValue(c: ActionDetail['conditions'][number]): string {
  if (c.valueName) return c.valueName;
  if (c.conditionType === 4) return label('severity', c.value);
  return c.value2 ? `${c.value2}: ${c.value}` : c.value;
}

function ActionStatus({ a }: { a: Action }) {
  return a.status === 0 ? <StatusIndicator type="success">{t('status.enabled')}</StatusIndicator> : <StatusIndicator type="stopped">{t('status.disabled')}</StatusIndicator>;
}

// ---- dialogs shared by the list and the detail page ----

type Dialog =
  | { kind: 'create'; value: ActionFormValue }
  | { kind: 'edit'; actionId: number; expectedName: string; value: ActionFormValue }
  | { kind: 'delete'; action: Action };

/** The edit form of an action, or null after showing why it cannot be edited here. */
async function editDialog(serverId: number, actionId: number, push: (type: Flash['type'], content: string) => void): Promise<Dialog | null> {
  try {
    const r = await api.get<{ input: ActionFormValue; reason: string | null }>(`/api/zabbix/servers/${serverId}/actions/${actionId}/edit`);
    if (r.reason) {
      push('info', t(r.reason));
      return null;
    }
    return { kind: 'edit', actionId, expectedName: r.input.name, value: r.input };
  } catch (e) {
    push('error', errorText(e));
    return null;
  }
}

function ActionDialogs({ serverId, server, dialog, setDialog, onDone }: {
  serverId: number;
  server: ServerRef | undefined;
  dialog: Dialog | null;
  setDialog: (d: Dialog | null) => void;
  onDone: (kind: Dialog['kind'], message: string) => void;
}) {
  if (!dialog) return null;
  if (dialog.kind === 'delete') {
    return (
      <WriteDialog
        title={t('actionForm.deleteTitle')}
        formKey="delete"
        danger
        run={(preview) => api.del(`/api/zabbix/servers/${serverId}/actions/${dialog.action.actionId}` + query({ expectedName: dialog.action.name, preview }))}
        onClose={() => setDialog(null)}
        onDone={() => onDone('delete', t('actionForm.deleted', { name: dialog.action.name }))}
      >
        <Box>{t('actionForm.deleteConfirm', { name: dialog.action.name })}</Box>
      </WriteDialog>
    );
  }
  const form = dialog;
  return (
    <WriteDialog
      title={form.kind === 'create' ? t('actionForm.create') : t('actionForm.edit')}
      formKey={JSON.stringify(form)}
      run={(preview) =>
        form.kind === 'create'
          ? api.post(`/api/zabbix/servers/${serverId}/actions`, { ...form.value, preview })
          : api.put(`/api/zabbix/servers/${serverId}/actions/${form.actionId}`, { ...form.value, expectedName: form.expectedName, preview })
      }
      onClose={() => setDialog(null)}
      onDone={() => onDone(form.kind, t(form.kind === 'create' ? 'actionForm.created' : 'actionForm.updated', { name: form.value.name }))}
    >
      <ActionForm serverId={serverId} value={form.value} v70={server?.zabbixVersion !== '6.0'} onChange={(v) => setDialog({ ...form, value: v })} />
    </WriteDialog>
  );
}

// ---- detail page ----

function ActionDetailPage({ serverId, actionId }: { serverId: number; actionId: number }) {
  const { servers } = useServers((s) => s.admin);
  const [d, setD] = useState<ActionDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [flash, push] = useFlash();
  const server = servers.find((s) => s.serverId === serverId);
  const canWrite = server?.writable === true && d?.action.eventSource === 0;

  const load = () =>
    api.get<ActionDetail>(`/api/zabbix/servers/${serverId}/actions/${actionId}`)
      .then((r) => {
        setD(r);
        document.title = `${r.action.name} - ${t('nav.actions')}`;
      })
      .catch((e) => setError(errorText(e)));
  useEffect(() => {
    void load();
  }, [serverId, actionId]);

  const a = d?.action;
  return (
    <Shell active="/actions" flash={flash} breadcrumbs={[{ text: t('nav.actions'), href: `/actions?server=${serverId}` }, { text: a?.name ?? '...', href: `/actions?server=${serverId}&id=${actionId}` }]}>
      <SpaceBetween size="l">
        <Header
          variant="h1"
          description={server ? `${server.name} (Zabbix ${server.zabbixVersion})` : undefined}
          actions={a && canWrite && (
            <ActionsMenu
              items={[{ id: 'delete', text: t('action.delete') }]}
              onItemClick={() => setDialog({ kind: 'delete', action: a })}
              primary={<Button variant="primary" onClick={async () => setDialog(await editDialog(serverId, actionId, push))}>{t('action.edit')}</Button>}
            />
          )}
        >
          {a?.name ?? t('common.loading')}
        </Header>
        {error && <Box color="text-status-error">{error}</Box>}
        {!d && !error && <Spinner size="large" />}
        {d && a && (
          <>
            <Container header={<Header variant="h2">{t('detail.overview')}</Header>}>
              <KeyValuePairs
                columns={4}
                items={[
                  { label: t('field.eventSource'), value: label('eventSource', a.eventSource) },
                  { label: t('field.status'), value: <ActionStatus a={a} /> },
                  { label: t('field.calculation'), value: d.evalType === 3 ? d.formula : label('evalType', d.evalType) },
                  { label: t('field.escPeriod'), value: a.escPeriod },
                ]}
              />
            </Container>
            <Table
              header={<Header variant="h2" counter={`(${d.conditions.length})`}>{t('action.conditions')}</Header>}
              items={d.conditions}
              wrapLines
              empty={<Box textAlign="center" color="text-body-secondary">-</Box>}
              columnDefinitions={[
                { id: 'type', header: t('field.type'), cell: (c) => label('conditionType', c.conditionType) },
                { id: 'op', header: t('field.operator'), cell: (c) => label('operator', c.operator) },
                { id: 'value', header: t('field.value'), cell: (c) => conditionValue(c) },
              ]}
            />
            <Table
              header={<Header variant="h2" counter={`(${d.operations.length})`}>{t('action.operations')}</Header>}
              items={d.operations}
              wrapLines
              empty={<Box textAlign="center" color="text-body-secondary">-</Box>}
              columnDefinitions={[
                { id: 'phase', header: t('field.phase'), cell: (o) => label('recovery', o.recovery) },
                { id: 'type', header: t('field.type'), cell: (o) => label('operationType', o.operationType) },
                { id: 'steps', header: t('field.steps'), cell: (o) => (o.recovery === 0 ? `${o.stepFrom} - ${o.stepTo === 0 ? '∞' : o.stepTo}` : '') },
                { id: 'to', header: t('field.recipient'), cell: (o) => [...o.userGroups, ...o.users].join(', ') || '-' },
                { id: 'media', header: t('field.mediaType'), cell: (o) => o.mediaType ?? t('action.allMedia') },
              ]}
            />
          </>
        )}
      </SpaceBetween>
      <ActionDialogs
        serverId={serverId}
        server={server}
        dialog={dialog}
        setDialog={setDialog}
        onDone={(kind, message) => {
          setDialog(null);
          if (kind === 'delete') {
            flashNextPage('success', message);
            window.location.href = url(`/actions?server=${serverId}`);
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

function ActionsPage() {
  const initialServer = new URLSearchParams(window.location.search).get('server');
  const { servers, loading: serversLoading } = useServers((s) => s.admin);
  const [server, setServer] = useState<number | null>(initialServer ? Number(initialServer) : null);
  const [source, setSource] = useState('0');
  const [rows, setRows] = useState<Action[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [reload, setReload] = useState(0);
  const [flash, push] = useFlash();
  const current = servers.find((s) => s.serverId === server);
  const canWrite = current?.writable === true;

  useEffect(() => {
    if (servers.length > 0 && (server === null || !servers.some((s) => s.serverId === server))) setServer(servers[0].serverId);
  }, [servers]);

  useEffect(() => {
    if (server === null) return;
    setLoading(true);
    api.get<Action[]>(`/api/zabbix/servers/${server}/actions` + query({ eventsource: source }))
      .then(setRows)
      .catch((e) => {
        push('error', errorText(e));
        setRows([]);
      })
      .finally(() => setLoading(false));
  }, [server, source, reload]);

  const sourceOptions = [{ value: '', label: t('filter.anySource') }, ...[0, 1, 2, 3, 4].map((s) => ({ value: String(s), label: label('eventSource', s) }))];

  return (
    <Shell active="/actions" flash={flash} contentType="table" breadcrumbs={[{ text: t('nav.actions'), href: '/actions' }]}>
      <DataTable
        tableId="actions"
        title={t('nav.actions')}
        description={t('action.pageHelp')}
        items={rows}
        loading={loading}
        trackBy="actionId"
        actions={canWrite && (
          <Button variant="primary" onClick={() => setDialog({ kind: 'create', value: emptyAction() })}>{t('actionForm.create')}</Button>
        )}
        toolbar={
          <FetchToolbar servers={servers} serversLoading={serversLoading} loading={loading} onFetch={() => setReload((n) => n + 1)}>
            <ServerSelect servers={servers} loading={serversLoading} selected={server} onChange={(id) => { setServer(id); window.history.replaceState(null, '', `?server=${id}`); }} />
            <Select options={sourceOptions} selectedOption={sourceOptions.find((o) => o.value === source) ?? sourceOptions[0]} onChange={(e) => setSource(e.detail.selectedOption.value ?? '')} />
          </FetchToolbar>
        }
        empty={<Box textAlign="center" color="text-body-secondary">{servers.length === 0 && !serversLoading ? t('action.noServer') : t('action.empty')}</Box>}
        defaultSort={{ column: 'name' }}
        columns={[
          { id: 'name', header: t('field.name'), cell: (a) => (server !== null ? <Link href={actionHref(server, a.actionId)}>{a.name}</Link> : a.name), filterValue: (a) => a.name, isRowHeader: true, width: 280 },
          { id: 'source', header: t('field.eventSource'), cell: (a) => label('eventSource', a.eventSource), filterValue: (a) => label('eventSource', a.eventSource), enumerated: true },
          { id: 'status', header: t('field.status'), cell: (a) => <ActionStatus a={a} />, filterValue: (a) => (a.status === 0 ? t('status.enabled') : t('status.disabled')), enumerated: true },
          { id: 'conditions', header: t('action.conditions'), cell: (a) => a.conditionCount, sortValue: (a) => a.conditionCount },
          { id: 'operations', header: t('action.operations'), cell: (a) => a.operationCount, sortValue: (a) => a.operationCount },
          { id: 'esc', header: t('field.escPeriod'), cell: (a) => a.escPeriod, filterValue: (a) => a.escPeriod, enumerated: true },
        ]}
      />
      {server !== null && (
        <ActionDialogs
          serverId={server}
          server={current}
          dialog={dialog}
          setDialog={setDialog}
          onDone={(_, message) => {
            setDialog(null);
            push('success', message);
            setReload((n) => n + 1);
          }}
        />
      )}
    </Shell>
  );
}

const params = new URLSearchParams(window.location.search);
const detailId = params.get('id');
mount(detailId && params.get('server') ? <ActionDetailPage serverId={Number(params.get('server'))} actionId={Number(detailId)} /> : <ActionsPage />);
