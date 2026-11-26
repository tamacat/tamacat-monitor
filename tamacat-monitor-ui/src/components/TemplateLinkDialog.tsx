import { useEffect, useState } from 'react';
import Alert from '@cloudscape-design/components/alert';
import FormField from '@cloudscape-design/components/form-field';
import RadioGroup from '@cloudscape-design/components/radio-group';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';

import { api } from '../lib/api';
import { t } from '../lib/boot';
import { query } from '../lib/zabbix';
import { WriteDialog, type WriteResponse } from './WriteDialog';

interface TemplateRow {
  templateId: number;
  host: string;
  name: string;
  itemCount: number;
  reason: string | null;
}

interface Linked {
  templateId: number;
  host: string;
  name: string;
}

type Mode = 'link' | 'unlink' | 'clear';

/**
 * Link a template to a host, unlink it (its items, triggers and graphs stay on the host), or unlink and
 * clear (they are deleted). Templates tamacat-monitor cannot link are listed but disabled, with the reason.
 */
export function TemplateLinkDialog({ serverId, hostId, host, onClose, onDone }: {
  serverId: number;
  hostId: number;
  host: string;
  onClose: () => void;
  onDone: (r: WriteResponse, mode: Mode, templateName: string) => void;
}) {
  const [linked, setLinked] = useState<Linked[]>([]);
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [mode, setMode] = useState<Mode>('link');
  const [templateId, setTemplateId] = useState<number | null>(null);
  const [selected, setSelected] = useState<TemplateRow | null>(null);

  useEffect(() => {
    api.get<Linked[]>(`/api/zabbix/servers/${serverId}/hosts/${hostId}/templates`).then(setLinked);
  }, [serverId, hostId]);

  // a server can have hundreds of templates: they are searched on the server as the user types
  const search = (text: string) => {
    setLoading(true);
    api.get<TemplateRow[]>(`/api/zabbix/servers/${serverId}/templates` + query({ q: text, limit: 50 }))
      .then(setTemplates)
      .finally(() => setLoading(false));
  };
  useEffect(() => search(''), [serverId]);

  const linkedIds = new Set(linked.map((l) => l.templateId));
  const linkOptions = templates
    .filter((x) => !linkedIds.has(x.templateId))
    .map((x) => ({
      value: String(x.templateId),
      label: x.name,
      description: x.reason ? t(x.reason) : t('template.items', { n: x.itemCount }),
      disabled: x.reason !== null,
    }));
  const unlinkOptions = linked.map((x) => ({ value: String(x.templateId), label: x.name }));
  const options = mode === 'link' ? linkOptions : unlinkOptions;
  const selectedName = (mode === 'link' ? selected?.name : linked.find((x) => x.templateId === templateId)?.name) ?? '';

  return (
    <WriteDialog
      title={t('template.title', { host })}
      formKey={JSON.stringify({ mode, templateId })}
      danger={mode === 'clear'}
      run={(preview) => {
        if (templateId === null) return Promise.reject(new Error('no template'));
        return mode === 'link'
          ? api.post(`/api/zabbix/servers/${serverId}/hosts/${hostId}/templates`, { templateId, expectedHost: host, preview })
          : api.del(`/api/zabbix/servers/${serverId}/hosts/${hostId}/templates/${templateId}` +
              query({ expectedHost: host, clear: mode === 'clear', preview }));
      }}
      onClose={onClose}
      onDone={(r) => onDone(r, mode, selectedName)}
    >
      <SpaceBetween size="m">
        <FormField label={t('template.operation')}>
          <RadioGroup
            value={mode}
            onChange={(e) => { setMode(e.detail.value as Mode); setTemplateId(null); }}
            items={[
              { value: 'link', label: t('template.link'), description: t('template.linkHelp') },
              { value: 'unlink', label: t('template.unlink'), description: t('template.unlinkHelp'), disabled: linked.length === 0 },
              { value: 'clear', label: t('template.unlinkClear'), description: t('template.unlinkClearHelp'), disabled: linked.length === 0 },
            ]}
          />
        </FormField>
        <FormField label={t('template.template')} description={mode === 'link' ? t('template.limits') : undefined}>
          <Select
            statusType={mode === 'link' && loading ? 'loading' : 'finished'}
            filteringType={mode === 'link' ? 'manual' : 'auto'}
            onLoadItems={(e) => { if (mode === 'link') search(e.detail.filteringText); }}
            options={options}
            selectedOption={templateId === null ? null
              : options.find((o) => Number(o.value) === templateId) ?? (selected ? { value: String(selected.templateId), label: selected.name } : null)}
            onChange={(e) => {
              const id = Number(e.detail.selectedOption.value);
              setTemplateId(id);
              setSelected(templates.find((x) => x.templateId === id) ?? null);
            }}
            placeholder={t('template.choose')}
          />
        </FormField>
        {mode === 'clear' && <Alert type="warning">{t('template.clearWarning')}</Alert>}
      </SpaceBetween>
    </WriteDialog>
  );
}
