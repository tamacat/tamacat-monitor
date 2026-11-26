import { useEffect, useState } from 'react';
import Alert from '@cloudscape-design/components/alert';
import Badge from '@cloudscape-design/components/badge';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Checkbox from '@cloudscape-design/components/checkbox';
import ColumnLayout from '@cloudscape-design/components/column-layout';
import Container from '@cloudscape-design/components/container';
import Form from '@cloudscape-design/components/form';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import KeyValuePairs from '@cloudscape-design/components/key-value-pairs';
import Modal from '@cloudscape-design/components/modal';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Spinner from '@cloudscape-design/components/spinner';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Link from '@cloudscape-design/components/link';
import Textarea from '@cloudscape-design/components/textarea';
import Toggle from '@cloudscape-design/components/toggle';

import { ActionsMenu, DataTable } from '../components/DataTable';
import { api, ApiError, errorText, fieldError } from '../lib/api';
import { formatDateTime, t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import type { TestResult, ZabbixServer } from '../lib/types';
import { flashNextPage, Shell, useFlash } from '../shell/Shell';

function ServerStatus({ s }: { s: ZabbixServer }) {
  if (!s.enabled) return <StatusIndicator type="stopped">{t('status.disabled')}</StatusIndicator>;
  switch (s.lastStatus) {
    case 'OK':
      return <StatusIndicator type="success">{t('server.status.OK')}</StatusIndicator>;
    case 'UNSUPPORTED':
      return <StatusIndicator type="warning">{t('server.status.UNSUPPORTED')}</StatusIndicator>;
    case 'ERROR':
      return <StatusIndicator type="error">{t('server.status.ERROR')}</StatusIndicator>;
    default:
      return <StatusIndicator type="pending">{t('server.status.unknown')}</StatusIndicator>;
  }
}

interface FormState {
  name: string;
  description: string;
  dbUrl: string;
  readUser: string;
  readPassword: string;
  writeUser: string;
  writePassword: string;
  clearWritePassword: boolean;
  readOnly: boolean;
  enabled: boolean;
}

function toForm(s?: ZabbixServer): FormState {
  return {
    name: s?.name ?? '',
    description: s?.description ?? '',
    dbUrl: s?.dbUrl ?? '',
    readUser: s?.readUser ?? '',
    readPassword: '',
    writeUser: s?.writeUser ?? '',
    writePassword: '',
    clearWritePassword: false,
    readOnly: s?.readOnly ?? true,
    enabled: s?.enabled ?? true,
  };
}

function ServerModal({ server, onClose, onSaved }: { server?: ZabbixServer; onClose: () => void; onSaved: (s: ZabbixServer) => void }) {
  const editing = server !== undefined;
  const [f, setF] = useState<FormState>(toForm(server));
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [test, setTest] = useState<TestResult | null>(null);
  const set = <K extends keyof FormState>(k: K, v: FormState[K]) => setF((o) => ({ ...o, [k]: v }));

  const runTest = async () => {
    setTesting(true);
    setTest(null);
    try {
      setTest(
        await api.post<TestResult>('/api/servers/test', {
          serverId: server?.serverId ?? null,
          dbUrl: f.dbUrl,
          readUser: f.readUser,
          readPassword: f.readPassword,
        }),
      );
    } catch (e) {
      // e.g. a URL rejected by the server-side policy: show the field message
      const key = e instanceof ApiError ? (e.fields.dbUrl ?? e.key) : 'error.internal';
      setTest({ connected: false, supported: false, messageKey: key });
    } finally {
      setTesting(false);
    }
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = { ...f, writeUser: f.writeUser || null };
      const saved = editing
        ? await api.put<ZabbixServer>(`/api/servers/${server.serverId}`, body)
        : await api.post<ZabbixServer>('/api/servers', body);
      onSaved(saved);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };

  const generalError = error !== null && !['name', 'dbUrl', 'readUser', 'readPassword', 'writeUser', 'description'].some((k) => fieldError(error, k));

  return (
    <Modal
      visible
      size="large"
      onDismiss={onClose}
      header={editing ? t('server.edit') : t('server.create')}
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onClose}>
              {t('action.cancel')}
            </Button>
            <Button variant="primary" loading={busy} onClick={() => void save()}>
              {t('action.save')}
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <Form>
        <SpaceBetween size="m">
          {generalError && <Alert type="error">{errorText(error)}</Alert>}
          <ColumnLayout columns={2}>
            <FormField label={t('field.name')} errorText={fieldError(error, 'name')}>
              <Input value={f.name} onChange={(e) => set('name', e.detail.value)} />
            </FormField>
            <FormField label={t('field.enabled')}>
              <Toggle checked={f.enabled} onChange={(e) => set('enabled', e.detail.checked)}>
                {f.enabled ? t('status.enabled') : t('status.disabled')}
              </Toggle>
            </FormField>
          </ColumnLayout>
          <FormField label={t('field.description')} errorText={fieldError(error, 'description')}>
            <Textarea rows={2} value={f.description} onChange={(e) => set('description', e.detail.value)} />
          </FormField>
          <FormField label={t('field.dbUrl')} description={t('server.dbUrlHelp')} errorText={fieldError(error, 'dbUrl')}>
            <Input value={f.dbUrl} placeholder="jdbc:mysql://zabbix-db:3306/zabbix" onChange={(e) => set('dbUrl', e.detail.value)} />
          </FormField>
          <Header variant="h3" description={t('server.readHelp')}>
            {t('server.readAccount')}
          </Header>
          <ColumnLayout columns={2}>
            <FormField label={t('field.dbUser')} errorText={fieldError(error, 'readUser')}>
              <Input value={f.readUser} onChange={(e) => set('readUser', e.detail.value)} autoComplete="off" />
            </FormField>
            <FormField
              label={t('field.dbPassword')}
              constraintText={editing ? t('server.passwordKeep') : undefined}
              errorText={fieldError(error, 'readPassword')}
            >
              <Input type="password" value={f.readPassword} onChange={(e) => set('readPassword', e.detail.value)} autoComplete="new-password" />
            </FormField>
          </ColumnLayout>
          <SpaceBetween size="xs" direction="horizontal" alignItems="center">
            <Button iconName="refresh" loading={testing} onClick={() => void runTest()} disabled={!f.dbUrl || !f.readUser}>
              {t('server.test')}
            </Button>
            {test && <TestOutcome r={test} />}
          </SpaceBetween>
          <Header variant="h3" description={t('server.writeHelp')}>
            {t('server.writeAccount')}
          </Header>
          <ColumnLayout columns={2}>
            <FormField label={t('field.dbUser')} errorText={fieldError(error, 'writeUser')}>
              <Input value={f.writeUser} onChange={(e) => set('writeUser', e.detail.value)} autoComplete="off" disabled={f.clearWritePassword} />
            </FormField>
            <FormField label={t('field.dbPassword')} constraintText={editing && server.hasWritePassword ? t('server.passwordKeep') : undefined}>
              <Input
                type="password"
                value={f.writePassword}
                onChange={(e) => set('writePassword', e.detail.value)}
                autoComplete="new-password"
                disabled={f.clearWritePassword}
              />
            </FormField>
          </ColumnLayout>
          {editing && server.hasWritePassword && (
            <Checkbox checked={f.clearWritePassword} onChange={(e) => set('clearWritePassword', e.detail.checked)}>
              {t('server.clearWrite')}
            </Checkbox>
          )}
          <FormField label={t('field.readOnly')} description={t('server.readOnlyHelp')}>
            <Toggle checked={f.readOnly} onChange={(e) => set('readOnly', e.detail.checked)}>
              {f.readOnly ? t('server.readOnly') : t('server.writable')}
            </Toggle>
          </FormField>
        </SpaceBetween>
      </Form>
    </Modal>
  );
}

function TestOutcome({ r }: { r: TestResult }) {
  if (r.connected && r.supported) {
    return <StatusIndicator type="success">{t('server.testOk', { version: r.zabbixVersion ?? '?' })}</StatusIndicator>;
  }
  if (r.connected) {
    return <StatusIndicator type="warning">{t('error.server.unsupported')} ({r.zabbixVersion})</StatusIndicator>;
  }
  return (
    <StatusIndicator type="error">
      {t(r.messageKey ?? 'error.server.connect')}
      {r.detail ? `: ${r.detail}` : ''}
    </StatusIndicator>
  );
}

function ModeBadge({ s }: { s: ZabbixServer }) {
  return s.writable ? <Badge color="green">{t('server.writable')}</Badge> : <Badge>{t('server.readOnly')}</Badge>;
}

// ---- detail page ----

function ServerDetailPage({ serverId }: { serverId: number }) {
  const [s, setS] = useState<ZabbixServer | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [modal, setModal] = useState<'edit' | 'delete' | null>(null);
  const [checking, setChecking] = useState(false);
  const [flash, push] = useFlash();

  const load = async () => {
    try {
      const r = await api.get<ZabbixServer>(`/api/servers/${serverId}`);
      setS(r);
      document.title = `${r.name} - ${t('nav.servers')}`;
    } catch (e) {
      setError(errorText(e));
    }
  };
  useEffect(() => {
    void load();
  }, [serverId]);

  const check = async () => {
    setChecking(true);
    try {
      const r = await api.post<ZabbixServer>(`/api/servers/${serverId}/check`);
      push(r.lastStatus === 'OK' ? 'success' : 'warning', t('server.checked', { name: r.name, status: t(`server.status.${r.lastStatus ?? 'unknown'}`) }));
      setS(r);
    } catch (e) {
      push('error', errorText(e));
    } finally {
      setChecking(false);
    }
  };

  const remove = async () => {
    if (!s) return;
    try {
      await api.del(`/api/servers/${serverId}`);
      flashNextPage('success', t('server.deleted', { name: s.name }));
      window.location.href = url('/servers');
    } catch (e) {
      push('error', errorText(e));
      setModal(null);
    }
  };

  return (
    <Shell active="/servers" flash={flash} breadcrumbs={[{ text: t('nav.servers'), href: '/servers' }, { text: s?.name ?? '...', href: `/servers?id=${serverId}` }]}>
      <SpaceBetween size="l">
        <Header
          variant="h1"
          description={s?.description || undefined}
          actions={s && (
            <ActionsMenu
              extra={<Button href={url(`/server?id=${serverId}`)}>{t('server.dashboard')}</Button>}
              items={[
                { id: 'check', text: t('server.check'), disabled: checking },
                { id: 'delete', text: t('action.delete') },
              ]}
              onItemClick={(id) => (id === 'check' ? void check() : setModal('delete'))}
              primary={<Button variant="primary" onClick={() => setModal('edit')}>{t('action.edit')}</Button>}
            />
          )}
        >
          {s?.name ?? t('common.loading')}
        </Header>
        {error && <Box color="text-status-error">{error}</Box>}
        {!s && !error && <Spinner size="large" />}
        {s && (
          <Container header={<Header variant="h2">{t('detail.overview')}</Header>}>
            <KeyValuePairs
              columns={3}
              items={[
                { label: t('field.zabbixVersion'), value: s.zabbixVersion ?? '-' },
                { label: t('field.status'), value: checking ? <StatusIndicator type="loading">{t('server.check')}</StatusIndicator> : <ServerStatus s={s} /> },
                { label: t('field.lastChecked'), value: formatDateTime(s.lastCheckedAt) },
                { label: t('field.mode'), value: <ModeBadge s={s} /> },
                { label: t('field.enabled'), value: s.enabled ? t('status.enabled') : t('status.disabled') },
                { label: t('field.dbUrl'), value: <Box variant="code">{s.dbUrl}</Box> },
                { label: t('server.readAccount'), value: s.readUser },
                { label: t('server.writeAccount'), value: s.writeUser ?? '-' },
                { label: t('field.description'), value: s.description || '-' },
              ]}
            />
          </Container>
        )}
      </SpaceBetween>
      {modal === 'edit' && s && (
        <ServerModal
          server={s}
          onClose={() => setModal(null)}
          onSaved={(saved) => {
            setModal(null);
            push(saved.lastStatus === 'OK' ? 'success' : 'warning', t('server.saved', { name: saved.name, status: t(`server.status.${saved.lastStatus ?? 'unknown'}`) }));
            setS(saved);
          }}
        />
      )}
      {modal === 'delete' && s && (
        <Modal
          visible
          onDismiss={() => setModal(null)}
          header={t('server.deleteTitle')}
          footer={
            <Box float="right">
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="link" onClick={() => setModal(null)}>
                  {t('action.cancel')}
                </Button>
                <Button variant="primary" onClick={() => void remove()}>
                  {t('action.delete')}
                </Button>
              </SpaceBetween>
            </Box>
          }
        >
          {t('server.deleteConfirm', { name: s.name })}
        </Modal>
      )}
    </Shell>
  );
}

// ---- list page ----

function ServersPage() {
  const [servers, setServers] = useState<ZabbixServer[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [flash, push] = useFlash();

  const load = async () => {
    setLoading(true);
    try {
      setServers(await api.get<ZabbixServer[]>('/api/servers'));
    } catch (e) {
      push('error', errorText(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  return (
    <Shell active="/servers" flash={flash} contentType="table" breadcrumbs={[{ text: t('nav.servers'), href: '/servers' }]}>
      <DataTable
        tableId="servers"
        title={t('nav.servers')}
        description={t('server.pageHelp')}
        items={servers}
        loading={loading}
        trackBy="serverId"
        actions={
          <SpaceBetween direction="horizontal" size="xs">
            <Button iconName="refresh" ariaLabel={t('action.reload')} onClick={() => void load()} />
            <Button variant="primary" onClick={() => setCreating(true)}>{t('server.create')}</Button>
          </SpaceBetween>
        }
        empty={
          <Box textAlign="center" color="inherit">
            <SpaceBetween size="m">
              <b>{t('server.empty')}</b>
              <Button onClick={() => setCreating(true)}>{t('server.create')}</Button>
            </SpaceBetween>
          </Box>
        }
        defaultSort={{ column: 'name' }}
        columns={[
          { id: 'name', header: t('field.name'), cell: (s) => <Link href={url(`/servers?id=${s.serverId}`)}>{s.name}</Link>, filterValue: (s) => s.name, isRowHeader: true },
          { id: 'version', header: t('field.zabbixVersion'), cell: (s) => s.zabbixVersion ?? '-', filterValue: (s) => s.zabbixVersion ?? '', enumerated: true },
          { id: 'status', header: t('field.status'), cell: (s) => <ServerStatus s={s} />, filterValue: (s) => t(`server.status.${s.lastStatus ?? 'unknown'}`), enumerated: true },
          { id: 'mode', header: t('field.mode'), cell: (s) => <ModeBadge s={s} />, filterValue: (s) => (s.writable ? t('server.writable') : t('server.readOnly')), enumerated: true },
          { id: 'dbUrl', header: t('field.dbUrl'), cell: (s) => s.dbUrl, filterValue: (s) => s.dbUrl, width: 280 },
          { id: 'checked', header: t('field.lastChecked'), cell: (s) => formatDateTime(s.lastCheckedAt), sortValue: (s) => s.lastCheckedAt ?? '' },
        ]}
      />
      {creating && (
        <ServerModal
          onClose={() => setCreating(false)}
          onSaved={(s) => {
            flashNextPage(s.lastStatus === 'OK' ? 'success' : 'warning', t('server.saved', { name: s.name, status: t(`server.status.${s.lastStatus ?? 'unknown'}`) }));
            window.location.href = url(`/servers?id=${s.serverId}`);
          }}
        />
      )}
    </Shell>
  );
}

const serverId = new URLSearchParams(window.location.search).get('id');
mount(serverId ? <ServerDetailPage serverId={Number(serverId)} /> : <ServersPage />);
