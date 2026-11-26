import { useEffect, useState } from 'react';
import Alert from '@cloudscape-design/components/alert';
import Badge from '@cloudscape-design/components/badge';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Checkbox from '@cloudscape-design/components/checkbox';
import ColumnLayout from '@cloudscape-design/components/column-layout';
import Container from '@cloudscape-design/components/container';
import Form from '@cloudscape-design/components/form';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import KeyValuePairs from '@cloudscape-design/components/key-value-pairs';
import Link from '@cloudscape-design/components/link';
import Modal from '@cloudscape-design/components/modal';
import Multiselect from '@cloudscape-design/components/multiselect';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Spinner from '@cloudscape-design/components/spinner';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Table from '@cloudscape-design/components/table';
import Toggle from '@cloudscape-design/components/toggle';

import { ActionsMenu, DataTable } from '../components/DataTable';
import { api, errorText, fieldError } from '../lib/api';
import { boot, formatDateTime, ROLES, t, url, type Role } from '../lib/boot';
import { mount } from '../lib/mount';
import type { HostGroup, User, UserDetail, UserScope, ZabbixServer } from '../lib/types';
import { flashNextPage, Shell, useFlash } from '../shell/Shell';

const LOCALES = [
  { value: 'en', label: 'English' },
  { value: 'ja', label: '日本語' },
];

function isLocked(u: User): boolean {
  return u.lockedUntil !== null && new Date(u.lockedUntil).getTime() > Date.now();
}

function UserStatus({ u }: { u: User }) {
  if (!u.enabled) return <StatusIndicator type="stopped">{t('status.disabled')}</StatusIndicator>;
  if (isLocked(u)) return <StatusIndicator type="warning">{t('status.locked')}</StatusIndicator>;
  if (u.mustChangePassword) return <StatusIndicator type="pending">{t('status.mustChangePassword')}</StatusIndicator>;
  return <StatusIndicator type="success">{t('status.enabled')}</StatusIndicator>;
}

function userStatusText(u: User): string {
  if (!u.enabled) return t('status.disabled');
  if (isLocked(u)) return t('status.locked');
  if (u.mustChangePassword) return t('status.mustChangePassword');
  return t('status.enabled');
}

// ---- create / edit ----

function UserModal({ user, onClose, onSaved }: { user?: User; onClose: () => void; onSaved: (u: User) => void }) {
  const editing = user !== undefined;
  const [loginId, setLoginId] = useState(user?.loginId ?? '');
  const [displayName, setDisplayName] = useState(user?.displayName ?? '');
  const [email, setEmail] = useState(user?.email ?? '');
  const [locale, setLocale] = useState<string>(user?.locale ?? boot.locale);
  const [enabled, setEnabled] = useState(user?.enabled ?? true);
  const [roles, setRoles] = useState<Role[]>(user?.roles ?? ['VIEWER']);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [mismatch, setMismatch] = useState(false);
  const [busy, setBusy] = useState(false);

  const roleOptions = ROLES.map((r) => ({ value: r, label: t(`role.${r}`), description: t(`role.${r}.help`) }));

  const save = async () => {
    setError(null);
    setMismatch(false);
    if (!editing && password !== confirm) {
      setMismatch(true);
      return;
    }
    setBusy(true);
    try {
      const body = { loginId, displayName, email: email || null, locale, enabled, roles, password };
      const saved = editing
        ? await api.put<User>(`/api/users/${user.userId}`, body)
        : await api.post<User>('/api/users', body);
      onSaved(saved);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };

  const fields = ['loginId', 'displayName', 'email', 'locale', 'roles', 'password'];
  const generalError = error !== null && !fields.some((k) => fieldError(error, k));

  return (
    <Modal
      visible
      size="medium"
      onDismiss={onClose}
      header={editing ? t('user.edit') : t('user.create')}
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onClose}>
              {t('action.cancel')}
            </Button>
            <Button variant="primary" loading={busy} onClick={() => void save()}>
              {t('action.save')}
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <Form>
        <SpaceBetween size="m">
          {generalError && <Alert type="error">{errorText(error)}</Alert>}
          <FormField label={t('field.loginId')} errorText={fieldError(error, 'loginId')} constraintText={editing ? undefined : t('user.loginIdRule')}>
            <Input value={loginId} onChange={(e) => setLoginId(e.detail.value)} disabled={editing} autoComplete="off" />
          </FormField>
          <FormField label={t('field.displayName')} errorText={fieldError(error, 'displayName')}>
            <Input value={displayName} onChange={(e) => setDisplayName(e.detail.value)} />
          </FormField>
          <FormField label={t('field.email')} errorText={fieldError(error, 'email')}>
            <Input type="email" value={email} onChange={(e) => setEmail(e.detail.value)} />
          </FormField>
          <ColumnLayout columns={2}>
            <FormField label={t('field.locale')}>
              <Select
                selectedOption={LOCALES.find((l) => l.value === locale) ?? LOCALES[0]}
                options={LOCALES}
                onChange={(e) => setLocale(e.detail.selectedOption.value ?? 'en')}
              />
            </FormField>
            <FormField label={t('field.enabled')}>
              <Toggle checked={enabled} onChange={(e) => setEnabled(e.detail.checked)}>
                {enabled ? t('status.enabled') : t('status.disabled')}
              </Toggle>
            </FormField>
          </ColumnLayout>
          <FormField label={t('field.roles')} errorText={fieldError(error, 'roles')}>
            <Multiselect
              selectedOptions={roleOptions.filter((o) => roles.includes(o.value))}
              options={roleOptions}
              onChange={(e) => setRoles(e.detail.selectedOptions.map((o) => o.value as Role))}
              placeholder={t('field.roles')}
            />
          </FormField>
          {!editing && (
            <ColumnLayout columns={2}>
              <FormField label={t('field.initialPassword')} errorText={fieldError(error, 'password')} constraintText={t('account.passwordRule')}>
                <Input type="password" value={password} onChange={(e) => setPassword(e.detail.value)} autoComplete="new-password" />
              </FormField>
              <FormField label={t('field.confirmPassword')} errorText={mismatch ? t('error.password.mismatch') : undefined}>
                <Input type="password" value={confirm} onChange={(e) => setConfirm(e.detail.value)} autoComplete="new-password" />
              </FormField>
            </ColumnLayout>
          )}
        </SpaceBetween>
      </Form>
    </Modal>
  );
}

// ---- password reset ----

function PasswordModal({ user, onClose, onDone }: { user: User; onClose: () => void; onDone: () => void }) {
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [mismatch, setMismatch] = useState(false);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setError(null);
    setMismatch(password !== confirm);
    if (password !== confirm) return;
    setBusy(true);
    try {
      await api.post(`/api/users/${user.userId}/password`, { password });
      onDone();
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };

  return (
    <Modal
      visible
      onDismiss={onClose}
      header={t('user.resetPassword')}
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onClose}>
              {t('action.cancel')}
            </Button>
            <Button variant="primary" loading={busy} onClick={() => void save()}>
              {t('action.save')}
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <SpaceBetween size="m">
        <Box>{t('user.resetPasswordHelp', { name: user.loginId })}</Box>
        {error !== null && !fieldError(error, 'password') && <Alert type="error">{errorText(error)}</Alert>}
        <FormField label={t('field.newPassword')} errorText={fieldError(error, 'password')} constraintText={t('account.passwordRule')}>
          <Input type="password" value={password} onChange={(e) => setPassword(e.detail.value)} autoComplete="new-password" />
        </FormField>
        <FormField label={t('field.confirmPassword')} errorText={mismatch ? t('error.password.mismatch') : undefined}>
          <Input type="password" value={confirm} onChange={(e) => setConfirm(e.detail.value)} autoComplete="new-password" />
        </FormField>
      </SpaceBetween>
    </Modal>
  );
}

// ---- assignments (servers for SERVER_ADMIN, host groups for OPERATOR / VIEWER) ----

function AssignmentsModal({ user, onClose, onDone }: { user: User; onClose: () => void; onDone: () => void }) {
  const [servers, setServers] = useState<ZabbixServer[]>([]);
  const [serverIds, setServerIds] = useState<number[]>([]);
  const [scopes, setScopes] = useState<UserScope[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const [scopeServer, setScopeServer] = useState<number | null>(null);
  const [groups, setGroups] = useState<HostGroup[]>([]);
  const [groupsLoading, setGroupsLoading] = useState(false);
  const [groupsError, setGroupsError] = useState<string | null>(null);
  const [pickedGroups, setPickedGroups] = useState<number[]>([]);

  useEffect(() => {
    void (async () => {
      try {
        const [list, detail] = await Promise.all([api.get<ZabbixServer[]>('/api/servers'), api.get<UserDetail>(`/api/users/${user.userId}`)]);
        setServers(list);
        setServerIds(detail.serverIds);
        setScopes(detail.scopes);
      } catch (e) {
        setError(e);
      } finally {
        setLoading(false);
      }
    })();
  }, [user.userId]);

  const loadGroups = async (serverId: number) => {
    setScopeServer(serverId);
    setGroups([]);
    setPickedGroups([]);
    setGroupsError(null);
    setGroupsLoading(true);
    try {
      setGroups(await api.get<HostGroup[]>(`/api/servers/${serverId}/hostgroups?limit=1000`));
    } catch (e) {
      setGroupsError(errorText(e));
    } finally {
      setGroupsLoading(false);
    }
  };

  const addScopes = () => {
    if (scopeServer === null) return;
    const add = groups
      .filter((g) => pickedGroups.includes(g.groupId))
      .filter((g) => !scopes.some((s) => s.serverId === scopeServer && s.hostgroupId === g.groupId))
      .map((g) => ({ serverId: scopeServer, hostgroupId: g.groupId, hostgroupName: g.name, includeSubgroups: false }));
    setScopes([...scopes, ...add]);
    setPickedGroups([]);
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.put(`/api/users/${user.userId}/assignments`, { serverIds, scopes });
      onDone();
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };

  const serverName = (id: number) => servers.find((s) => s.serverId === id)?.name ?? `#${id}`;
  const serverOptions = servers.map((s) => ({ value: String(s.serverId), label: s.name, description: s.zabbixVersion ?? undefined }));
  const usableServers = servers.filter((s) => s.enabled && s.lastStatus === 'OK');

  return (
    <Modal
      visible
      size="large"
      onDismiss={onClose}
      header={t('user.assignments', { name: user.loginId })}
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onClose}>
              {t('action.cancel')}
            </Button>
            <Button variant="primary" loading={busy} disabled={loading} onClick={() => void save()}>
              {t('action.save')}
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <SpaceBetween size="l">
        {error !== null && <Alert type="error">{errorText(error)}</Alert>}
        {user.roles.includes('SYSTEM_ADMIN') && <Alert type="info">{t('user.assignments.adminAll')}</Alert>}

        {user.roles.includes('SERVER_ADMIN') && (
          <FormField label={t('user.assignments.servers')} description={t('user.assignments.serversHelp')}>
            <Multiselect
              statusType={loading ? 'loading' : 'finished'}
              selectedOptions={serverOptions.filter((o) => serverIds.includes(Number(o.value)))}
              options={serverOptions}
              onChange={(e) => setServerIds(e.detail.selectedOptions.map((o) => Number(o.value)))}
              placeholder={t('user.assignments.chooseServers')}
            />
          </FormField>
        )}

        <SpaceBetween size="s">
          <Header variant="h3" description={t('user.assignments.groupsHelp')}>
            {t('user.assignments.groups')}
          </Header>
          <ColumnLayout columns={3}>
            <FormField label={t('field.server')}>
              <Select
                statusType={loading ? 'loading' : 'finished'}
                selectedOption={scopeServer === null ? null : { value: String(scopeServer), label: serverName(scopeServer) }}
                options={usableServers.map((s) => ({ value: String(s.serverId), label: s.name }))}
                onChange={(e) => void loadGroups(Number(e.detail.selectedOption.value))}
                placeholder={t('user.assignments.chooseServer')}
                empty={t('user.assignments.noServer')}
              />
            </FormField>
            <FormField label={t('field.hostGroups')} errorText={groupsError ?? undefined}>
              <Multiselect
                statusType={groupsLoading ? 'loading' : 'finished'}
                disabled={scopeServer === null}
                filteringType="auto"
                selectedOptions={groups.filter((g) => pickedGroups.includes(g.groupId)).map((g) => ({ value: String(g.groupId), label: g.name }))}
                options={groups.map((g) => ({ value: String(g.groupId), label: g.name }))}
                onChange={(e) => setPickedGroups(e.detail.selectedOptions.map((o) => Number(o.value)))}
                placeholder={t('user.assignments.chooseGroups')}
                hideTokens
              />
            </FormField>
            <FormField label={' '}>
              <Button iconName="add-plus" disabled={pickedGroups.length === 0} onClick={addScopes}>
                {t('action.add')}
              </Button>
            </FormField>
          </ColumnLayout>
          <Table
            variant="embedded"
            items={scopes}
            trackBy={(s) => `${s.serverId}:${s.hostgroupId}`}
            empty={<Box color="text-body-secondary">{t('user.assignments.noGroups')}</Box>}
            columnDefinitions={[
              { id: 'server', header: t('field.server'), cell: (s) => serverName(s.serverId) },
              { id: 'group', header: t('field.hostGroup'), cell: (s) => s.hostgroupName },
              {
                id: 'sub',
                header: t('field.includeSubgroups'),
                cell: (s) => (
                  <Checkbox
                    checked={s.includeSubgroups}
                    onChange={(e) =>
                      setScopes(scopes.map((x) => (x === s ? { ...x, includeSubgroups: e.detail.checked } : x)))
                    }
                  />
                ),
              },
              {
                id: 'remove',
                header: '',
                cell: (s) => (
                  <Button variant="icon" iconName="remove" ariaLabel={t('action.remove')} onClick={() => setScopes(scopes.filter((x) => x !== s))} />
                ),
              },
            ]}
          />
        </SpaceBetween>
      </SpaceBetween>
    </Modal>
  );
}

// ---- page ----

type ModalKind = 'edit' | 'password' | 'assign' | 'delete' | null;

const LOCALE_LABEL: Record<string, string> = Object.fromEntries(LOCALES.map((l) => [l.value, l.label]));

function RoleBadges({ u }: { u: User }) {
  return (
    <SpaceBetween direction="horizontal" size="xxs">
      {u.roles.map((r) => (
        <Badge key={r} color={r === 'SYSTEM_ADMIN' ? 'red' : r === 'SERVER_ADMIN' ? 'blue' : 'grey'}>
          {t(`role.${r}`)}
        </Badge>
      ))}
    </SpaceBetween>
  );
}

/** TOTP and passkeys: what the user can use after the password (or, for passkeys, instead of it). */
function secondFactorText(u: User): string {
  const parts = [];
  if (u.mfaEnabled) parts.push(t('mfa.totp'));
  if (u.passkeyCount > 0) parts.push(t('passkey.count', { n: u.passkeyCount }));
  return parts.length > 0 ? parts.join(' / ') : '-';
}

function SecondFactor({ u }: { u: User }) {
  return u.mfaEnabled || u.passkeyCount > 0 ? <StatusIndicator type="success">{secondFactorText(u)}</StatusIndicator> : <>-</>;
}

// ---- detail page ----

function UserDetailPage({ userId }: { userId: number }) {
  const [d, setD] = useState<UserDetail | null>(null);
  const [servers, setServers] = useState<ZabbixServer[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [modal, setModal] = useState<ModalKind>(null);
  const [flash, push] = useFlash();

  const load = async () => {
    try {
      const r = await api.get<UserDetail>(`/api/users/${userId}`);
      setD(r);
      document.title = `${r.user.loginId} - ${t('nav.users')}`;
    } catch (e) {
      setError(errorText(e));
    }
  };
  useEffect(() => {
    void load();
    api.get<ZabbixServer[]>('/api/servers').then(setServers).catch(() => setServers([]));
  }, [userId]);

  const serverName = (id: number) => servers.find((s) => s.serverId === id)?.name ?? String(id);
  const run = async (fn: () => Promise<unknown>, message: string) => {
    try {
      await fn();
      push('success', message);
      await load();
    } catch (e) {
      push('error', errorText(e));
    }
  };

  const u = d?.user;
  const isSelf = u?.userId === boot.user?.userId;
  const isAdmin = u?.roles.includes('SYSTEM_ADMIN') ?? false;
  return (
    <Shell active="/users" flash={flash} breadcrumbs={[{ text: t('nav.users'), href: '/users' }, { text: u?.loginId ?? '...', href: `/users?id=${userId}` }]}>
      <SpaceBetween size="l">
        <Header
          variant="h1"
          description={u?.displayName}
          actions={u && (
            <ActionsMenu
              items={[
                { id: 'assign', text: t('user.assign') },
                { id: 'password', text: t('user.resetPassword') },
                { id: 'mfa', text: t('mfa.reset'), disabled: !u.mfaEnabled && u.passkeyCount === 0 },
                { id: 'unlock', text: t('user.unlock'), disabled: !isLocked(u) },
                { id: 'delete', text: t('action.delete'), disabled: isSelf },
              ]}
              onItemClick={(id) => {
                if (id === 'mfa') {
                  if (window.confirm(t('mfa.resetConfirm', { name: u.loginId }))) {
                    void run(() => api.post(`/api/users/${u.userId}/mfa/reset`), t('mfa.resetDone', { name: u.loginId }));
                  }
                } else if (id === 'unlock') {
                  void run(() => api.post(`/api/users/${u.userId}/unlock`), t('user.unlocked', { name: u.loginId }));
                } else {
                  setModal(id as ModalKind);
                }
              }}
              primary={<Button variant="primary" onClick={() => setModal('edit')}>{t('action.edit')}</Button>}
            />
          )}
        >
          {u?.loginId ?? t('common.loading')}
        </Header>
        {error && <Box color="text-status-error">{error}</Box>}
        {!d && !error && <Spinner size="large" />}
        {d && u && (
          <>
            <Container header={<Header variant="h2">{t('detail.overview')}</Header>}>
              <KeyValuePairs
                columns={4}
                items={[
                  { label: t('field.loginId'), value: u.loginId },
                  { label: t('field.displayName'), value: u.displayName },
                  { label: t('field.email'), value: u.email || '-' },
                  { label: t('field.locale'), value: LOCALE_LABEL[u.locale] ?? u.locale },
                  { label: t('field.roles'), value: <RoleBadges u={u} /> },
                  { label: t('field.status'), value: <UserStatus u={u} /> },
                  { label: t('mfa.column'), value: <SecondFactor u={u} /> },
                  { label: t('field.lastLogin'), value: formatDateTime(u.lastLoginAt) },
                ]}
              />
            </Container>
            <Container header={<Header variant="h2" actions={<Button onClick={() => setModal('assign')}>{t('action.edit')}</Button>}>{t('user.assign')}</Header>}>
              {isAdmin ? (
                <Box color="text-body-secondary">{t('user.assignments.adminAll')}</Box>
              ) : (
                <SpaceBetween size="l">
                  <KeyValuePairs
                    items={[{ label: t('user.assignments.servers'), value: d.serverIds.length > 0 ? d.serverIds.map(serverName).join(', ') : '-' }]}
                  />
                  <Table
                    variant="embedded"
                    header={<Header variant="h3" counter={`(${d.scopes.length})`}>{t('user.assignments.groups')}</Header>}
                    items={d.scopes}
                    wrapLines
                    empty={<Box textAlign="center" color="text-body-secondary">{t('user.assignments.noGroups')}</Box>}
                    columnDefinitions={[
                      { id: 'server', header: t('field.server'), cell: (x) => serverName(x.serverId) },
                      { id: 'group', header: t('nav.hostgroups'), cell: (x) => x.hostgroupName, isRowHeader: true },
                      { id: 'sub', header: t('field.includeSubgroups'), cell: (x) => (x.includeSubgroups ? t('common.yes') : t('common.no')) },
                    ]}
                  />
                </SpaceBetween>
              )}
            </Container>
          </>
        )}
      </SpaceBetween>
      {modal === 'edit' && u && (
        <UserModal
          user={u}
          onClose={() => setModal(null)}
          onSaved={(saved) => {
            setModal(null);
            push('success', t('user.saved', { name: saved.loginId }));
            void load();
          }}
        />
      )}
      {modal === 'password' && u && (
        <PasswordModal
          user={u}
          onClose={() => setModal(null)}
          onDone={() => {
            setModal(null);
            push('success', t('user.passwordReset', { name: u.loginId }));
            void load();
          }}
        />
      )}
      {modal === 'assign' && u && (
        <AssignmentsModal
          user={u}
          onClose={() => setModal(null)}
          onDone={() => {
            setModal(null);
            push('success', t('user.assigned', { name: u.loginId }));
            void load();
          }}
        />
      )}
      {modal === 'delete' && u && (
        <Modal
          visible
          onDismiss={() => setModal(null)}
          header={t('user.deleteTitle')}
          footer={
            <Box float="right">
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="link" onClick={() => setModal(null)}>
                  {t('action.cancel')}
                </Button>
                <Button
                  variant="primary"
                  onClick={async () => {
                    try {
                      await api.del(`/api/users/${u.userId}`);
                      flashNextPage('success', t('user.deleted', { name: u.loginId }));
                      window.location.href = url('/users');
                    } catch (e) {
                      setModal(null);
                      push('error', errorText(e));
                    }
                  }}
                >
                  {t('action.delete')}
                </Button>
              </SpaceBetween>
            </Box>
          }
        >
          {t('user.deleteConfirm', { name: u.loginId })}
        </Modal>
      )}
    </Shell>
  );
}

// ---- list page ----

function UsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [flash, push] = useFlash();

  const load = async () => {
    setLoading(true);
    try {
      setUsers(await api.get<User[]>('/api/users'));
    } catch (e) {
      push('error', errorText(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  return (
    <Shell active="/users" flash={flash} contentType="table" breadcrumbs={[{ text: t('nav.users'), href: '/users' }]}>
      <DataTable
        tableId="users"
        title={t('nav.users')}
        description={t('user.pageHelp')}
        items={users}
        loading={loading}
        trackBy="userId"
        actions={
          <SpaceBetween direction="horizontal" size="xs">
            <Button iconName="refresh" ariaLabel={t('action.reload')} onClick={() => void load()} />
            <Button variant="primary" onClick={() => setCreating(true)}>{t('user.create')}</Button>
          </SpaceBetween>
        }
        empty={<Box textAlign="center" color="text-body-secondary">-</Box>}
        defaultSort={{ column: 'loginId' }}
        columns={[
          { id: 'loginId', header: t('field.loginId'), cell: (u) => <Link href={url(`/users?id=${u.userId}`)}>{u.loginId}</Link>, filterValue: (u) => u.loginId, isRowHeader: true },
          { id: 'displayName', header: t('field.displayName'), cell: (u) => u.displayName, filterValue: (u) => u.displayName },
          { id: 'email', header: t('field.email'), cell: (u) => u.email ?? '', filterValue: (u) => u.email ?? '', hidden: true },
          { id: 'roles', header: t('field.roles'), cell: (u) => <RoleBadges u={u} />, filterValue: (u) => u.roles.map((r) => t(`role.${r}`)).join(', ') },
          { id: 'status', header: t('field.status'), cell: (u) => <UserStatus u={u} />, filterValue: userStatusText, enumerated: true },
          { id: 'mfa', header: t('mfa.column'), cell: (u) => <SecondFactor u={u} />, filterValue: secondFactorText, enumerated: true },
          { id: 'lastLogin', header: t('field.lastLogin'), cell: (u) => formatDateTime(u.lastLoginAt), sortValue: (u) => u.lastLoginAt ?? '' },
        ]}
      />
      {creating && (
        <UserModal
          onClose={() => setCreating(false)}
          onSaved={(u) => {
            // continue on the new user's page, where the assignments are set next
            flashNextPage('success', t('user.saved', { name: u.loginId }));
            window.location.href = url(`/users?id=${u.userId}`);
          }}
        />
      )}
    </Shell>
  );
}

const userId = new URLSearchParams(window.location.search).get('id');
mount(userId ? <UserDetailPage userId={Number(userId)} /> : <UsersPage />);
