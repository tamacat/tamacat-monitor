import { useEffect, useState } from 'react';
import Box from '@cloudscape-design/components/box';
import SpaceBetween from '@cloudscape-design/components/space-between';
import StatusIndicator from '@cloudscape-design/components/status-indicator';

import { DataTable } from '../components/DataTable';
import { FetchToolbar, ServerErrors, useServers } from '../components/ServerFilter';
import { api, errorText } from '../lib/api';
import { formatDateTime, t } from '../lib/boot';
import { mount } from '../lib/mount';
import { duration, failedServers, flatten, query, severityLabel, severityStatus, truncatedServers, type MultiResult, type WithServer } from '../lib/zabbix';
import { Shell, useFlash } from '../shell/Shell';

interface Problem {
  eventId: number;
  name: string;
  severity: number;
  clock: string;
  acknowledged: boolean;
  suppressed: boolean;
  hosts: string[];
}

const LIMIT = 500;

function ProblemsPage() {
  const { servers, loading: serversLoading } = useServers();
  const [selected, setSelected] = useState<number[]>([]);
  const [text, setText] = useState('');
  const [rows, setRows] = useState<WithServer<Problem>[]>([]);
  const [failed, setFailed] = useState<{ serverName: string; error: string }[]>([]);
  const [truncated, setTruncated] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [flash, push] = useFlash();

  const search = async () => {
    setLoading(true);
    try {
      const r = await api.get<MultiResult<Problem>>('/api/zabbix/problems' + query({ q: text, limit: LIMIT }, selected));
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
    <Shell active="/problems" flash={flash} contentType="table" breadcrumbs={[{ text: t('nav.problems'), href: '/problems' }]}>
      <SpaceBetween size="m">
        <ServerErrors failed={failed} truncated={truncated} limit={LIMIT} />
        <DataTable
          tableId="problems"
          title={t('nav.problems')}
          description={t('problem.pageHelp')}
          items={rows}
          loading={loading}
          trackBy={(p) => `${p.serverId}:${p.eventId}`}
          toolbar={
            <FetchToolbar servers={servers} serversLoading={serversLoading} selected={selected} onSelected={setSelected}
              text={text} onText={setText} placeholder={t('problem.searchPlaceholder')} loading={loading} onFetch={() => void search()} />
          }
          empty={<Box textAlign="center" color="text-body-secondary">{t('problem.empty')}</Box>}
          defaultSort={{ column: 'severity', descending: true }}
          columns={[
            {
              id: 'severity',
              header: t('field.severity'),
              cell: (p) => <StatusIndicator type={severityStatus(p.severity)}>{severityLabel(p.severity)}</StatusIndicator>,
              filterValue: (p) => severityLabel(p.severity),
              sortValue: (p) => p.severity * 1e13 + new Date(p.clock).getTime() / 1e3,
              enumerated: true,
              width: 140,
            },
            { id: 'name', header: t('field.problem'), cell: (p) => p.name, filterValue: (p) => p.name, isRowHeader: true, width: 320 },
            { id: 'hosts', header: t('field.host'), cell: (p) => p.hosts.join(', '), filterValue: (p) => p.hosts.join(', '), enumerated: true },
            { id: 'server', header: t('field.server'), cell: (p) => p.serverName, filterValue: (p) => p.serverName, enumerated: true },
            { id: 'since', header: t('field.since'), cell: (p) => formatDateTime(p.clock), sortValue: (p) => p.clock },
            { id: 'duration', header: t('field.duration'), cell: (p) => duration(p.clock), sortValue: (p) => -new Date(p.clock).getTime() },
            {
              id: 'ack',
              header: t('field.acknowledged'),
              cell: (p) => (p.acknowledged ? <StatusIndicator type="success">{t('common.yes')}</StatusIndicator> : t('common.no')),
              filterValue: (p) => (p.acknowledged ? t('common.yes') : t('common.no')),
              enumerated: true,
            },
            { id: 'suppressed', header: t('field.suppressed'), cell: (p) => (p.suppressed ? t('common.yes') : ''), filterValue: (p) => (p.suppressed ? t('common.yes') : t('common.no')), enumerated: true, hidden: true },
          ]}
        />
      </SpaceBetween>
    </Shell>
  );
}

mount(<ProblemsPage />);
