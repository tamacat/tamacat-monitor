import { useEffect, useState, type ReactNode } from 'react';
import AppLayout from '@cloudscape-design/components/app-layout';
import Box from '@cloudscape-design/components/box';
import BreadcrumbGroup, { type BreadcrumbGroupProps } from '@cloudscape-design/components/breadcrumb-group';
import Flashbar, { type FlashbarProps } from '@cloudscape-design/components/flashbar';
import SideNavigation, { type SideNavigationProps } from '@cloudscape-design/components/side-navigation';
import TopNavigation from '@cloudscape-design/components/top-navigation';

import './shell.css';

import { DisplaySettingsModal } from '../components/DisplaySettingsModal';
import { api, errorText } from '../lib/api';
import { boot, hasRole, t, url } from '../lib/boot';

/** Side navigation width (Cloudscape default 280px), narrower to leave room for wide tables. */
const NAVIGATION_WIDTH = 200;

export type Flash = FlashbarProps.MessageDefinition;

const NEXT_FLASH = 'tmon.flash';

/** Keeps a text message for the next page of this tab (e.g. "deleted" after leaving a detail page). */
export function flashNextPage(type: Flash['type'], content: string): void {
  try {
    window.sessionStorage.setItem(NEXT_FLASH, JSON.stringify({ type, content }));
  } catch {
    // storage unavailable: the message is lost
  }
}

/** Flash messages shown at the top of the content. */
export function useFlash(): [Flash[], (type: Flash['type'], content: ReactNode) => void] {
  const [items, setItems] = useState<Flash[]>([]);
  useEffect(() => {
    try {
      const v = window.sessionStorage.getItem(NEXT_FLASH);
      if (v) {
        window.sessionStorage.removeItem(NEXT_FLASH);
        const m = JSON.parse(v) as { type: Flash['type']; content: string };
        push(m.type, m.content);
      }
    } catch {
      // ignore unreadable storage
    }
  }, []);
  const push = (type: Flash['type'], content: ReactNode) => {
    const id = String(Date.now() + Math.random());
    setItems((list) => [
      { id, type, content, dismissible: true, onDismiss: () => setItems((l) => l.filter((i) => i.id !== id)) },
      ...list.slice(0, 4),
    ]);
  };
  return [items, push];
}

function navigationItems(): SideNavigationProps.Item[] {
  const items: SideNavigationProps.Item[] = [
    { type: 'link', text: t('nav.home'), href: url('/') },
    {
      type: 'section',
      text: t('nav.monitoring'),
      items: [
        { type: 'link', text: t('nav.problems'), href: url('/problems') },
        { type: 'link', text: t('nav.hosts'), href: url('/hosts') },
        { type: 'link', text: t('nav.hostgroups'), href: url('/hostgroups') },
        { type: 'link', text: t('nav.items'), href: url('/items') },
        { type: 'link', text: t('nav.alerts'), href: url('/alerts') },
      ],
    },
  ];
  if (hasRole('SYSTEM_ADMIN') || hasRole('SERVER_ADMIN')) {
    items.push({
      type: 'section',
      text: t('nav.configuration'),
      items: [{ type: 'link', text: t('nav.actions'), href: url('/actions') }],
    });
  }
  if (hasRole('SYSTEM_ADMIN')) {
    items.push(
      { type: 'divider' },
      {
        type: 'section',
        text: t('nav.administration'),
        items: [
          { type: 'link', text: t('nav.servers'), href: url('/servers') },
          { type: 'link', text: t('nav.users'), href: url('/users') },
        ],
      },
    );
  }
  return items;
}

interface ShellProps {
  /** href of the active navigation item */
  active: string;
  flash?: Flash[];
  children: ReactNode;
  /** hide the navigation (forced password change) */
  navigationHide?: boolean;
  /** path from the home page; hrefs are context-relative */
  breadcrumbs?: BreadcrumbGroupProps.Item[];
  /** content type of the layout ("table" for full-page tables, "default" otherwise) */
  contentType?: 'default' | 'table' | 'form' | 'dashboard';
}

/** Page frame: top navigation (user menu, language, logout), side navigation and flash messages. */
export function Shell({ active, flash, children, navigationHide, breadcrumbs, contentType }: ShellProps) {
  const [menuError, setMenuError] = useState<string | null>(null);
  const [display, setDisplay] = useState(false);
  const user = boot.user;

  const onMenu = async (id: string) => {
    try {
      if (id === 'logout') {
        await api.post('/api/logout');
        window.location.href = url('/login');
      } else if (id === 'lang-en' || id === 'lang-ja') {
        await api.put('/api/me/locale', { locale: id.substring(5) });
        window.location.reload();
      } else if (id === 'account') {
        window.location.href = url('/account');
      } else if (id === 'display') {
        setDisplay(true);
      }
    } catch (e) {
      setMenuError(errorText(e));
    }
  };

  const messages: Flash[] = [...(flash ?? [])];
  if (menuError) {
    messages.unshift({ id: 'menu', type: 'error', content: menuError, dismissible: true, onDismiss: () => setMenuError(null) });
  }

  return (
    <>
      <div id="tmon-top">
        <TopNavigation
          identity={{ href: url('/'), title: t('app.title') }}
          utilities={
            user
              ? [
                  {
                    type: 'menu-dropdown',
                    text: user.displayName,
                    description: user.loginId,
                    iconName: 'user-profile',
                    onItemClick: (e) => void onMenu(e.detail.id),
                    items: [
                      { id: 'account', text: t('nav.account') },
                      { id: 'display', text: t('display.title') },
                      {
                        id: 'language',
                        text: t('nav.language'),
                        items: [
                          { id: 'lang-en', text: 'English', disabled: boot.locale === 'en' },
                          { id: 'lang-ja', text: '日本語', disabled: boot.locale === 'ja' },
                        ],
                      },
                      { id: 'logout', text: t('nav.logout') },
                    ],
                  },
                ]
              : []
          }
        />
      </div>
      <AppLayout
        headerSelector="#tmon-top"
        navigationWidth={NAVIGATION_WIDTH}
        navigation={
          <>
            <SideNavigation activeHref={url(active)} items={navigationItems()} />
            <Box padding={{ horizontal: 'l', vertical: 'm' }} variant="small" color="text-body-secondary">{t('app.notice')}</Box>
          </>
        }
        navigationHide={navigationHide}
        toolsHide
        contentType={contentType}
        breadcrumbs={breadcrumbs && breadcrumbs.length > 0 ? <BreadcrumbGroup items={[{ text: t('nav.home'), href: '/' }, ...breadcrumbs].map((b) => ({ ...b, href: url(b.href) }))} ariaLabel={t('nav.breadcrumbs')} /> : undefined}
        notifications={messages.length > 0 ? <Flashbar items={messages} /> : undefined}
        content={children}
      />
      {display && <DisplaySettingsModal onClose={() => setDisplay(false)} />}
    </>
  );
}
