import { useEffect, useRef, useState } from 'react';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Input from '@cloudscape-design/components/input';
import Link from '@cloudscape-design/components/link';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Toggle from '@cloudscape-design/components/toggle';

import { DataTable } from '../components/DataTable';
import { FetchToolbar, ServerErrors, ServerSelect, useServers } from '../components/ServerFilter';
import { api, errorText } from '../lib/api';
import { formatDateTime, t } from '../lib/boot';
import { mount } from '../lib/mount';
import { formatChange, formatValue, historyHref, isNumeric, MAX_ITEMS } from '../lib/values';
import { hostHref, itemHref, query, type MultiResult } from '../lib/zabbix';
import { Shell, useFlash } from '../shell/Shell';

interface Latest {
  itemId: number;
  hostId: number;
  host: string;
  hostName: string;
  name: string;
  key: string;
  valueType: number;
  units: string;
  delay: string;
  state: number;
  error: string;
  tags: { tag: string; value: string }[];
  lastClock: string | null;
  lastValue: string | null;
  prevValue: string | null;
}

interface HostGroup {
  groupId: number;
  name: string;
}

/** Rows per request (the API maximum) and the most rows fetched for one list. */
const PAGE = 200;
const MAX_ROWS = 2000;
const REFRESH = [0, 30, 60, 300];

const value = (i: Latest) => formatValue(i.lastValue, i.valueType, i.units);
const change = (i: Latest) => formatChange(i.lastValue, i.prevValue, i.valueType, i.units);
const tagText = (i: Latest) => i.tags.map((x) => (x.value ? `${x.tag}: ${x.value}` : x.tag)).join(', ');

function LatestPage() {
  const params = new URLSearchParams(window.location.search);
  const { servers, loading: serversLoading } = useServers();
  const [server, setServer] = useState<number | null>(params.get('server') ? Number(params.get('server')) : null);
  const [groups, setGroups] = useState<HostGroup[]>([]);
  const [group, setGroup] = useState<number | null>(params.get('group') ? Number(params.get('group')) : null);
  const hostId = params.get('host') ? Number(params.get('host')) : null;
  const [text, setText] = useState('');
  const [tag, setTag] = useState('');
  const [withData, setWithData] = useState(true);
  const [rows, setRows] = useState<Latest[]>([]);
  const [selected, setSelected] = useState<Latest[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [loading, setLoading] = useState(false);
  const [updated, setUpdated] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(Number(window.localStorage.getItem('tmon.latest.refresh') ?? 60));
  const [flash, push] = useFlash();
  const serverName = servers.find((s) => s.serverId === server)?.name ?? '';

  useEffect(() => {
    if (server === null && servers.length > 0) setServer(servers[0].serverId);
  }, [servers]);

  useEffect(() => {
    if (server === null) return;
    api.get<MultiResult<HostGroup>>('/api/zabbix/hostgroups' + query({ limit: 500 }, [server]))
      .then((r) => setGroups(r.results.flatMap((s) => s.items)))
      .catch(() => setGroups([]));
  }, [server]);

  /** Pages of the API until a short page or MAX_ROWS rows. */
  const search = async (quiet = false) => {
    if (server === null) return;
    if (!quiet) setLoading(true);
    try {
      const list: Latest[] = [];
      for (let offset = 0; offset < MAX_ROWS; offset += PAGE) {
        const page = await api.get<Latest[]>(`/api/zabbix/servers/${server}/latest` + query({ group, host: hostId, q: text, tag, limit: PAGE, offset }));
        list.push(...page);
        if (page.length < PAGE) break;
      }
      setRows(list);
      setSelected((sel) => list.filter((r) => sel.some((s) => s.itemId === r.itemId)));
      setTruncated(list.length >= MAX_ROWS);
      setUpdated(new Date().toISOString());
    } catch (e) {
      push('error', errorText(e));
      if (!quiet) setRows([]);
    } finally {
      if (!quiet) setLoading(false);
    }
  };

  useEffect(() => {
    void search();
  }, [server]);

  // auto refresh with the current conditions
  const searchRef = useRef(search);
  searchRef.current = search;
  useEffect(() => {
    window.localStorage.setItem('tmon.latest.refresh', String(refresh));
    if (refresh <= 0) return;
    const id = window.setInterval(() => void searchRef.current(true), refresh * 1000);
    return () => window.clearInterval(id);
  }, [refresh]);

  const shown = withData ? rows.filter((r) => r.lastValue !== null) : rows;
  const hostName = hostId ? rows[0]?.hostName : undefined;
  const selectedIds = selected.map((s) => s.itemId);
  const tooMany = selected.length > MAX_ITEMS;
  const graphable = selected.length > 0 && !tooMany && selected.every((s) => isNumeric(s.valueType));
  const groupOptions = [{ value: '', label: t('latest.allGroups') }, ...groups.map((g) => ({ value: String(g.groupId), label: g.name }))];
  const refreshOptions = REFRESH.map((s) => ({ value: String(s), label: t(`latest.refresh.${s}`) }));

  return (
    <Shell
      active="/latest"
      flash={flash}
      contentType="table"
      breadcrumbs={[
        { text: t('nav.latest'), href: server ? `/latest?server=${server}` : '/latest' },
        ...(hostId && server ? [{ text: hostName ?? String(hostId), href: `/latest?server=${server}&host=${hostId}` }] : []),
      ]}
    >
      <SpaceBetween size="m">
        <ServerErrors failed={[]} truncated={truncated ? [serverName] : []} limit={MAX_ROWS} />
        <DataTable
          tableId="latest"
          title={t('nav.latest')}
          description={
            <SpaceBetween size="xxs">
              <span>{hostId ? t('item.hostFiltered', { host: hostName ?? hostId }) : t('latest.pageHelp')}</span>
              {updated && <Box variant="small" color="text-body-secondary">{t('latest.updated', { time: formatDateTime(updated) })}</Box>}
            </SpaceBetween>
          }
          items={shown}
          loading={loading}
          trackBy={(i) => String(i.itemId)}
          selectionType="multi"
          selectedItems={selected}
          onSelectionChange={setSelected}
          actions={
            <SpaceBetween direction="horizontal" size="xs" alignItems="center">
              {tooMany && <Box color="text-status-warning">{t('latest.maxItems', { max: MAX_ITEMS })}</Box>}
              <Button disabled={selected.length === 0 || tooMany || server === null} href={server !== null ? historyHref(server, selectedIds, 'values') : undefined}>
                {t('latest.history')}
              </Button>
              <Button variant="primary" disabled={!graphable || server === null} disabledReason={selected.length > 0 && !tooMany ? t('latest.notNumeric') : undefined}
                href={server !== null && graphable ? historyHref(server, selectedIds, 'graph') : undefined}>
                {t('latest.graph')}
              </Button>
            </SpaceBetween>
          }
          toolbar={
            <SpaceBetween size="xs">
              <FetchToolbar servers={servers} serversLoading={serversLoading} text={text} onText={setText} placeholder={t('latest.searchPlaceholder')} loading={loading} onFetch={() => void search()}>
                <ServerSelect servers={servers} loading={serversLoading} selected={server} onChange={(id) => { setServer(id); setGroup(null); setSelected([]); window.history.replaceState(null, '', `?server=${id}`); }} />
                {!hostId && (
                  <Select
                    options={groupOptions}
                    selectedOption={groupOptions.find((o) => o.value === String(group ?? '')) ?? groupOptions[0]}
                    onChange={(e) => setGroup(e.detail.selectedOption.value ? Number(e.detail.selectedOption.value) : null)}
                    filteringType="auto"
                    ariaLabel={t('field.hostGroup')}
                  />
                )}
                <Input type="search" value={tag} placeholder={t('latest.tagPlaceholder')} ariaLabel={t('latest.tagPlaceholder')} onChange={(e) => setTag(e.detail.value)} />
              </FetchToolbar>
              <SpaceBetween direction="horizontal" size="l" alignItems="center">
                <Toggle checked={withData} onChange={(e) => setWithData(e.detail.checked)}>{t('latest.withData')}</Toggle>
                <Select
                  options={refreshOptions}
                  selectedOption={refreshOptions.find((o) => Number(o.value) === refresh) ?? refreshOptions[0]}
                  onChange={(e) => setRefresh(Number(e.detail.selectedOption.value))}
                  ariaLabel={t('latest.refresh')}
                />
              </SpaceBetween>
            </SpaceBetween>
          }
          empty={<Box textAlign="center" color="text-body-secondary">{server === null ? t('filter.chooseServer') : t('latest.empty')}</Box>}
          defaultSort={{ column: 'host' }}
          columns={[
            { id: 'host', header: t('field.host'), cell: (i) => (server !== null ? <Link href={hostHref(server, i.hostId)}>{i.hostName}</Link> : i.hostName), filterValue: (i) => i.hostName, enumerated: true },
            { id: 'name', header: t('field.name'), cell: (i) => (server !== null ? <Link href={itemHref(server, i.itemId)}>{i.name}</Link> : i.name), filterValue: (i) => i.name, isRowHeader: true, width: 280 },
            { id: 'key', header: t('field.key'), cell: (i) => <Box variant="code">{i.key}</Box>, filterValue: (i) => i.key, width: 220, hidden: true },
            { id: 'clock', header: t('field.lastCheck'), cell: (i) => formatDateTime(i.lastClock), sortValue: (i) => i.lastClock ?? '', width: 180 },
            {
              id: 'value',
              header: t('field.lastValue'),
              cell: (i) => (i.state === 1 ? <span title={i.error}><StatusIndicator type="error">{t('item.state.1')}</StatusIndicator></span> : <span title={i.lastValue ?? ''}>{value(i)}</span>),
              filterValue: value,
              sortValue: (i) => (isNumeric(i.valueType) && i.lastValue !== null ? Number(i.lastValue) : value(i)),
            },
            { id: 'change', header: t('latest.change'), cell: change, sortValue: (i) => (i.lastValue !== null && i.prevValue !== null && isNumeric(i.valueType) ? Number(i.lastValue) - Number(i.prevValue) : 0), width: 120 },
            { id: 'tags', header: t('field.tags'), cell: tagText, filterValue: tagText, width: 220 },
            { id: 'delay', header: t('field.interval'), cell: (i) => i.delay, filterValue: (i) => i.delay, enumerated: true, width: 110, hidden: true },
            {
              id: 'links',
              header: '',
              width: 150,
              cell: (i) =>
                server !== null && (
                  <SpaceBetween direction="horizontal" size="s">
                    {isNumeric(i.valueType) && <Link href={historyHref(server, [i.itemId], 'graph')}>{t('latest.graph')}</Link>}
                    <Link href={historyHref(server, [i.itemId], 'values')}>{t('latest.history')}</Link>
                  </SpaceBetween>
                ),
            },
          ]}
        />
      </SpaceBetween>
    </Shell>
  );
}

mount(<LatestPage />);
