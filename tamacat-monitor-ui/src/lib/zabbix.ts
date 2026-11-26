import { api } from './api';
import { t, url } from './boot';

// ---- API types (P2 read API) ----

export interface ServerRef {
  serverId: number;
  name: string;
  zabbixVersion: string;
  admin: boolean;
  /** the server accepts writes (write account, not read-only, supported version) */
  writable: boolean;
}

export interface ServerResult<T> {
  serverId: number;
  serverName: string;
  zabbixVersion: string;
  error?: string;
  items: T[];
}

export interface MultiResult<T> {
  results: ServerResult<T>[];
}

/** A row of a multi-server result with its server. */
export type WithServer<T> = T & { serverId: number; serverName: string };

export function flatten<T>(r: MultiResult<T>): WithServer<T>[] {
  return r.results.flatMap((s) => s.items.map((i) => ({ ...i, serverId: s.serverId, serverName: s.serverName })));
}

export function failedServers<T>(r: MultiResult<T>): { serverName: string; error: string }[] {
  return r.results.filter((s) => s.error).map((s) => ({ serverName: s.serverName, error: s.error! }));
}

/** Servers whose answer reached the row limit (more rows may exist). */
export function truncatedServers<T>(r: MultiResult<T>, limit: number): string[] {
  return r.results.filter((s) => !s.error && s.items.length >= limit).map((s) => s.serverName);
}

export function hostHref(serverId: number, hostId: number): string {
  return url(`/hosts?server=${serverId}&id=${hostId}`);
}

export function itemHref(serverId: number, itemId: number): string {
  return url(`/items?server=${serverId}&id=${itemId}`);
}

export function actionHref(serverId: number, actionId: number): string {
  return url(`/actions?server=${serverId}&id=${actionId}`);
}

export function loadServers(): Promise<ServerRef[]> {
  return api.get<ServerRef[]>('/api/zabbix/servers');
}

/** Query string with repeated "server" parameters; empty values are left out. */
export function query(params: Record<string, string | number | boolean | null | undefined>, servers: number[] = []): string {
  const q = new URLSearchParams();
  servers.forEach((s) => q.append('server', String(s)));
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== '') q.append(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

// ---- labels ----

export const SEVERITIES = [0, 1, 2, 3, 4, 5];

export function severityLabel(s: number): string {
  return t(`severity.${s}`);
}

/** Cloudscape status for a severity. */
export function severityStatus(s: number): 'info' | 'warning' | 'error' | 'stopped' {
  if (s >= 4) return 'error';
  if (s >= 2) return 'warning';
  if (s === 1) return 'info';
  return 'stopped';
}

export function label(group: string, value: number | string): string {
  const key = `${group}.${value}`;
  const text = t(key);
  return text === key ? String(value) : text;
}

export function duration(fromIso: string): string {
  const sec = Math.max(0, Math.floor((Date.now() - new Date(fromIso).getTime()) / 1000));
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}
