import { useEffect, useState } from 'react';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Checkbox from '@cloudscape-design/components/checkbox';
import ColumnLayout from '@cloudscape-design/components/column-layout';
import Container from '@cloudscape-design/components/container';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import Multiselect from '@cloudscape-design/components/multiselect';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Textarea from '@cloudscape-design/components/textarea';
import Toggle from '@cloudscape-design/components/toggle';

import { api } from '../lib/api';
import { t } from '../lib/boot';
import { flatten, label, query, type MultiResult } from '../lib/zabbix';

export interface ActionMessage {
  defaultMessage: boolean;
  subject: string;
  message: string;
  mediaTypeId: number | null;
  userGroupIds: number[];
  userIds: number[];
}

export interface ActionOperation {
  type: number;
  stepFrom: number;
  stepTo: number;
  escPeriod: string;
  message: ActionMessage;
}

export interface ActionCondition {
  type: number;
  operator: number;
  value: string;
  value2: string;
}

export interface ActionFormValue {
  name: string;
  status: number;
  escPeriod: string;
  pauseSuppressed: boolean;
  notifyIfCanceled: boolean;
  pauseSymptoms: boolean;
  evalType: number;
  conditions: ActionCondition[];
  operations: ActionOperation[];
  recoveryOperations: ActionOperation[];
  updateOperations: ActionOperation[];
}

interface Choice {
  id: number;
  name: string;
}

interface Choices {
  userGroups: Choice[];
  users: Choice[];
  mediaTypes: Choice[];
}

type Group = 'operations' | 'recoveryOperations' | 'updateOperations';

export function emptyAction(): ActionFormValue {
  return {
    name: '',
    status: 0,
    escPeriod: '1h',
    pauseSuppressed: true,
    notifyIfCanceled: true,
    pauseSymptoms: true,
    evalType: 0,
    conditions: [],
    operations: [],
    recoveryOperations: [],
    updateOperations: [],
  };
}

function newMessage(): ActionMessage {
  return { defaultMessage: true, subject: '', message: '', mediaTypeId: null, userGroupIds: [], userIds: [] };
}

function newOperation(type = 0): ActionOperation {
  return { type, stepFrom: 1, stepTo: 1, escPeriod: '0', message: newMessage() };
}

/** Condition types and their operators (ActionWriter). */
const CONDITION_OPERATORS: Record<number, number[]> = { 0: [0, 1], 1: [0, 1], 4: [0, 1, 5, 6], 25: [0, 1, 2, 3], 26: [0, 1, 2, 3] };
const CONDITION_TYPES = [0, 1, 4, 25, 26];

function ConditionRow({ c, groups, hosts, onChange, onRemove }: {
  c: ActionCondition;
  groups: Choice[];
  hosts: Choice[];
  onChange: (c: ActionCondition) => void;
  onRemove: () => void;
}) {
  const typeOptions = CONDITION_TYPES.map((x) => ({ value: String(x), label: label('conditionType', x) }));
  const opOptions = (CONDITION_OPERATORS[c.type] ?? [0]).map((x) => ({ value: String(x), label: label('operator', x) }));
  const choices = c.type === 0 ? groups : c.type === 1 ? hosts : c.type === 4 ? [0, 1, 2, 3, 4, 5].map((s) => ({ id: s, name: label('severity', s) })) : null;
  const valueOptions = choices?.map((x) => ({ value: String(x.id), label: x.name })) ?? [];
  return (
    <ColumnLayout columns={c.type === 26 ? 5 : 4}>
      <Select
        options={typeOptions}
        selectedOption={typeOptions.find((o) => Number(o.value) === c.type) ?? null}
        onChange={(e) => onChange({ type: Number(e.detail.selectedOption.value), operator: 0, value: '', value2: '' })}
      />
      {c.type === 26 && <Input value={c.value2} placeholder={t('actionForm.tagName')} onChange={(e) => onChange({ ...c, value2: e.detail.value })} />}
      <Select options={opOptions} selectedOption={opOptions.find((o) => Number(o.value) === c.operator) ?? null} onChange={(e) => onChange({ ...c, operator: Number(e.detail.selectedOption.value) })} />
      {choices ? (
        <Select
          filteringType="auto"
          options={valueOptions}
          selectedOption={valueOptions.find((o) => o.value === c.value) ?? null}
          onChange={(e) => onChange({ ...c, value: e.detail.selectedOption.value ?? '' })}
        />
      ) : (
        <Input value={c.value} onChange={(e) => onChange({ ...c, value: e.detail.value })} />
      )}
      <Button iconName="remove" variant="icon" ariaLabel={t('action.remove')} onClick={onRemove} />
    </ColumnLayout>
  );
}

function OperationEditor({ group, op, choices, onChange, onRemove }: {
  group: Group;
  op: ActionOperation;
  choices: Choices;
  onChange: (o: ActionOperation) => void;
  onRemove: () => void;
}) {
  const m = op.message;
  const setMessage = (patch: Partial<ActionMessage>) => onChange({ ...op, message: { ...m, ...patch } });
  const allType = group === 'recoveryOperations' ? 11 : group === 'updateOperations' ? 12 : null;
  const typeOptions = [0, ...(allType === null ? [] : [allType])].map((x) => ({ value: String(x), label: label('operationType', x) }));
  const groupOptions = choices.userGroups.map((g) => ({ value: String(g.id), label: g.name }));
  const userOptions = choices.users.map((u) => ({ value: String(u.id), label: u.name }));
  const mediaOptions = [{ value: '', label: t('action.allMedia') }, ...choices.mediaTypes.map((x) => ({ value: String(x.id), label: x.name }))];
  return (
    <Container
      header={
        <Header variant="h3" actions={<Button iconName="remove" variant="icon" ariaLabel={t('action.remove')} onClick={onRemove} />}>
          {label('operationType', op.type)}
        </Header>
      }
    >
      <SpaceBetween size="s">
        <ColumnLayout columns={group === 'operations' ? 4 : 2}>
          {allType !== null && (
            <FormField label={t('field.type')}>
              <Select options={typeOptions} selectedOption={typeOptions.find((o) => Number(o.value) === op.type) ?? null} onChange={(e) => onChange({ ...op, type: Number(e.detail.selectedOption.value) })} />
            </FormField>
          )}
          {/* separate children: ColumnLayout puts each child in its own column */}
          {group === 'operations' && (
            <FormField label={t('actionForm.stepFrom')}>
              <Input type="number" value={String(op.stepFrom)} onChange={(e) => onChange({ ...op, stepFrom: Number(e.detail.value) })} />
            </FormField>
          )}
          {group === 'operations' && (
            <FormField label={t('actionForm.stepTo')} constraintText={t('actionForm.stepToHelp')}>
              <Input type="number" value={String(op.stepTo)} onChange={(e) => onChange({ ...op, stepTo: Number(e.detail.value) })} />
            </FormField>
          )}
          {group === 'operations' && (
            <FormField label={t('actionForm.stepDuration')} constraintText={t('actionForm.stepDurationHelp')}>
              <Input value={op.escPeriod} onChange={(e) => onChange({ ...op, escPeriod: e.detail.value })} />
            </FormField>
          )}
          {op.type === 0 && (
            <FormField label={t('field.mediaType')}>
              <Select
                options={mediaOptions}
                selectedOption={mediaOptions.find((o) => o.value === (m.mediaTypeId === null ? '' : String(m.mediaTypeId))) ?? mediaOptions[0]}
                onChange={(e) => setMessage({ mediaTypeId: e.detail.selectedOption.value ? Number(e.detail.selectedOption.value) : null })}
              />
            </FormField>
          )}
        </ColumnLayout>
        {op.type === 0 && (
          <ColumnLayout columns={2}>
            <FormField label={t('actionForm.userGroups')}>
              <Multiselect
                filteringType="auto"
                options={groupOptions}
                selectedOptions={groupOptions.filter((o) => m.userGroupIds.includes(Number(o.value)))}
                onChange={(e) => setMessage({ userGroupIds: e.detail.selectedOptions.map((o) => Number(o.value)) })}
              />
            </FormField>
            <FormField label={t('actionForm.users')}>
              <Multiselect
                filteringType="auto"
                options={userOptions}
                selectedOptions={userOptions.filter((o) => m.userIds.includes(Number(o.value)))}
                onChange={(e) => setMessage({ userIds: e.detail.selectedOptions.map((o) => Number(o.value)) })}
              />
            </FormField>
          </ColumnLayout>
        )}
        <Checkbox checked={m.defaultMessage} onChange={(e) => setMessage({ defaultMessage: e.detail.checked })}>
          {t('actionForm.defaultMessage')}
        </Checkbox>
        {!m.defaultMessage && (
          <SpaceBetween size="xs">
            <FormField label={t('field.subject')}>
              <Input value={m.subject} onChange={(e) => setMessage({ subject: e.detail.value })} />
            </FormField>
            <FormField label={t('actionForm.message')}>
              <Textarea rows={3} value={m.message} onChange={(e) => setMessage({ message: e.detail.value })} />
            </FormField>
          </SpaceBetween>
        )}
      </SpaceBetween>
    </Container>
  );
}

/** Fields of a trigger action (create / edit). Recipients and media types are chosen from the server. */
export function ActionForm({ serverId, value, onChange, v70 }: { serverId: number; value: ActionFormValue; onChange: (v: ActionFormValue) => void; v70: boolean }) {
  const [choices, setChoices] = useState<Choices>({ userGroups: [], users: [], mediaTypes: [] });
  const [groups, setGroups] = useState<Choice[]>([]);
  const [hosts, setHosts] = useState<Choice[]>([]);
  const set = <K extends keyof ActionFormValue>(k: K, v: ActionFormValue[K]) => onChange({ ...value, [k]: v });

  useEffect(() => {
    api.get<Choices>(`/api/zabbix/servers/${serverId}/action-choices`).then(setChoices);
    api.get<MultiResult<{ groupId: number; name: string }>>('/api/zabbix/hostgroups' + query({ limit: 500 }, [serverId]))
      .then((r) => setGroups(flatten(r).map((g) => ({ id: g.groupId, name: g.name }))));
    api.get<MultiResult<{ hostId: number; name: string }>>('/api/zabbix/hosts' + query({ limit: 500 }, [serverId]))
      .then((r) => setHosts(flatten(r).map((h) => ({ id: h.hostId, name: h.name }))));
  }, [serverId]);

  const evalOptions = [0, 1, 2].map((x) => ({ value: String(x), label: label('evalType', x) }));

  const opsSection = (group: Group, title: string) => (
    <SpaceBetween size="xs">
      <Header
        variant="h3"
        actions={<Button iconName="add-plus" onClick={() => set(group, [...value[group], newOperation()])}>{t('action.add')}</Button>}
      >
        {title}
      </Header>
      {value[group].length === 0 && <Box color="text-body-secondary">{t('actionForm.noOperations')}</Box>}
      {value[group].map((op, i) => (
        <OperationEditor
          key={i}
          group={group}
          op={op}
          choices={choices}
          onChange={(o) => set(group, value[group].map((x, j) => (j === i ? o : x)))}
          onRemove={() => set(group, value[group].filter((_, j) => j !== i))}
        />
      ))}
    </SpaceBetween>
  );

  return (
    <SpaceBetween size="l">
      <ColumnLayout columns={2}>
        <FormField label={t('field.name')}>
          <Input value={value.name} onChange={(e) => set('name', e.detail.value)} />
        </FormField>
        <FormField label={t('field.escPeriod')} constraintText={t('actionForm.escPeriodHelp')}>
          <Input value={value.escPeriod} onChange={(e) => set('escPeriod', e.detail.value)} />
        </FormField>
      </ColumnLayout>
      <SpaceBetween size="xxs">
        <Toggle checked={value.status === 0} onChange={(e) => set('status', e.detail.checked ? 0 : 1)}>
          {value.status === 0 ? t('status.enabled') : t('status.disabled')}
        </Toggle>
        <Checkbox checked={value.pauseSuppressed} onChange={(e) => set('pauseSuppressed', e.detail.checked)}>{t('actionForm.pauseSuppressed')}</Checkbox>
        {v70 && <Checkbox checked={value.pauseSymptoms} onChange={(e) => set('pauseSymptoms', e.detail.checked)}>{t('actionForm.pauseSymptoms')}</Checkbox>}
        <Checkbox checked={value.notifyIfCanceled} onChange={(e) => set('notifyIfCanceled', e.detail.checked)}>{t('actionForm.notifyIfCanceled')}</Checkbox>
      </SpaceBetween>
      <SpaceBetween size="xs">
        <Header
          variant="h3"
          actions={
            <SpaceBetween direction="horizontal" size="xs">
              <Select options={evalOptions} selectedOption={evalOptions.find((o) => Number(o.value) === value.evalType) ?? evalOptions[0]} onChange={(e) => set('evalType', Number(e.detail.selectedOption.value))} />
              <Button iconName="add-plus" onClick={() => set('conditions', [...value.conditions, { type: 0, operator: 0, value: '', value2: '' }])}>{t('action.add')}</Button>
            </SpaceBetween>
          }
        >
          {t('action.conditions')}
        </Header>
        {value.conditions.length === 0 && <Box color="text-body-secondary">{t('actionForm.noConditions')}</Box>}
        {value.conditions.map((c, i) => (
          <ConditionRow
            key={i}
            c={c}
            groups={groups}
            hosts={hosts}
            onChange={(n) => set('conditions', value.conditions.map((x, j) => (j === i ? n : x)))}
            onRemove={() => set('conditions', value.conditions.filter((_, j) => j !== i))}
          />
        ))}
      </SpaceBetween>
      {opsSection('operations', t('action.operations'))}
      {opsSection('recoveryOperations', t('actionForm.recoveryOperations'))}
      {opsSection('updateOperations', t('actionForm.updateOperations'))}
    </SpaceBetween>
  );
}
