import { useEffect, useState } from 'react';
import Box from '@cloudscape-design/components/box';
import DateRangePicker, { type DateRangePickerProps } from '@cloudscape-design/components/date-range-picker';
import SpaceBetween from '@cloudscape-design/components/space-between';
import StatusIndicator from '@cloudscape-design/components/status-indicator';

import { DataTable } from '../components/DataTable';
import { FetchToolbar, ServerErrors, useServers } from '../components/ServerFilter';
import { api, errorText } from '../lib/api';
import { formatDateTime, t } from '../lib/boot';
import { mount } from '../lib/mount';
import { failedServers, flatten, label, query, severityLabel, truncatedServers, type MultiResult, type WithServer } from '../lib/zabbix';
import { Shell, useFlash } from '../shell/Shell';

interface Alert {
  alertId: number;
  clock: string;
  actionName: string | null;
  eventName: string | null;
  severity?: number;
  sendTo: string;
  mediaType: string | null;
  userName: string | null;
  status: number;
  retries: number;
  error: string;
  subject: string;
  alertType: number;
}

const MAX_DAYS = 31;
const LIMIT = 500;

/** Converts the picker value to [from, to] instants. */
function toRange(v: DateRangePickerProps.Value | null): [string, string] | null {
  if (!v) return null;
  if (v.type === 'absolute') return [new Date(v.startDate).toISOString(), new Date(v.endDate).toISOString()];
  const unitMs: Record<string, number> = { second: 1e3, minute: 6e4, hour: 3.6e6, day: 8.64e7, week: 6.048e8, month: 2.592e9, year: 3.1536e10 };
  const to = Date.now();
  return [new Date(to - v.amount * (unitMs[v.unit] ?? 8.64e7)).toISOString(), new Date(to).toISOString()];
}

function eventText(a: Alert): string {
  return a.eventName ? `${a.severity !== undefined ? '[' + severityLabel(a.severity) + '] ' : ''}${a.eventName}` : '-';
}

function typeText(a: Alert): string {
  return a.alertType === 1 ? t('alert.type.1') : a.mediaType ?? t('alert.type.0');
}

function recipient(a: Alert): string {
  return [a.userName, a.sendTo].filter(Boolean).join(' / ');
}

function AlertStatus({ a }: { a: Alert }) {
  const type = a.status === 1 ? 'success' : a.status === 2 ? 'error' : 'pending';
  return <StatusIndicator type={type}>{label('alert.status', a.status)}</StatusIndicator>;
}

function AlertsPage() {
  const { servers, loading: serversLoading } = useServers();
  const [selected, setSelected] = useState<number[]>([]);
  const [range, setRange] = useState<DateRangePickerProps.Value | null>({ type: 'relative', amount: 1, unit: 'day', key: '1d' });
  const [text, setText] = useState('');
  const [rows, setRows] = useState<WithServer<Alert>[]>([]);
  const [failed, setFailed] = useState<{ serverName: string; error: string }[]>([]);
  const [truncated, setTruncated] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [flash, push] = useFlash();

  const search = async () => {
    const r = toRange(range);
    if (!r) return;
    setLoading(true);
    try {
      const res = await api.get<MultiResult<Alert>>('/api/zabbix/alerts' + query({ from: r[0], to: r[1], q: text, limit: LIMIT }, selected));
      setRows(flatten(res));
      setFailed(failedServers(res));
      setTruncated(truncatedServers(res, LIMIT));
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
    <Shell active="/alerts" flash={flash} contentType="table" breadcrumbs={[{ text: t('nav.alerts'), href: '/alerts' }]}>
      <SpaceBetween size="m">
        <ServerErrors failed={failed} truncated={truncated} limit={LIMIT} />
        <DataTable
          tableId="alerts"
          title={t('nav.alerts')}
          description={t('alert.pageHelp')}
          items={rows}
          loading={loading}
          trackBy={(a) => `${a.serverId}:${a.alertId}`}
          toolbar={
            <FetchToolbar servers={servers} serversLoading={serversLoading} selected={selected} onSelected={setSelected}
              text={text} onText={setText} placeholder={t('alert.searchPlaceholder')} loading={loading} onFetch={() => void search()}>
              <DateRangePicker
                value={range}
                onChange={(e) => setRange(e.detail.value)}
                relativeOptions={[
                  { key: '1h', amount: 1, unit: 'hour', type: 'relative' },
                  { key: '1d', amount: 1, unit: 'day', type: 'relative' },
                  { key: '7d', amount: 7, unit: 'day', type: 'relative' },
                  { key: '30d', amount: 30, unit: 'day', type: 'relative' },
                ]}
                isValidRange={(v) => {
                  const r = toRange(v);
                  if (!r) return { valid: false, errorMessage: t('error.period') };
                  const days = (new Date(r[1]).getTime() - new Date(r[0]).getTime()) / 8.64e7;
                  return days > 0 && days <= MAX_DAYS ? { valid: true } : { valid: false, errorMessage: t('error.period') };
                }}
                placeholder={t('alert.period')}
              />
            </FetchToolbar>
          }
          empty={<Box textAlign="center" color="text-body-secondary">{t('alert.empty')}</Box>}
          defaultSort={{ column: 'clock', descending: true }}
          columns={[
            { id: 'clock', header: t('field.time'), cell: (a) => formatDateTime(a.clock), sortValue: (a) => a.clock, width: 180 },
            { id: 'server', header: t('field.server'), cell: (a) => a.serverName, filterValue: (a) => a.serverName, enumerated: true },
            { id: 'action', header: t('field.action'), cell: (a) => a.actionName ?? '-', filterValue: (a) => a.actionName ?? '', enumerated: true },
            { id: 'event', header: t('field.event'), cell: eventText, filterValue: eventText, isRowHeader: true, width: 280 },
            { id: 'type', header: t('field.type'), cell: typeText, filterValue: typeText, enumerated: true },
            { id: 'sendTo', header: t('field.recipient'), cell: recipient, filterValue: recipient, enumerated: true },
            { id: 'status', header: t('field.status'), cell: (a) => <span title={a.error}><AlertStatus a={a} /></span>, filterValue: (a) => label('alert.status', a.status), enumerated: true },
            { id: 'retries', header: t('field.retries'), cell: (a) => a.retries, sortValue: (a) => a.retries },
            { id: 'subject', header: t('field.subject'), cell: (a) => a.subject, filterValue: (a) => a.subject },
            { id: 'error', header: t('dashboard.error'), cell: (a) => a.error, filterValue: (a) => a.error, hidden: true },
          ]}
        />
      </SpaceBetween>
    </Shell>
  );
}

mount(<AlertsPage />);
