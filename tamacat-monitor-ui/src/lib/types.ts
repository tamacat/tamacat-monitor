import type { Role } from './boot';

export interface User {
  userId: number;
  loginId: string;
  displayName: string;
  email: string | null;
  locale: 'en' | 'ja';
  enabled: boolean;
  mustChangePassword: boolean;
  mfaEnabled: boolean;
  /** registered passkeys (sign-in and second step) */
  passkeyCount: number;
  roles: Role[];
  lockedUntil: string | null;
  lastLoginAt: string | null;
}

export interface UserScope {
  serverId: number;
  hostgroupId: number;
  hostgroupName: string;
  includeSubgroups: boolean;
}

export interface UserDetail {
  user: User;
  serverIds: number[];
  scopes: UserScope[];
}

export interface ZabbixServer {
  serverId: number;
  name: string;
  description: string | null;
  dbUrl: string;
  readUser: string;
  writeUser: string | null;
  hasWritePassword: boolean;
  readOnly: boolean;
  enabled: boolean;
  writable: boolean;
  zabbixVersion: string | null;
  lastStatus: 'OK' | 'UNSUPPORTED' | 'ERROR' | null;
  lastCheckedAt: string | null;
}

export interface TestResult {
  connected: boolean;
  supported: boolean;
  zabbixVersion?: string;
  messageKey?: string;
  detail?: string;
}

export interface HostGroup {
  groupId: number;
  name: string;
}
