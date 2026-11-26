import Box from '@cloudscape-design/components/box';
import ColumnLayout from '@cloudscape-design/components/column-layout';
import Link from '@cloudscape-design/components/link';
import SpaceBetween from '@cloudscape-design/components/space-between';
import StatusIndicator from '@cloudscape-design/components/status-indicator';

import { formatDateTime, t, url } from '../lib/boot';
import { severityLabel, severityStatus } from '../lib/zabbix';

export interface Summary {
  hostsMonitored: number;
  hostsDisabled: number;
  hostsInMaintenance: number;
  hostsUnavailable: number;
  problemsBySeverity: number[];
  problems: number;
  unacknowledged: number;
  itemsEnabled: number;
  itemsUnsupported: number;
}

export interface ServerCard {
  serverId: number;
  name: string;
  zabbixVersion: string | null;
  status: string;
  stale: boolean;
  admin: boolean;
  summary: Summary | null;
  collectedAt: string | null;
}

/** One figure: label above, large number below (text color, never the status color). */
export function StatTile({ label, value, href, detail }: { label: string; value: number; href?: string; detail?: string }) {
  return (
    <div>
      <Box variant="awsui-key-label">{label}</Box>
      <Box fontSize="display-l" fontWeight="bold">
        {href ? <Link href={href} variant="primary" fontSize="display-l">{value}</Link> : value}
      </Box>
      {detail && <Box variant="small" color="text-body-secondary">{detail}</Box>}
    </div>
  );
}

/** The main figures of a summary. */
export function SummaryTiles({ s, problemsHref }: { s: Summary; problemsHref?: string }) {
  return (
    <ColumnLayout columns={5} variant="text-grid" minColumnWidth={140}>
      <StatTile label={t('dashboard.problems')} value={s.problems} href={problemsHref} />
      <StatTile label={t('dashboard.unacknowledged')} value={s.unacknowledged} />
      <StatTile label={t('dashboard.hostsMonitored')} value={s.hostsMonitored} />
      <StatTile label={t('dashboard.hostsUnavailable')} value={s.hostsUnavailable} />
      <StatTile label={t('dashboard.itemsUnsupported')} value={s.itemsUnsupported} detail={t('dashboard.ofEnabled', { n: s.itemsEnabled })} />
    </ColumnLayout>
  );
}

/**
 * Open problems per severity, highest first. Each severity carries its status icon and name,
 * so it is not identified by color alone.
 */
export function SeverityBreakdown({ counts, compact }: { counts: number[]; compact?: boolean }) {
  const order = [5, 4, 3, 2, 1, 0];
  if (compact) {
    return (
      <SpaceBetween direction="horizontal" size="s">
        {order.filter((s) => counts[s] > 0).map((s) => (
          <StatusIndicator key={s} type={severityStatus(s)}>
            {severityLabel(s)} {counts[s]}
          </StatusIndicator>
        ))}
        {counts.every((n) => n === 0) && <StatusIndicator type="success">{t('dashboard.noProblem')}</StatusIndicator>}
      </SpaceBetween>
    );
  }
  return (
    <ColumnLayout columns={6} variant="text-grid" minColumnWidth={110}>
      {order.map((s) => (
        <div key={s}>
          <StatusIndicator type={counts[s] > 0 ? severityStatus(s) : 'stopped'} colorOverride={counts[s] > 0 ? undefined : 'grey'}>
            {severityLabel(s)}
          </StatusIndicator>
          <Box fontSize="heading-xl" fontWeight="bold">{counts[s]}</Box>
        </div>
      ))}
    </ColumnLayout>
  );
}

/** Connection status of a server card, with the time of stale figures. */
export function CardStatus({ c }: { c: ServerCard }) {
  if (c.status === 'OK') return <StatusIndicator type="success">{t('server.status.OK')}</StatusIndicator>;
  const label =
    c.status === 'TIMEOUT'
      ? t('dashboard.status.timeout')
      : c.status === 'QUERY_ERROR'
        ? t('dashboard.status.queryError')
        : t(`server.status.${c.status === 'UNKNOWN' ? 'unknown' : c.status}`);
  return (
    <SpaceBetween size="xxs">
      <StatusIndicator type={c.status === 'UNSUPPORTED' ? 'warning' : 'error'}>{label}</StatusIndicator>
      {c.stale && c.collectedAt && (
        <Box variant="small" color="text-body-secondary">{t('dashboard.lastKnown', { time: formatDateTime(c.collectedAt) })}</Box>
      )}
    </SpaceBetween>
  );
}

export function serverHref(id: number): string {
  return url(`/server?id=${id}`);
}
