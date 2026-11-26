import { useEffect, useState } from 'react';
import qrcode from 'qrcode-generator';
import Alert from '@cloudscape-design/components/alert';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import ColumnLayout from '@cloudscape-design/components/column-layout';
import Container from '@cloudscape-design/components/container';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import SpaceBetween from '@cloudscape-design/components/space-between';
import StatusIndicator from '@cloudscape-design/components/status-indicator';

import { api, errorText, fieldError } from '../lib/api';
import { t } from '../lib/boot';

interface Status {
  enabled: boolean;
  recoveryCodesLeft: number;
}

/** QR code of the otpauth URI as an inline SVG (data URL: allowed by the CSP img-src 'self' data:). */
function qrDataUrl(text: string): string {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  return qr.createDataURL(4, 8);
}

/**
 * Two-factor authentication of the own account (T-602): set up with an authenticator app (QR code or
 * key), confirm with a code and the password, keep the recovery codes; disable with password and a code.
 */
export function MfaSettings({ onChange }: { onChange: (message: string) => void }) {
  const [status, setStatus] = useState<Status | null>(null);
  const [setup, setSetup] = useState<{ secret: string; uri: string } | null>(null);
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [codes, setCodes] = useState<string[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api.get<Status>('/api/me/mfa').then(setStatus);
  useEffect(() => {
    void load();
  }, []);

  const run = async (f: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await f();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
      setPassword('');
      setCode('');
    }
  };

  const start = () => run(async () => {
    setCodes(null);
    setSetup(await api.post<{ secret: string; uri: string }>('/api/me/mfa/setup', {}));
  });
  const enable = () => run(async () => {
    const r = await api.post<{ recoveryCodes: string[] }>('/api/me/mfa/enable', { password, code });
    setCodes(r.recoveryCodes);
    setSetup(null);
    await load();
    onChange(t('mfa.enabled'));
  });
  const disable = () => run(async () => {
    await api.post('/api/me/mfa/disable', { password, code });
    await load();
    onChange(t('mfa.disabled'));
  });

  const credentials = (
    <ColumnLayout columns={2}>
      <FormField label={t('field.currentPassword')} errorText={fieldError(error, 'password')}>
        <Input type="password" value={password} onChange={(e) => setPassword(e.detail.value)} autoComplete="current-password" />
      </FormField>
      <FormField label={t('mfa.code')} errorText={fieldError(error, 'code')}>
        <Input value={code} onChange={(e) => setCode(e.detail.value)} autoComplete="one-time-code" />
      </FormField>
    </ColumnLayout>
  );
  const otherError = error !== null && !fieldError(error, 'password') && !fieldError(error, 'code');

  return (
    <Container header={<Header variant="h2" description={t('mfa.help')}>{t('mfa.title')}</Header>}>
      <SpaceBetween size="m">
        {status && (
          status.enabled
            ? <StatusIndicator type="success">{t('mfa.on', { n: status.recoveryCodesLeft })}</StatusIndicator>
            : <StatusIndicator type="stopped">{t('mfa.off')}</StatusIndicator>
        )}
        {otherError && <Alert type="error">{errorText(error)}</Alert>}
        {codes && (
          <Alert type="warning" header={t('mfa.recoveryTitle')}>
            <SpaceBetween size="xs">
              <Box>{t('mfa.recoveryHelp')}</Box>
              <Box variant="code">{codes.join('  ')}</Box>
            </SpaceBetween>
          </Alert>
        )}
        {status && !status.enabled && !setup && <Button onClick={() => void start()} loading={busy}>{t('mfa.setup')}</Button>}
        {setup && (
          <SpaceBetween size="m">
            <ColumnLayout columns={2}>
              <img src={qrDataUrl(setup.uri)} alt={t('mfa.qr')} width={200} height={200} />
              <SpaceBetween size="xs">
                <Box>{t('mfa.scan')}</Box>
                <Box variant="small">{t('mfa.key')}</Box>
                <Box variant="code">{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</Box>
              </SpaceBetween>
            </ColumnLayout>
            {credentials}
            <SpaceBetween direction="horizontal" size="xs">
              <Button variant="primary" onClick={() => void enable()} loading={busy} disabled={!password || !code}>{t('mfa.enable')}</Button>
              <Button variant="link" onClick={() => setSetup(null)}>{t('action.cancel')}</Button>
            </SpaceBetween>
          </SpaceBetween>
        )}
        {status?.enabled && (
          <SpaceBetween size="m">
            <Box variant="small">{t('mfa.disableHelp')}</Box>
            {credentials}
            <Button onClick={() => void disable()} loading={busy} disabled={!password || !code}>{t('mfa.disable')}</Button>
          </SpaceBetween>
        )}
      </SpaceBetween>
    </Container>
  );
}
