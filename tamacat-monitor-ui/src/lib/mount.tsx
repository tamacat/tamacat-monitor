import '@cloudscape-design/global-styles/index.css';

import { StrictMode, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { I18nProvider } from '@cloudscape-design/components/i18n';
import enMessages from '@cloudscape-design/components/i18n/messages/all.en';
import jaMessages from '@cloudscape-design/components/i18n/messages/all.ja';

import { boot } from './boot';
import { applyDisplay } from './display';

/** Renders a page: Cloudscape i18n (en / ja) and the display settings of this browser (theme, light / dark mode). */
export function mount(page: ReactNode): void {
  applyDisplay();

  document.documentElement.lang = boot.locale;
  const root = document.getElementById('root');
  if (!root) return;
  createRoot(root).render(
    <StrictMode>
      <I18nProvider locale={boot.locale} messages={[enMessages, jaMessages]}>
        {page}
      </I18nProvider>
    </StrictMode>,
  );
}
