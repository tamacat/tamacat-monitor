import { useState } from 'react';
import Alert from '@cloudscape-design/components/alert';
import Button from '@cloudscape-design/components/button';
import Container from '@cloudscape-design/components/container';
import ContentLayout from '@cloudscape-design/components/content-layout';
import Form from '@cloudscape-design/components/form';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import KeyValuePairs from '@cloudscape-design/components/key-value-pairs';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';

import { MfaSettings } from '../components/MfaSettings';
import { PasskeySettings } from '../components/PasskeySettings';
import { api, errorText, fieldError } from '../lib/api';
import { boot, t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import { Shell, useFlash } from '../shell/Shell';

const LOCALES = [
  { value: 'en', label: 'English' },
  { value: 'ja', label: '日本語' },
];

function AccountPage() {
  const user = boot.user!;
  const forced = user.mustChangePassword;
  const [flash, push] = useFlash();

  const [locale, setLocale] = useState(boot.locale);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [pwError, setPwError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const saveLocale = async () => {
    try {
      await api.put('/api/me/locale', { locale });
      window.location.reload();
    } catch (e) {
      push('error', errorText(e));
    }
  };

  const changePassword = async () => {
    setPwError(null);
    if (next !== confirm) {
      setPwError({ confirm: true });
      return;
    }
    setBusy(true);
    try {
      await api.post('/api/me/password', { currentPassword: current, newPassword: next });
      setCurrent('');
      setNext('');
      setConfirm('');
      if (forced) {
        window.location.href = url('/');
        return;
      }
      push('success', t('account.passwordChanged'));
    } catch (e) {
      setPwError(e);
    } finally {
      setBusy(false);
    }
  };

  const confirmMismatch = pwError !== null && typeof pwError === 'object' && 'confirm' in pwError;

  return (
    <Shell active="/account" flash={flash} navigationHide={forced}>
      <ContentLayout header={<Header variant="h1">{t('nav.account')}</Header>}>
        <SpaceBetween size="l">
          {forced && <Alert type="warning" header={t('account.mustChange.title')}>{t('account.mustChange')}</Alert>}

          <Container header={<Header variant="h2">{t('account.profile')}</Header>}>
            <KeyValuePairs
              columns={3}
              items={[
                { label: t('field.loginId'), value: user.loginId },
                { label: t('field.displayName'), value: user.displayName },
                { label: t('field.roles'), value: user.roles.map((r) => t(`role.${r}`)).join(', ') },
              ]}
            />
          </Container>

          {!forced && (
            <Container header={<Header variant="h2">{t('nav.language')}</Header>}>
              <SpaceBetween size="s" direction="horizontal" alignItems="end">
                <FormField label={t('field.locale')}>
                  <Select
                    selectedOption={LOCALES.find((l) => l.value === locale) ?? LOCALES[0]}
                    options={LOCALES}
                    onChange={(e) => setLocale(e.detail.selectedOption.value as 'en' | 'ja')}
                  />
                </FormField>
                <Button onClick={() => void saveLocale()} disabled={locale === boot.locale}>
                  {t('action.save')}
                </Button>
              </SpaceBetween>
            </Container>
          )}

          <form
            onSubmit={(e) => {
              e.preventDefault();
              void changePassword();
            }}
          >
            <Container header={<Header variant="h2" description={t('account.passwordRule')}>{t('account.changePassword')}</Header>}>
              <Form
                actions={
                  <Button variant="primary" formAction="submit" loading={busy} disabled={!current || !next || !confirm}>
                    {t('account.changePassword')}
                  </Button>
                }
              >
                <SpaceBetween size="m">
                  {pwError !== null && !confirmMismatch && !fieldError(pwError, 'currentPassword') && !fieldError(pwError, 'newPassword') && (
                    <Alert type="error">{errorText(pwError)}</Alert>
                  )}
                  <FormField label={t('field.currentPassword')} errorText={fieldError(pwError, 'currentPassword')}>
                    <Input type="password" value={current} onChange={(e) => setCurrent(e.detail.value)} autoComplete="current-password" />
                  </FormField>
                  <FormField label={t('field.newPassword')} errorText={fieldError(pwError, 'newPassword')}>
                    <Input type="password" value={next} onChange={(e) => setNext(e.detail.value)} autoComplete="new-password" />
                  </FormField>
                  <FormField label={t('field.confirmPassword')} errorText={confirmMismatch ? t('error.password.mismatch') : undefined}>
                    <Input type="password" value={confirm} onChange={(e) => setConfirm(e.detail.value)} autoComplete="new-password" />
                  </FormField>
                </SpaceBetween>
              </Form>
            </Container>
          </form>
          {!forced && <MfaSettings onChange={(m) => push('success', m)} />}
          {!forced && <PasskeySettings onChange={(m) => push('success', m)} />}
        </SpaceBetween>
      </ContentLayout>
    </Shell>
  );
}

mount(<AccountPage />);
