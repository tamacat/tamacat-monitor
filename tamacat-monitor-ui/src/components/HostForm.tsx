import { useEffect, useState } from 'react';
import AttributeEditor from '@cloudscape-design/components/attribute-editor';
import ColumnLayout from '@cloudscape-design/components/column-layout';
import FormField from '@cloudscape-design/components/form-field';
import Input from '@cloudscape-design/components/input';
import Multiselect from '@cloudscape-design/components/multiselect';
import SegmentedControl from '@cloudscape-design/components/segmented-control';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Textarea from '@cloudscape-design/components/textarea';
import Toggle from '@cloudscape-design/components/toggle';

import { api } from '../lib/api';
import { t } from '../lib/boot';
import { flatten, query, type MultiResult } from '../lib/zabbix';

export interface HostFormValue {
  host: string;
  name: string;
  description: string;
  status: number;
  groupIds: number[];
  tags: { tag: string; value: string }[];
  useIp: boolean;
  ip: string;
  dns: string;
  port: string;
}

export function emptyHost(): HostFormValue {
  return { host: '', name: '', description: '', status: 0, groupIds: [], tags: [], useIp: true, ip: '', dns: '', port: '10050' };
}

/** Request body for the host write API. */
export function hostBody(v: HostFormValue) {
  return {
    host: v.host,
    name: v.name,
    description: v.description,
    status: v.status,
    groupIds: v.groupIds,
    tags: v.tags.filter((x) => x.tag.trim() !== ''),
    interface: v.ip || v.dns ? { type: 1, useIp: v.useIp, ip: v.ip, dns: v.dns, port: v.port } : null,
  };
}

/** Fields of a host (create / edit). Host groups are those the user may use on the server. */
export function HostForm({ serverId, value, onChange, editing }: { serverId: number | null; value: HostFormValue; onChange: (v: HostFormValue) => void; editing?: boolean }) {
  const [groups, setGroups] = useState<{ groupId: number; name: string }[]>([]);
  const [loading, setLoading] = useState(false);
  const set = <K extends keyof HostFormValue>(k: K, v: HostFormValue[K]) => onChange({ ...value, [k]: v });

  useEffect(() => {
    if (serverId === null) return;
    setLoading(true);
    api.get<MultiResult<{ groupId: number; name: string }>>('/api/zabbix/hostgroups' + query({ limit: 500 }, [serverId]))
      .then((r) => setGroups(flatten(r)))
      .finally(() => setLoading(false));
  }, [serverId]);

  const options = groups.map((g) => ({ value: String(g.groupId), label: g.name }));

  return (
    <SpaceBetween size="m">
      <ColumnLayout columns={2}>
        <FormField label={t('field.hostName')} constraintText={editing ? t('host.hostFixed') : t('host.hostRule')}>
          <Input value={value.host} disabled={editing} onChange={(e) => set('host', e.detail.value)} />
        </FormField>
        <FormField label={t('field.visibleName')}>
          <Input value={value.name} placeholder={value.host} onChange={(e) => set('name', e.detail.value)} />
        </FormField>
      </ColumnLayout>
      <FormField label={t('field.hostGroups')} description={t('host.groupsHelp')}>
        <Multiselect
          statusType={loading ? 'loading' : 'finished'}
          filteringType="auto"
          options={options}
          selectedOptions={options.filter((o) => value.groupIds.includes(Number(o.value)))}
          onChange={(e) => set('groupIds', e.detail.selectedOptions.map((o) => Number(o.value)))}
          placeholder={t('user.assignments.chooseGroups')}
        />
      </FormField>
      <ColumnLayout columns={3}>
        <FormField label={t('field.connectTo')}>
          <SegmentedControl
            selectedId={value.useIp ? 'ip' : 'dns'}
            options={[{ id: 'ip', text: 'IP' }, { id: 'dns', text: 'DNS' }]}
            onChange={(e) => set('useIp', e.detail.selectedId === 'ip')}
          />
        </FormField>
        <FormField label={value.useIp ? t('field.ip') : t('field.dns')} description={t('host.agentInterface')}>
          <Input value={value.useIp ? value.ip : value.dns} onChange={(e) => set(value.useIp ? 'ip' : 'dns', e.detail.value)} />
        </FormField>
        <FormField label={t('field.port')}>
          <Input value={value.port} inputMode="numeric" onChange={(e) => set('port', e.detail.value)} />
        </FormField>
      </ColumnLayout>
      <FormField label={t('field.status')}>
        <Toggle checked={value.status === 0} onChange={(e) => set('status', e.detail.checked ? 0 : 1)}>
          {value.status === 0 ? t('host.status.0') : t('host.status.1')}
        </Toggle>
      </FormField>
      <FormField label={t('field.tags')}>
        <AttributeEditor
          items={value.tags}
          addButtonText={t('action.add')}
          removeButtonText={t('action.remove')}
          empty={t('host.noTags')}
          onAddButtonClick={() => set('tags', [...value.tags, { tag: '', value: '' }])}
          onRemoveButtonClick={(e) => set('tags', value.tags.filter((_, i) => i !== e.detail.itemIndex))}
          definition={[
            {
              label: t('field.tag'),
              control: (item, i) => <Input value={item.tag} onChange={(e) => set('tags', value.tags.map((x, j) => (j === i ? { ...x, tag: e.detail.value } : x)))} />,
            },
            {
              label: t('field.value'),
              control: (item, i) => <Input value={item.value} onChange={(e) => set('tags', value.tags.map((x, j) => (j === i ? { ...x, value: e.detail.value } : x)))} />,
            },
          ]}
        />
      </FormField>
      <FormField label={t('field.description')}>
        <Textarea rows={2} value={value.description} onChange={(e) => set('description', e.detail.value)} />
      </FormField>
    </SpaceBetween>
  );
}
