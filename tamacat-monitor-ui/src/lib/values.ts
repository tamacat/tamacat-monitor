// Formatting of collected values (latest data, graphs, history), close to the Zabbix frontend.
import { boot, t } from './boot';
import { query } from './zabbix';

/** Item fields the history API returns with every series / row set. */
export interface ItemRef {
  itemId: number;
  hostId: number;
  host: string;
  hostName: string;
  name: string;
  key: string;
  valueType: number;
  units: string;
}

/** Most items in one graph or history view (the API maximum). */
export const MAX_ITEMS = 20;

export const isNumeric = (valueType: number) => valueType === 0 || valueType === 3;

const locale = () => (boot.locale === 'ja' ? 'ja-JP' : 'en-US');

/** Units shown without a prefix (Zabbix ZBX_UNITS blacklist). */
const NO_PREFIX = new Set(['%', 'ms', 'rpm', 'RPM']);

function seconds(v: number): string {
  const neg = v < 0;
  let s = Math.abs(v);
  if (s < 1) return `${neg ? '-' : ''}${Math.round(s * 1000)}ms`;
  const parts: string[] = [];
  const units: [string, number][] = [['y', 31536000], ['M', 2592000], ['d', 86400], ['h', 3600], ['m', 60], ['s', 1]];
  for (const [u, n] of units) {
    if (s >= n && parts.length < 3) {
      const q = Math.floor(s / n);
      parts.push(`${q}${u}`);
      s -= q * n;
    }
  }
  return (neg ? '-' : '') + (parts.join(' ') || '0s');
}

function uptime(v: number): string {
  const d = Math.floor(v / 86400);
  const rest = Math.floor(v % 86400);
  const hh = String(Math.floor(rest / 3600)).padStart(2, '0');
  const mm = String(Math.floor((rest % 3600) / 60)).padStart(2, '0');
  const ss = String(rest % 60).padStart(2, '0');
  return `${d > 0 ? `${d}d, ` : ''}${hh}:${mm}:${ss}`;
}

/** A number with units: K/M/G... prefixes (1024 for B and Bps), "s", "uptime" and "unixtime" as in Zabbix. */
export function formatNumber(v: number, units: string, digits = 4): string {
  if (!Number.isFinite(v)) return '-';
  if (units === 'unixtime') return v > 0 ? new Date(v * 1000).toLocaleString(locale()) : '-';
  if (units === 'uptime') return uptime(v);
  if (units === 's') return seconds(v);
  // small fractions keep significant digits instead of rounding to 0 ("0.00003")
  const plain = (n: number) =>
    n !== 0 && Math.abs(n) < 1
      ? n.toLocaleString(locale(), { maximumSignificantDigits: digits })
      : n.toLocaleString(locale(), { maximumFractionDigits: digits });
  // "!" before the units: shown as they are, without prefixes (Zabbix 6.0+)
  if (units.startsWith('!')) return `${plain(v)} ${units.substring(1)}`.trimEnd();
  if (!units || NO_PREFIX.has(units)) return units ? `${plain(v)} ${units}` : plain(v);
  const base = units === 'B' || units === 'Bps' ? 1024 : 1000;
  const prefixes = ['', 'K', 'M', 'G', 'T', 'P', 'E'];
  let n = v;
  let i = 0;
  while (Math.abs(n) >= base && i < prefixes.length - 1) {
    n /= base;
    i++;
  }
  return `${plain(i === 0 ? n : Math.round(n * 100) / 100)} ${prefixes[i]}${units}`;
}

/** A value as the API returns it (text), formatted for its value type. */
export function formatValue(value: string | null, valueType: number, units: string): string {
  if (value === null) return '-';
  if (isNumeric(valueType)) {
    const n = Number(value);
    return Number.isFinite(n) ? formatNumber(n, units) : value;
  }
  return value;
}

/** Change between two numeric values ("+1.5 %"), or '' when there is none. */
export function formatChange(last: string | null, prev: string | null, valueType: number, units: string): string {
  if (!isNumeric(valueType) || last === null || prev === null) return '';
  const d = Number(last) - Number(prev);
  if (!Number.isFinite(d) || d === 0) return '';
  if (units === 'unixtime') return seconds(d);
  return `${d > 0 ? '+' : ''}${formatNumber(d, units)}`;
}

/** Seconds as a short period ("5m", "1h", "1d"). */
export function formatBucket(sec: number): string {
  return seconds(sec);
}

export function historyHref(serverId: number, itemIds: number[], view?: 'graph' | 'values'): string {
  const q = new URLSearchParams();
  q.set('server', String(serverId));
  itemIds.forEach((i) => q.append('item', String(i)));
  if (view) q.set('view', view);
  return `${boot.contextPath}/history?${q.toString()}`;
}

/** API path with repeated "item" parameters. */
export function withItems(path: string, itemIds: number[], params: Record<string, string | number | boolean | null | undefined>): string {
  const q = query(params);
  const items = itemIds.map((i) => `item=${i}`).join('&');
  return `${path}${q ? q + '&' : '?'}${items}`;
}

export function logSeverityLabel(s: number): string {
  const key = `logSeverity.${s}`;
  const text = t(key);
  return text === key ? String(s) : text;
}
