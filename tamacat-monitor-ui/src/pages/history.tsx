import { useEffect, useState } from 'react';
import Alert from '@cloudscape-design/components/alert';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Checkbox from '@cloudscape-design/components/checkbox';
import Container from '@cloudscape-design/components/container';
import DateRangePicker, { type DateRangePickerProps } from '@cloudscape-design/components/date-range-picker';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import LineChart from '@cloudscape-design/components/line-chart';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Spinner from '@cloudscape-design/components/spinner';
import Tabs from '@cloudscape-design/components/tabs';

import { DataTable } from '../components/DataTable';
import { ApiError, api, errorText } from '../lib/api';
import { boot, formatDateTime, t } from '../lib/boot';
import { mount } from '../lib/mount';
import { formatBucket, formatNumber, formatValue, isNumeric, logSeverityLabel, MAX_ITEMS, withItems, type ItemRef } from '../lib/values';
import { itemHref } from '../lib/zabbix';
import { Shell, useFlash } from '../shell/Shell';

interface Series {
  item: ItemRef;
  source: 'history' | 'trends';
  bucket: number;
  /** [epoch seconds, min, avg, max] */
  points: [number, number | null, number | null, number | null][];
}

interface HistoryRow {
  itemId: number;
  clock: string;
  ns: number;
  value: string | null;
  logTime?: string | null;
  source?: string;
  severity?: number;
  logEventId?: number;
}

type View = 'graph' | 'values';
type Source = 'auto' | 'history' | 'trends';

const DAY = 8.64e7;
const MAX_VALUE_DAYS = 31;
const MAX_GRAPH_DAYS = 732;
/** Rows per request (the API maximum) and the most rows fetched. */
const PAGE = 1000;
const MAX_ROWS = 5000;

const RELATIVE: DateRangePickerProps.RelativeOption[] = [
  { key: '1h', amount: 1, unit: 'hour', type: 'relative' },
  { key: '3h', amount: 3, unit: 'hour', type: 'relative' },
  { key: '12h', amount: 12, unit: 'hour', type: 'relative' },
  { key: '1d', amount: 1, unit: 'day', type: 'relative' },
  { key: '7d', amount: 7, unit: 'day', type: 'relative' },
  { key: '30d', amount: 30, unit: 'day', type: 'relative' },
  { key: '90d', amount: 90, unit: 'day', type: 'relative' },
  { key: '1y', amount: 1, unit: 'year', type: 'relative' },
];

/** Converts the picker value to [from, to] instants (relative periods end now). */
function toRange(v: DateRangePickerProps.Value | null): [string, string] | null {
  if (!v) return null;
  if (v.type === 'absolute') return [new Date(v.startDate).toISOString(), new Date(v.endDate).toISOString()];
  const unitMs: Record<string, number> = { second: 1e3, minute: 6e4, hour: 3.6e6, day: DAY, week: 7 * DAY, month: 30 * DAY, year: 365 * DAY };
  const to = Date.now();
  return [new Date(to - v.amount * (unitMs[v.unit] ?? DAY)).toISOString(), new Date(to).toISOString()];
}

const days = (r: [string, string]) => (new Date(r[1]).getTime() - new Date(r[0]).getTime()) / DAY;

/** The first field error of a validation answer, else the general error text. */
function problemText(e: unknown): string {
  if (e instanceof ApiError) {
    const f = Object.values(e.fields)[0];
    if (f) return t(f);
  }
  return errorText(e);
}

const itemLabel = (i: ItemRef, multiHost: boolean) => (multiHost ? `${i.hostName}: ${i.name}` : i.name);

function csvCell(v: string | number | null | undefined): string {
  const s = v === null || v === undefined ? '' : String(v);
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function downloadCsv(rows: HistoryRow[], items: Map<number, ItemRef>) {
  const hasLog = rows.some((r) => r.logEventId !== undefined);
  const head = ['clock', 'ns', 'host', 'item', 'key', 'value', ...(hasLog ? ['logTime', 'source', 'severity', 'logEventId'] : [])];
  const lines = [head.join(',')];
  for (const r of rows) {
    const i = items.get(r.itemId);
    const cells = [r.clock, r.ns, i?.host, i?.name, i?.key, r.value, ...(hasLog ? [r.logTime, r.source, r.severity, r.logEventId] : [])];
    lines.push(cells.map(csvCell).join(','));
  }
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `history-${new Date().toISOString().replace(/[:.]/g, '-')}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}

// ---- graph ----

function GraphView({ serverId, itemIds, range, multiHost }: { serverId: number; itemIds: number[]; range: DateRangePickerProps.Value | null; multiHost: boolean }) {
  const [source, setSource] = useState<Source>('auto');
  const [minMax, setMinMax] = useState(false);
  const [series, setSeries] = useState<Series[] | null>(null);
  const [period, setPeriod] = useState<[string, string] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    const r = toRange(range);
    if (!r) return;
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<{ series: Series[] }>(withItems(`/api/zabbix/servers/${serverId}/graph`, itemIds, { from: r[0], to: r[1], source, points: 600 }));
      setSeries(res.series);
      setPeriod(r);
    } catch (e) {
      setError(problemText(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [range, source]);

  const units = series?.[0]?.item.units ?? '';
  const spanDays = period ? days(period) : 1;
  const xFormat = (d: Date) =>
    d.toLocaleString(boot.locale === 'ja' ? 'ja-JP' : 'en-US', spanDays <= 1 ? { hour: '2-digit', minute: '2-digit' } : spanDays <= 7 ? { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' } : { year: spanDays > 366 ? 'numeric' : undefined, month: 'numeric', day: 'numeric' });
  const chartSeries = (series ?? []).flatMap((s) => {
    const name = itemLabel(s.item, multiHost);
    const pick = (idx: 1 | 2 | 3) => s.points.filter((p) => p[idx] !== null).map((p) => ({ x: new Date(p[0] * 1000), y: p[idx] as number }));
    const avg = { title: name, type: 'line' as const, data: pick(2), valueFormatter: (v: number) => formatNumber(v, s.item.units) };
    if (!minMax) return [avg];
    return [
      avg,
      { title: t('history.series.min', { name }), type: 'line' as const, data: pick(1), valueFormatter: (v: number) => formatNumber(v, s.item.units) },
      { title: t('history.series.max', { name }), type: 'line' as const, data: pick(3), valueFormatter: (v: number) => formatNumber(v, s.item.units) },
    ];
  });
  const sources = Array.from(new Set((series ?? []).map((s) => t(`history.source.${s.source}`))));
  const buckets = Array.from(new Set((series ?? []).map((s) => formatBucket(s.bucket))));
  const sourceOptions = (['auto', 'history', 'trends'] as Source[]).map((s) => ({ value: s, label: t(`history.source.${s}`) }));

  return (
    <Container
      header={
        <Header
          variant="h2"
          description={series && series.length > 0 && t('history.sourceInfo', { source: sources.join(', '), bucket: buckets.join(', ') })}
          actions={
            <SpaceBetween direction="horizontal" size="s" alignItems="center">
              <Checkbox checked={minMax} onChange={(e) => setMinMax(e.detail.checked)}>{t('history.showMinMax')}</Checkbox>
              <Select options={sourceOptions} selectedOption={sourceOptions.find((o) => o.value === source)!} onChange={(e) => setSource(e.detail.selectedOption.value as Source)} ariaLabel={t('history.source')} />
              <Button iconName="refresh" loading={loading} onClick={() => void load()} ariaLabel={t('table.fetch')} />
            </SpaceBetween>
          }
        >
          {t('history.tab.graph')}
        </Header>
      }
    >
      {error ? (
        <Alert type="error">{error}</Alert>
      ) : (
        <LineChart
          series={chartSeries}
          xScaleType="time"
          xDomain={period ? [new Date(period[0]), new Date(period[1])] : undefined}
          xTickFormatter={xFormat}
          yTickFormatter={(v) => formatNumber(v, units, 2)}
          detailPopoverSeriesContent={({ series: s, y }) => ({ key: s.title, value: (s as { valueFormatter?: (v: number) => string }).valueFormatter?.(y) ?? String(y) })}
          statusType={loading && !series ? 'loading' : 'finished'}
          height={340}
          hideFilter={chartSeries.length <= 1}
          ariaLabel={t('history.tab.graph')}
          empty={<Box textAlign="center" color="text-body-secondary">{t('history.noData')}</Box>}
        />
      )}
    </Container>
  );
}

// ---- values ----

function ValuesView({ serverId, itemIds, items, range, multiHost }: { serverId: number; itemIds: number[]; items: ItemRef[]; range: DateRangePickerProps.Value | null; multiHost: boolean }) {
  const [text, setText] = useState('');
  const [exclude, setExclude] = useState(false);
  const [min, setMin] = useState('');
  const [max, setMax] = useState('');
  const [rows, setRows] = useState<HistoryRow[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const byId = new Map(items.map((i) => [i.itemId, i]));
  const hasNumeric = items.some((i) => isNumeric(i.valueType));
  const hasText = items.some((i) => !isNumeric(i.valueType));
  const hasLog = items.some((i) => i.valueType === 2);

  const load = async () => {
    const r = toRange(range);
    if (!r) return;
    if (days(r) > MAX_VALUE_DAYS) {
      setError(t('error.period'));
      setRows([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const list: HistoryRow[] = [];
      for (let offset = 0; offset < MAX_ROWS; offset += PAGE) {
        const res = await api.get<{ rows: HistoryRow[] }>(withItems(`/api/zabbix/servers/${serverId}/history`, itemIds, {
          from: r[0], to: r[1], q: text, exclude: text ? exclude : null, min, max, limit: PAGE, offset,
        }));
        list.push(...res.rows);
        if (res.rows.length < PAGE) break;
      }
      setRows(list);
      setTruncated(list.length >= MAX_ROWS);
    } catch (e) {
      setError(problemText(e));
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [range]);

  const value = (r: HistoryRow) => {
    const i = byId.get(r.itemId);
    return i ? formatValue(r.value, i.valueType, i.units) : r.value ?? '';
  };
  const name = (r: HistoryRow) => {
    const i = byId.get(r.itemId);
    return i ? itemLabel(i, multiHost) : String(r.itemId);
  };

  return (
    <SpaceBetween size="m">
      {error && <Alert type="error">{error}</Alert>}
      {truncated && <Alert type="info">{t('history.limitReached', { limit: MAX_ROWS })}</Alert>}
      <DataTable
        tableId={hasLog ? 'history-log' : 'history'}
        title={t('history.tab.values')}
        description={hasNumeric && hasText ? t('history.filterHelp') : undefined}
        items={rows}
        loading={loading}
        trackBy={(r) => `${r.itemId}:${r.clock}:${r.ns}`}
        actions={<Button iconName="download" disabled={rows.length === 0} onClick={() => downloadCsv(rows, byId)}>{t('history.download')}</Button>}
        toolbar={
          <form onSubmit={(e) => { e.preventDefault(); void load(); }}>
            <SpaceBetween direction="horizontal" size="xs" alignItems="center">
              {hasText && <Input type="search" value={text} placeholder={t('history.textFilter')} ariaLabel={t('history.textFilter')} onChange={(e) => setText(e.detail.value)} />}
              {hasText && <Checkbox checked={exclude} onChange={(e) => setExclude(e.detail.checked)}>{t('history.exclude')}</Checkbox>}
              {hasNumeric && <Input type="number" value={min} placeholder={t('history.min')} ariaLabel={t('history.min')} onChange={(e) => setMin(e.detail.value)} />}
              {hasNumeric && <Input type="number" value={max} placeholder={t('history.max')} ariaLabel={t('history.max')} onChange={(e) => setMax(e.detail.value)} />}
              <Button formAction="submit" iconName="refresh" loading={loading}>{t('table.fetch')}</Button>
            </SpaceBetween>
          </form>
        }
        empty={<Box textAlign="center" color="text-body-secondary">{t('history.empty')}</Box>}
        defaultSort={{ column: 'clock', descending: true }}
        columns={[
          { id: 'clock', header: t('field.time'), cell: (r) => formatDateTime(r.clock), sortValue: (r) => `${r.clock}:${String(r.ns).padStart(9, '0')}`, width: 190 },
          ...(itemIds.length > 1 ? [{ id: 'item', header: t('field.item'), cell: name, filterValue: name, enumerated: true, width: 260 }] : []),
          ...(hasLog
            ? [
                { id: 'logTime', header: t('field.logTime'), cell: (r: HistoryRow) => formatDateTime(r.logTime), sortValue: (r: HistoryRow) => r.logTime ?? '', width: 190, hidden: true },
                { id: 'source', header: t('field.source'), cell: (r: HistoryRow) => r.source ?? '', filterValue: (r: HistoryRow) => r.source ?? '', enumerated: true },
                { id: 'severity', header: t('field.logSeverity'), cell: (r: HistoryRow) => (r.severity !== undefined ? logSeverityLabel(r.severity) : ''), filterValue: (r: HistoryRow) => (r.severity !== undefined ? logSeverityLabel(r.severity) : ''), enumerated: true, width: 130 },
                { id: 'eventId', header: t('field.eventId'), cell: (r: HistoryRow) => r.logEventId ?? '', sortValue: (r: HistoryRow) => r.logEventId ?? 0, width: 110, hidden: true },
              ]
            : []),
          {
            id: 'value',
            header: t('field.value'),
            cell: (r) => <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{value(r)}</span>,
            filterValue: (r) => r.value ?? '',
            sortValue: (r) => {
              const i = byId.get(r.itemId);
              return i && isNumeric(i.valueType) && r.value !== null ? Number(r.value) : r.value ?? '';
            },
            width: 480,
            isRowHeader: true,
          },
        ]}
      />
    </SpaceBetween>
  );
}

// ---- page ----

function HistoryPage() {
  const params = new URLSearchParams(window.location.search);
  const serverId = Number(params.get('server'));
  const itemIds = Array.from(new Set(params.getAll('item').flatMap((v) => v.split(',')).map(Number).filter((n) => Number.isInteger(n) && n > 0))).slice(0, MAX_ITEMS);
  const [items, setItems] = useState<ItemRef[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<View>(params.get('view') === 'values' ? 'values' : 'graph');
  const [range, setRange] = useState<DateRangePickerProps.Value | null>(RELATIVE[0]);
  const [flash] = useFlash();

  // item names and types (a minimal history read)
  useEffect(() => {
    if (!serverId || itemIds.length === 0) {
      setError(t('error.history.items'));
      return;
    }
    const to = new Date();
    api.get<{ items: ItemRef[] }>(withItems(`/api/zabbix/servers/${serverId}/history`, itemIds, { from: new Date(to.getTime() - 1000).toISOString(), to: to.toISOString(), limit: 1 }))
      .then((r) => {
        setItems(r.items);
        if (!r.items.every((i) => isNumeric(i.valueType))) setView('values');
        document.title = `${r.items.length === 1 ? r.items[0].name : t('history.items', { count: r.items.length })} - ${t('nav.history')}`;
      })
      .catch((e) => setError(problemText(e)));
  }, []);

  const numeric = items !== null && items.every((i) => isNumeric(i.valueType));
  const hosts = new Set((items ?? []).map((i) => i.hostId));
  const multiHost = hosts.size > 1;
  const single = items?.length === 1 ? items[0] : null;
  const host = items && hosts.size === 1 ? items[0] : null;
  const changeView = (v: View) => {
    setView(v);
    const q = new URLSearchParams(window.location.search);
    q.set('view', v);
    window.history.replaceState(null, '', `?${q.toString()}`);
  };

  return (
    <Shell
      active="/latest"
      flash={flash}
      breadcrumbs={[
        { text: t('nav.latest'), href: `/latest?server=${serverId}` },
        ...(host ? [{ text: host.hostName, href: `/latest?server=${serverId}&host=${host.hostId}` }] : []),
        { text: single ? single.name : t('history.items', { count: itemIds.length }), href: `/history?${params.toString()}` },
      ]}
    >
      <SpaceBetween size="l">
        <Header
          variant="h1"
          description={single ? <Box variant="code">{single.key}</Box> : items && items.map((i) => itemLabel(i, multiHost)).join(' / ')}
          actions={single && <Button href={itemHref(serverId, single.itemId)}>{t('nav.items')}</Button>}
        >
          {single ? `${single.hostName}: ${single.name}` : items ? t('history.items', { count: items.length }) : t('common.loading')}
        </Header>
        {error && <Alert type="error">{error}</Alert>}
        {!items && !error && <Spinner size="large" />}
        {items && (
          <>
            <SpaceBetween direction="horizontal" size="xs" alignItems="center">
              <DateRangePicker
                value={range}
                onChange={(e) => setRange(e.detail.value)}
                relativeOptions={RELATIVE}
                isValidRange={(v) => {
                  const r = toRange(v);
                  if (!r) return { valid: false, errorMessage: t('history.graphPeriod') };
                  const d = days(r);
                  const limit = view === 'values' ? MAX_VALUE_DAYS : MAX_GRAPH_DAYS;
                  return d > 0 && d <= limit ? { valid: true } : { valid: false, errorMessage: view === 'values' ? t('error.period') : t('history.graphPeriod') };
                }}
                placeholder={t('history.period')}
              />
            </SpaceBetween>
            <Tabs
              activeTabId={view}
              onChange={(e) => changeView(e.detail.activeTabId as View)}
              tabs={[
                {
                  id: 'graph',
                  label: t('history.tab.graph'),
                  disabled: !numeric,
                  disabledReason: t('latest.notNumeric'),
                  content: numeric && view === 'graph' && <GraphView serverId={serverId} itemIds={itemIds} range={range} multiHost={multiHost} />,
                },
                {
                  id: 'values',
                  label: t('history.tab.values'),
                  content: view === 'values' && <ValuesView serverId={serverId} itemIds={itemIds} items={items} range={range} multiHost={multiHost} />,
                },
              ]}
            />
          </>
        )}
      </SpaceBetween>
    </Shell>
  );
}

mount(<HistoryPage />);

