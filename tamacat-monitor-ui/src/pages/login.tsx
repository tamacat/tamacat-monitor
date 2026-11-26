import { useState } from 'react';
import Alert from '@cloudscape-design/components/alert';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Container from '@cloudscape-design/components/container';
import Form from '@cloudscape-design/components/form';
import FormField from '@cloudscape-design/components/form-field';
import Grid from '@cloudscape-design/components/grid';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import SpaceBetween from '@cloudscape-design/components/space-between';

import { api, ApiError, errorText, setCsrfToken } from '../lib/api';
import { boot, t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import { getPasskey, passkeysSupported } from '../lib/webauthn';

interface LoginResponse {
  user?: { mustChangePassword: boolean };
  csrfToken?: string;
  /** the password was right; a second step is due (TOTP / recovery code, or a passkey) */
  mfaRequired?: boolean;
  methods?: ('totp' | 'passkey')[];
}

const passkeysOn = () => boot.passkeys === true && passkeysSupported();

function LoginPage() {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [methods, setMethods] = useState<LoginResponse['methods'] | null>(null);
  const [code, setCode] = useState('');
  const mfa = methods !== null;
  const totp = methods?.includes('totp') ?? false;
  const passkeySecond = (methods?.includes('passkey') ?? false) && passkeysOn();

  const done = (r: LoginResponse) => {
    if (r.csrfToken) setCsrfToken(r.csrfToken);
    window.location.href = url(r.user?.mustChangePassword ? '/account' : '/');
  };

  const failed = (e: unknown) => {
    setError(errorText(e));
    setPassword('');
    setCode('');
    if (mfa && e instanceof ApiError && e.key === 'error.mfa.expired') setMethods(null);
    setBusy(false);
  };

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      if (mfa) {
        done(await api.post<LoginResponse>('/api/login/mfa', { code }));
        return;
      }
      const r = await api.post<LoginResponse>('/api/login', { loginId, password });
      setPassword('');
      if (r.mfaRequired) {
        setMethods(r.methods ?? ['totp']);
        setBusy(false);
        return;
      }
      done(r);
    } catch (e) {
      failed(e);
    }
  };

  /** Sign-in with a passkey alone, or the second step with one of the user's passkeys. */
  const withPasskey = async (second: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const base = second ? '/api/login/mfa/passkey' : '/api/login/passkey';
      const options = await api.post(`${base}/options`);
      const credential = await getPasskey(options);
      done(await api.post<LoginResponse>(base, { credential }));
    } catch (e) {
      failed(e);
    }
  };

  return (
    <Box padding={{ top: 'xxxl', horizontal: 'l' }}>
      <Grid gridDefinition={[{ colspan: { default: 12, s: 6, l: 4 }, offset: { default: 0, s: 3, l: 4 } }]}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <Container
            header={<Header variant="h1">{t('app.title')}</Header>}
            footer={!mfa && passkeysOn() && (
              <SpaceBetween size="xs">
                <Box textAlign="center"><Box color="text-body-secondary" variant="small">{t('login.or')}</Box></Box>
                <Button iconName="key" formAction="none" fullWidth loading={busy} onClick={() => void withPasskey(false)}>
                  {t('login.passkey')}
                </Button>
              </SpaceBetween>
            )}
          >
            <Form
              actions={
                <SpaceBetween direction="horizontal" size="xs">
                  {mfa && <Button variant="link" formAction="none" onClick={() => { setMethods(null); setError(null); }}>{t('login.back')}</Button>}
                  {(!mfa || totp) && (
                    <Button variant="primary" formAction="submit" loading={busy}>
                      {t('login.submit')}
                    </Button>
                  )}
                </SpaceBetween>
              }
            >
              <SpaceBetween size="l">
                {error && <Alert type="error">{error}</Alert>}
                {mfa && passkeySecond && (
                  <SpaceBetween size="xs">
                    <Box>{t(totp ? 'login.passkeySecondHelp' : 'login.passkeySecondOnly')}</Box>
                    <Button iconName="key" formAction="none" variant={totp ? 'normal' : 'primary'} loading={busy} onClick={() => void withPasskey(true)}>
                      {t('login.usePasskey')}
                    </Button>
                  </SpaceBetween>
                )}
                {mfa && totp && (
                  <FormField label={t('mfa.code')} description={t('mfa.codeHelp')}>
                    <Input value={code} onChange={(e) => setCode(e.detail.value)} autoComplete="one-time-code" inputMode="text" autoFocus={!passkeySecond} />
                  </FormField>
                )}
                {!mfa && (
                  <>
                    <FormField label={t('field.loginId')}>
                      <Input
                        value={loginId}
                        onChange={(e) => setLoginId(e.detail.value)}
                        autoComplete="username"
                        autoFocus
                        disableBrowserAutocorrect
                      />
                    </FormField>
                    <FormField label={t('field.password')}>
                      <Input
                        type="password"
                        value={password}
                        onChange={(e) => setPassword(e.detail.value)}
                        autoComplete="current-password"
                      />
                    </FormField>
                  </>
                )}
              </SpaceBetween>
            </Form>
          </Container>
          <Box margin={{ top: 'm' }} textAlign="center" variant="small" color="text-body-secondary">{t('app.notice')}</Box>
        </form>
      </Grid>
    </Box>
  );
}

mount(<LoginPage />);
