import { useEffect, useState } from 'react';
import Alert from '@cloudscape-design/components/alert';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import Modal from '@cloudscape-design/components/modal';
import SpaceBetween from '@cloudscape-design/components/space-between';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Table from '@cloudscape-design/components/table';

import { api, errorText, fieldError } from '../lib/api';
import { formatDateTime, t } from '../lib/boot';
import { createPasskey, passkeysSupported } from '../lib/webauthn';

interface Passkey {
  passkeyId: number;
  name: string;
  synced: boolean;
  createdAt: string;
  lastUsedAt?: string;
}

type Dialog = { kind: 'add' } | { kind: 'rename'; passkey: Passkey } | { kind: 'delete'; passkey: Passkey };

/**
 * Passkeys of the own account (T-801): add (password, then the browser / device dialog), rename, delete
 * (password). A passkey signs in alone, and is also accepted as the second step after the password.
 */
export function PasskeySettings({ onChange }: { onChange: (message: string) => void }) {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [list, setList] = useState<Passkey[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const r = await api.get<{ enabled: boolean; passkeys: Passkey[] }>('/api/me/passkeys');
      setEnabled(r.enabled);
      setList(r.passkeys);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    void load();
  }, []);

  const open = (d: Dialog) => {
    setName(d.kind === 'rename' ? d.passkey.name : d.kind === 'add' ? t('passkey.defaultName', { n: list.length + 1 }) : '');
    setPassword('');
    setError(null);
    setDialog(d);
  };

  const run = async (fn: () => Promise<string>) => {
    setBusy(true);
    setError(null);
    try {
      const message = await fn();
      setDialog(null);
      onChange(message);
      await load();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
      setPassword('');
    }
  };

  const submit = () => {
    if (!dialog) return;
    if (dialog.kind === 'add') {
      void run(async () => {
        const options = await api.post('/api/me/passkeys/options', { password });
        const credential = await createPasskey(options);
        await api.post('/api/me/passkeys', { name, credential });
        return t('passkey.added', { name });
      });
    } else if (dialog.kind === 'rename') {
      void run(async () => {
        await api.put(`/api/me/passkeys/${dialog.passkey.passkeyId}`, { name });
        return t('passkey.renamed', { name });
      });
    } else {
      void run(async () => {
        await api.post(`/api/me/passkeys/${dialog.passkey.passkeyId}/delete`, { password });
        return t('passkey.deleted', { name: dialog.passkey.name });
      });
    }
  };

  if (enabled === false) {
    return null; // not configured on this server (webauthn.rp-id)
  }

  return (
    <>
      <Table
        header={
          <Header
            variant="h2"
            counter={`(${list.length})`}
            description={t('passkey.help')}
            actions={<Button disabled={!passkeysSupported()} onClick={() => open({ kind: 'add' })}>{t('passkey.add')}</Button>}
          >
            {t('passkey.title')}
          </Header>
        }
        items={list}
        loading={loading}
        loadingText={t('common.loading')}
        trackBy="passkeyId"
        wrapLines
        empty={<Box textAlign="center" color="text-body-secondary">{passkeysSupported() ? t('passkey.empty') : t('error.passkey.unsupported')}</Box>}
        columnDefinitions={[
          { id: 'name', header: t('field.name'), cell: (p) => p.name, isRowHeader: true },
          { id: 'synced', header: t('passkey.synced'), cell: (p) => (p.synced ? <StatusIndicator type="success">{t('common.yes')}</StatusIndicator> : t('common.no')) },
          { id: 'created', header: t('passkey.created'), cell: (p) => formatDateTime(p.createdAt) },
          { id: 'used', header: t('passkey.lastUsed'), cell: (p) => (p.lastUsedAt ? formatDateTime(p.lastUsedAt) : t('passkey.never')) },
          {
            id: 'ops',
            header: '',
            cell: (p) => (
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="inline-link" onClick={() => open({ kind: 'rename', passkey: p })}>{t('passkey.rename')}</Button>
                <Button variant="inline-link" onClick={() => open({ kind: 'delete', passkey: p })}>{t('action.delete')}</Button>
              </SpaceBetween>
            ),
          },
        ]}
      />
      {dialog && (
        <Modal
          visible
          onDismiss={() => setDialog(null)}
          header={dialog.kind === 'add' ? t('passkey.add') : dialog.kind === 'rename' ? t('passkey.rename') : t('passkey.deleteTitle')}
          footer={
            <Box float="right">
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="link" onClick={() => setDialog(null)}>{t('action.cancel')}</Button>
                <Button variant="primary" loading={busy} onClick={submit}>
                  {dialog.kind === 'add' ? t('passkey.create') : dialog.kind === 'rename' ? t('action.save') : t('action.delete')}
                </Button>
              </SpaceBetween>
            </Box>
          }
        >
          <form onSubmit={(e) => { e.preventDefault(); submit(); }}>
            <SpaceBetween size="m">
              {error !== null && !fieldError(error, 'password') && !fieldError(error, 'name') && <Alert type="error">{errorText(error)}</Alert>}
              {dialog.kind === 'delete' && <Box>{t('passkey.deleteConfirm', { name: dialog.passkey.name })}</Box>}
              {dialog.kind !== 'delete' && (
                <FormField label={t('field.name')} description={t('passkey.nameHelp')} errorText={fieldError(error, 'name')}>
                  <Input value={name} onChange={(e) => setName(e.detail.value)} autoFocus />
                </FormField>
              )}
              {dialog.kind !== 'rename' && (
                <FormField label={t('field.currentPassword')} errorText={fieldError(error, 'password')}>
                  <Input type="password" value={password} onChange={(e) => setPassword(e.detail.value)} autoComplete="current-password" />
                </FormField>
              )}
              {dialog.kind === 'add' && <Box variant="small" color="text-body-secondary">{t('passkey.addHelp')}</Box>}
            </SpaceBetween>
          </form>
        </Modal>
      )}
    </>
  );
}
