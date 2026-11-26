// Data passed by the page shell (/WEB-INF/jsp/page.jsp) in <script id="tmon-bootstrap" type="application/json">.

export type Role = 'SYSTEM_ADMIN' | 'SERVER_ADMIN' | 'OPERATOR' | 'VIEWER';
export const ROLES: Role[] = ['SYSTEM_ADMIN', 'SERVER_ADMIN', 'OPERATOR', 'VIEWER'];

export interface BootUser {
  userId: number;
  loginId: string;
  displayName: string;
  mustChangePassword: boolean;
  roles: Role[];
}

export interface Boot {
  page: string;
  contextPath: string;
  csrfToken: string;
  locale: 'en' | 'ja';
  version: string;
  messages: Record<string, string>;
  /** passkeys are configured (webauthn.rp-id) */
  passkeys?: boolean;
  user?: BootUser;
}

function read(): Boot {
  const el = document.getElementById('tmon-bootstrap');
  return JSON.parse(el?.textContent ?? '{}') as Boot;
}

export const boot: Boot = read();

/** Message by key, with {name} placeholders replaced. Unknown keys are shown as-is. */
export function t(key: string, params?: Record<string, string | number>): string {
  let s = boot.messages?.[key] ?? key;
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      s = s.split(`{${k}}`).join(String(v));
    }
  }
  return s;
}

export function url(path: string): string {
  return boot.contextPath + path;
}

export function hasRole(role: Role): boolean {
  return boot.user?.roles.includes(role) ?? false;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '-';
  return new Date(iso).toLocaleString(boot.locale === 'ja' ? 'ja-JP' : 'en-US');
}
