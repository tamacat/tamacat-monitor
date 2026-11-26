import { useEffect, useState } from 'react';
import AttributeEditor from '@cloudscape-design/components/attribute-editor';
import ColumnLayout from '@cloudscape-design/components/column-layout';
import FormField from '@cloudscape-design/components/form-field';
import Input from '@cloudscape-design/components/input';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Textarea from '@cloudscape-design/components/textarea';
import Toggle from '@cloudscape-design/components/toggle';

import { api } from '../lib/api';
import { t } from '../lib/boot';
import { flatten, label, query, type MultiResult } from '../lib/zabbix';

export interface ItemFormValue {
  hostId: number | null;
  name: string;
  key: string;
  type: number;
  valueType: number;
  delay: string;
  history: string;
  trends: string;
  units: string;
  description: string;
  status: number;
  tags: { tag: string; value: string }[];
}

export function emptyItem(hostId: number | null): ItemFormValue {
  return { hostId, name: '', key: '', type: 0, valueType: 3, delay: '1m', history: '31d', trends: '365d', units: '', description: '', status: 0, tags: [] };
}

/** Item types tamacat-monitor can create (ItemWriter.TYPES) and the value types of Zabbix. */
const TYPES = [0, 7, 2];
const VALUE_TYPES = [3, 0, 1, 4, 2];

export function itemBody(v: ItemFormValue) {
  const numeric = v.valueType === 0 || v.valueType === 3;
  return {
    hostId: v.hostId,
    name: v.name,
    key: v.key,
    type: v.type,
    valueType: v.valueType,
    delay: v.type === 2 ? '0' : v.delay,
    history: v.history,
    trends: numeric ? v.trends : '0',
    units: numeric ? v.units : '',
    description: v.description,
    status: v.status,
    tags: v.tags.filter((x) => x.tag.trim() !== ''),
  };
}

/** Fields of an item (create / edit). On edit, the host, key, type and type of information are fixed. */
export function ItemForm({ serverId, value, onChange, editing }: { serverId: number | null; value: ItemFormValue; onChange: (v: ItemFormValue) => void; editing?: boolean }) {
  const [hosts, setHosts] = useState<{ hostId: number; name: string }[]>([]);
  const [loading, setLoading] = useState(false);
  const set = <K extends keyof ItemFormValue>(k: K, v: ItemFormValue[K]) => onChange({ ...value, [k]: v });
  const numeric = value.valueType === 0 || value.valueType === 3;

  useEffect(() => {
    if (serverId === null || editing) return;
    setLoading(true);
    api.get<MultiResult<{ hostId: number; name: string }>>('/api/zabbix/hosts' + query({ limit: 500 }, [serverId]))
      .then((r) => setHosts(flatten(r)))
      .finally(() => setLoading(false));
  }, [serverId, editing]);

  const hostOptions = hosts.map((h) => ({ value: String(h.hostId), label: h.name }));
  const typeOptions = TYPES.map((x) => ({ value: String(x), label: label('itemType', x) }));
  const valueTypeOptions = VALUE_TYPES.map((x) => ({ value: String(x), label: t(`valueType.${x}`) }));

  return (
    <SpaceBetween size="m">
      {!editing && (
        <FormField label={t('field.host')}>
          <Select
            statusType={loading ? 'loading' : 'finished'}
            filteringType="auto"
            options={hostOptions}
            selectedOption={hostOptions.find((o) => Number(o.value) === value.hostId) ?? null}
            onChange={(e) => set('hostId', Number(e.detail.selectedOption.value))}
          />
        </FormField>
      )}
      <ColumnLayout columns={2}>
        <FormField label={t('field.name')}>
          <Input value={value.name} onChange={(e) => set('name', e.detail.value)} />
        </FormField>
        <FormField label={t('field.key')} constraintText={editing ? t('item.keyFixed') : undefined}>
          <Input value={value.key} disabled={editing} onChange={(e) => set('key', e.detail.value)} />
        </FormField>
        <FormField label={t('field.type')}>
          <Select disabled={editing} options={typeOptions} selectedOption={typeOptions.find((o) => Number(o.value) === value.type) ?? null} onChange={(e) => set('type', Number(e.detail.selectedOption.value))} />
        </FormField>
        <FormField label={t('field.valueType')}>
          <Select disabled={editing} options={valueTypeOptions} selectedOption={valueTypeOptions.find((o) => Number(o.value) === value.valueType) ?? null} onChange={(e) => set('valueType', Number(e.detail.selectedOption.value))} />
        </FormField>
      </ColumnLayout>
      <ColumnLayout columns={4}>
        <FormField label={t('field.interval')} constraintText={t('item.delayHelp')}>
          <Input value={value.type === 2 ? '' : value.delay} disabled={value.type === 2} onChange={(e) => set('delay', e.detail.value)} />
        </FormField>
        <FormField label={t('field.history')} constraintText={t('item.historyHelp')}>
          <Input value={value.history} onChange={(e) => set('history', e.detail.value)} />
        </FormField>
        <FormField label={t('field.trends')} constraintText={t('item.trendsHelp')}>
          <Input value={numeric ? value.trends : '0'} disabled={!numeric} onChange={(e) => set('trends', e.detail.value)} />
        </FormField>
        <FormField label={t('field.units')}>
          <Input value={numeric ? value.units : ''} disabled={!numeric} onChange={(e) => set('units', e.detail.value)} />
        </FormField>
      </ColumnLayout>
      <FormField label={t('field.status')}>
        <Toggle checked={value.status === 0} onChange={(e) => set('status', e.detail.checked ? 0 : 1)}>
          {value.status === 0 ? t('item.status.0') : t('item.status.1')}
        </Toggle>
      </FormField>
      <FormField label={t('field.tags')}>
        <AttributeEditor
          items={value.tags}
          addButtonText={t('action.add')}
          removeButtonText={t('action.remove')}
          empty={t('item.noTags')}
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
