import { useEffect, useState } from 'react';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Container from '@cloudscape-design/components/container';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import KeyValuePairs from '@cloudscape-design/components/key-value-pairs';
import Link from '@cloudscape-design/components/link';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Spinner from '@cloudscape-design/components/spinner';
import Table from '@cloudscape-design/components/table';

import { ActionsMenu, DataTable } from '../components/DataTable';
import { FetchToolbar, ServerErrors, ServerSelect, useServers } from '../components/ServerFilter';
import { WriteDialog } from '../components/WriteDialog';
import { api, errorText } from '../lib/api';
import { t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import { failedServers, flatten, hostHref, query, truncatedServers, type MultiResult, type WithServer } from '../lib/zabbix';
import { flashNextPage, Shell, useFlash } from '../shell/Shell';

interface HostGroup {
  groupId: number;
  name: string;
  hostCount: number;
}

interface GroupHost {
  hostId: number;
  name: string;
  status: number;
}

const LIMIT = 500;
const HOST_LIMIT = 200;

function groupHref(serverId: number, groupId: number): string {
  return url(`/hostgroups?server=${serverId}&id=${groupId}`);
}

// ---- detail page ----

function HostGroupDetailPage({ serverId, groupId }: { serverId: number; groupId: number }) {
  const { servers } = useServers();
  const [group, setGroup] = useState<HostGroup | null>(null);
  const [hosts, setHosts] = useState<GroupHost[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<'rename' | 'delete' | null>(null);
  const [name, setName] = useState('');
  const [flash, push] = useFlash();
  const server = servers.find((s) => s.serverId === serverId);
  const canWrite = server !== undefined && server.admin && server.writable;

  const load = async () => {
    try {
      const g = await api.get<HostGroup>(`/api/zabbix/servers/${serverId}/hostgroups/${groupId}`);
      setGroup(g);
      document.title = `${g.name} - ${t('nav.hostgroups')}`;
      const r = await api.get<MultiResult<GroupHost>>('/api/zabbix/hosts' + query({ group: groupId, limit: HOST_LIMIT }, [serverId]));
      setHosts(flatten(r));
    } catch (e) {
      setError(errorText(e));
    }
  };
  useEffect(() => {
    void load();
  }, [serverId, groupId]);

  return (
    <Shell active="/hostgroups" flash={flash} breadcrumbs={[{ text: t('nav.hostgroups'), href: '/hostgroups' }, { text: group?.name ?? '...', href: `/hostgroups?server=${serverId}&id=${groupId}` }]}>
      <SpaceBetween size="l">
        <Header
          variant="h1"
          description={server ? `${server.name} (Zabbix ${server.zabbixVersion})` : undefined}
          actions={group && canWrite && (
            <ActionsMenu
              items={[{ id: 'delete', text: t('action.delete') }]}
              onItemClick={() => setDialog('delete')}
              primary={<Button variant="primary" onClick={() => { setName(group.name); setDialog('rename'); }}>{t('hostgroup.rename')}</Button>}
            />
          )}
        >
          {group?.name ?? t('common.loading')}
        </Header>
        {error && <Box color="text-status-error">{error}</Box>}
        {!group && !error && <Spinner size="large" />}
        {group && (
          <>
            <Container header={<Header variant="h2">{t('detail.overview')}</Header>}>
              <KeyValuePairs
                columns={3}
                items={[
                  { label: t('field.name'), value: group.name },
                  { label: t('field.server'), value: server?.name ?? '-' },
                  { label: t('nav.hosts'), value: group.hostCount },
                ]}
              />
            </Container>
            <Table
              header={<Header variant="h2" counter={`(${group.hostCount})`}>{t('nav.hosts')}</Header>}
              items={hosts ?? []}
              loading={hosts === null}
              loadingText={t('common.loading')}
              wrapLines
              empty={<Box textAlign="center" color="text-body-secondary">{t('host.empty')}</Box>}
              columnDefinitions={[
                { id: 'name', header: t('field.name'), cell: (h) => <Link href={hostHref(serverId, h.hostId)}>{h.name}</Link>, isRowHeader: true },
                { id: 'status', header: t('field.status'), cell: (h) => t(`host.status.${h.status}`) },
              ]}
            />
          </>
        )}
      </SpaceBetween>
      {dialog === 'rename' && group && (
        <WriteDialog
          title={t('hostgroup.rename')}
          formKey={name}
          run={(preview) => api.put(`/api/zabbix/servers/${serverId}/hostgroups/${groupId}`, { expectedName: group.name, name, preview })}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            push('success', t('hostgroup.renamed', { name }));
            void load();
          }}
        >
          <FormField label={t('field.name')} description={group.name} constraintText={t('hostgroup.nameRule')}>
            <Input value={name} onChange={(e) => setName(e.detail.value)} />
          </FormField>
        </WriteDialog>
      )}
      {dialog === 'delete' && group && (
        <WriteDialog
          title={t('hostgroup.deleteTitle')}
          formKey="delete"
          danger
          run={(preview) => api.del(`/api/zabbix/servers/${serverId}/hostgroups/${groupId}` + query({ expectedName: group.name, preview }))}
          onClose={() => setDialog(null)}
          onDone={() => {
            flashNextPage('success', t('hostgroup.deleted', { name: group.name }));
            window.location.href = url('/hostgroups');
          }}
        >
          <Box>{t('hostgroup.deleteConfirm', { name: group.name, server: server?.name ?? '' })}</Box>
        </WriteDialog>
      )}
    </Shell>
  );
}

// ---- list page ----

function HostGroupsPage() {
  const { servers, loading: serversLoading } = useServers();
  const [selected, setSelected] = useState<number[]>([]);
  const [text, setText] = useState('');
  const [rows, setRows] = useState<WithServer<HostGroup>[]>([]);
  const [failed, setFailed] = useState<{ serverName: string; error: string }[]>([]);
  const [truncated, setTruncated] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [target, setTarget] = useState<number | null>(null);
  const [flash, push] = useFlash();
  const writableServers = servers.filter((s) => s.admin && s.writable);

  const search = async () => {
    setLoading(true);
    try {
      const r = await api.get<MultiResult<HostGroup>>('/api/zabbix/hostgroups' + query({ q: text, limit: LIMIT }, selected));
      setRows(flatten(r));
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
  }, []);

  return (
    <Shell active="/hostgroups" flash={flash} contentType="table" breadcrumbs={[{ text: t('nav.hostgroups'), href: '/hostgroups' }]}>
      <SpaceBetween size="m">
        <ServerErrors failed={failed} truncated={truncated} limit={LIMIT} />
        <DataTable
          tableId="hostgroups"
          title={t('nav.hostgroups')}
          description={t('hostgroup.pageHelp')}
          items={rows}
          loading={loading}
          trackBy={(g) => `${g.serverId}:${g.groupId}`}
          actions={writableServers.length > 0 && (
            <Button variant="primary" onClick={() => { setName(''); setTarget(writableServers[0]?.serverId ?? null); setCreating(true); }}>
              {t('hostgroup.create')}
            </Button>
          )}
          toolbar={
            <FetchToolbar servers={servers} serversLoading={serversLoading} selected={selected} onSelected={setSelected}
              text={text} onText={setText} placeholder={t('hostgroup.searchPlaceholder')} loading={loading} onFetch={() => void search()} />
          }
          empty={<Box textAlign="center" color="text-body-secondary">{t('hostgroup.empty')}</Box>}
          defaultSort={{ column: 'name' }}
          columns={[
            { id: 'name', header: t('field.name'), cell: (g) => <Link href={groupHref(g.serverId, g.groupId)}>{g.name}</Link>, filterValue: (g) => g.name, isRowHeader: true, width: 320 },
            { id: 'server', header: t('field.server'), cell: (g) => g.serverName, filterValue: (g) => g.serverName, enumerated: true },
            {
              id: 'hosts',
              header: t('nav.hosts'),
              cell: (g) => (g.hostCount > 0 ? <Link href={url(`/hosts?group=${g.groupId}&groupServer=${g.serverId}&groupName=${encodeURIComponent(g.name)}`)}>{g.hostCount}</Link> : 0),
              sortValue: (g) => g.hostCount,
            },
          ]}
        />
      </SpaceBetween>

      {creating && (
        <WriteDialog
          title={t('hostgroup.create')}
          formKey={`${target}|${name}`}
          run={(preview) => api.post(`/api/zabbix/servers/${target}/hostgroups`, { name, preview })}
          onClose={() => setCreating(false)}
          onDone={() => {
            setCreating(false);
            push('success', t('hostgroup.created', { name }));
            void search();
          }}
        >
          <SpaceBetween size="s">
            <FormField label={t('field.server')} description={t('write.writableOnly')}>
              <ServerSelect servers={writableServers} loading={serversLoading} selected={target} onChange={setTarget} />
            </FormField>
            <FormField label={t('field.name')} constraintText={t('hostgroup.nameRule')}>
              <Input value={name} onChange={(e) => setName(e.detail.value)} />
            </FormField>
          </SpaceBetween>
        </WriteDialog>
      )}
    </Shell>
  );
}

const params = new URLSearchParams(window.location.search);
const detailId = params.get('id');
mount(detailId && params.get('server') ? <HostGroupDetailPage serverId={Number(params.get('server'))} groupId={Number(detailId)} /> : <HostGroupsPage />);
