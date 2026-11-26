import { useEffect, useState, type ReactNode } from 'react';
import Alert from '@cloudscape-design/components/alert';
import Button from '@cloudscape-design/components/button';
import Input from '@cloudscape-design/components/input';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Multiselect from '@cloudscape-design/components/multiselect';
import Select from '@cloudscape-design/components/select';

import { errorText } from '../lib/api';
import { t } from '../lib/boot';
import { loadServers, type ServerRef } from '../lib/zabbix';

/** Accessible Zabbix servers, loaded once per page. */
export function useServers(filter?: (s: ServerRef) => boolean): { servers: ServerRef[]; loading: boolean; error: string | null } {
  const [servers, setServers] = useState<ServerRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    loadServers()
      .then((list) => setServers(filter ? list.filter(filter) : list))
      .catch((e) => setError(errorText(e)))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return { servers, loading, error };
}

const options = (servers: ServerRef[]) =>
  servers.map((s) => ({ value: String(s.serverId), label: s.name, description: `Zabbix ${s.zabbixVersion}` }));

/** Several servers; none selected means all. */
export function ServerMultiselect({ servers, loading, selected, onChange }: { servers: ServerRef[]; loading: boolean; selected: number[]; onChange: (ids: number[]) => void }) {
  return (
    <Multiselect
      statusType={loading ? 'loading' : 'finished'}
      options={options(servers)}
      selectedOptions={options(servers).filter((o) => selected.includes(Number(o.value)))}
      onChange={(e) => onChange(e.detail.selectedOptions.map((o) => Number(o.value)))}
      placeholder={t('filter.allServers')}
      empty={t('filter.noServer')}
      hideTokens
      inlineTokens
    />
  );
}

/** Exactly one server. */
export function ServerSelect({ servers, loading, selected, onChange }: { servers: ServerRef[]; loading: boolean; selected: number | null; onChange: (id: number) => void }) {
  const opts = options(servers);
  return (
    <Select
      statusType={loading ? 'loading' : 'finished'}
      options={opts}
      selectedOption={opts.find((o) => Number(o.value) === selected) ?? null}
      onChange={(e) => onChange(Number(e.detail.selectedOption.value))}
      placeholder={t('filter.chooseServer')}
      empty={t('filter.noServer')}
    />
  );
}

/** Errors of individual servers in a multi-server result, and servers whose rows reached the limit. */
export function ServerErrors({ failed, truncated = [], limit }: { failed: { serverName: string; error: string }[]; truncated?: string[]; limit?: number }) {
  if (failed.length === 0 && truncated.length === 0) return null;
  return (
    <SpaceBetween size="xs">
      {failed.length > 0 && (
        <Alert type="warning" header={t('filter.partialResult')}>
          {failed.map((f) => `${f.serverName}: ${t(f.error)}`).join(' / ')}
        </Alert>
      )}
      {truncated.length > 0 && (
        <Alert type="info">{t('table.limitReached', { servers: truncated.join(', '), limit: limit ?? '' })}</Alert>
      )}
    </SpaceBetween>
  );
}

/**
 * Conditions of the rows fetched from the Zabbix servers (servers and a keyword searched on the server),
 * shown above the table filter, which then narrows the fetched rows in the browser.
 */
export function FetchToolbar({ servers, serversLoading, selected, onSelected, text, onText, placeholder, loading, onFetch, children }: {
  servers: ServerRef[];
  serversLoading: boolean;
  /** several servers; omit for single-server pages that put their own select in children */
  selected?: number[];
  onSelected?: (ids: number[]) => void;
  text?: string;
  onText?: (v: string) => void;
  placeholder?: string;
  loading: boolean;
  onFetch: () => void;
  children?: ReactNode;
}) {
  return (
    <form onSubmit={(e) => { e.preventDefault(); onFetch(); }}>
      <SpaceBetween direction="horizontal" size="xs" alignItems="center">
        {selected && onSelected && <ServerMultiselect servers={servers} loading={serversLoading} selected={selected} onChange={onSelected} />}
        {children}
        {onText && <Input type="search" value={text ?? ''} placeholder={placeholder} ariaLabel={placeholder} onChange={(e) => onText(e.detail.value)} />}
        <Button formAction="submit" iconName="refresh" loading={loading}>{t('table.fetch')}</Button>
      </SpaceBetween>
    </form>
  );
}
