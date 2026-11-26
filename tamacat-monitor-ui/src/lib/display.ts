import { applyDensity, applyMode, Density, Mode } from '@cloudscape-design/global-styles';
import { generateThemeStylesheet, type Theme } from '@cloudscape-design/components/theming';

/**
 * Display settings of this browser: theme (text size and spacing) and light / dark mode.
 * Kept in localStorage; a missing or unreadable value falls back to the defaults.
 */
export type ThemeId = 'standard' | 'compact' | 'dense';
export type ModeId = 'auto' | 'light' | 'dark';

export interface DisplaySettings {
  theme: ThemeId;
  mode: ModeId;
}

export const THEMES: ThemeId[] = ['compact', 'dense', 'standard'];
export const MODES: ModeId[] = ['auto', 'light', 'dark'];

const KEY = 'tmon.display';
const DEFAULTS: DisplaySettings = { theme: 'compact', mode: 'auto' };

/** System Japanese fonts after Cloudscape's own, so Japanese text is not drawn in a fallback serif / bitmap font. */
const FONT_FAMILY =
  "'Open Sans', 'Helvetica Neue', Roboto, Arial, 'Hiragino Sans', 'Hiragino Kaku Gothic ProN', 'Noto Sans JP', 'Yu Gothic UI', Meiryo, sans-serif";

type Sizes = [fontSize: string, lineHeight: string];

function typography(s: {
  bodyM: Sizes;
  bodyS: Sizes;
  h1: Sizes;
  h2: Sizes;
  h3: Sizes;
  h4: Sizes;
  h5: Sizes;
  display: Sizes;
}): Theme['tokens'] {
  return {
    fontFamilyBase: FONT_FAMILY,
    fontSizeBodyM: s.bodyM[0],
    lineHeightBodyM: s.bodyM[1],
    fontSizeBodyS: s.bodyS[0],
    lineHeightBodyS: s.bodyS[1],
    fontSizeHeadingXl: s.h1[0],
    lineHeightHeadingXl: s.h1[1],
    fontSizeHeadingL: s.h2[0],
    lineHeightHeadingL: s.h2[1],
    fontSizeHeadingM: s.h3[0],
    lineHeightHeadingM: s.h3[1],
    fontSizeHeadingS: s.h4[0],
    lineHeightHeadingS: s.h4[1],
    fontSizeHeadingXs: s.h5[0],
    lineHeightHeadingXs: s.h5[1],
    fontSizeDisplayL: s.display[0],
    lineHeightDisplayL: s.display[1],
    fontSizeFormLabel: s.bodyM[0],
    lineHeightFormLabel: s.bodyM[1],
    fontSizeKeyValuePairsLabel: s.bodyM[0],
    lineHeightKeyValuePairsLabel: s.bodyM[1],
    fontSizeTabs: s.h3[0],
    lineHeightTabs: s.h3[1],
    fontSizeExpandableHeading: s.h3[0],
  };
}

const THEME_TOKENS: Record<ThemeId, Theme['tokens']> = {
  standard: { fontFamilyBase: FONT_FAMILY },
  compact: typography({
    bodyM: ['13px', '18px'],
    bodyS: ['12px', '16px'],
    h1: ['20px', '26px'],
    h2: ['17px', '22px'],
    h3: ['15px', '20px'],
    h4: ['14px', '18px'],
    h5: ['13px', '18px'],
    display: ['32px', '38px'],
  }),
  dense: typography({
    bodyM: ['12px', '16px'],
    bodyS: ['11px', '14px'],
    h1: ['18px', '24px'],
    h2: ['16px', '20px'],
    h3: ['14px', '18px'],
    h4: ['13px', '16px'],
    h5: ['12px', '16px'],
    display: ['28px', '34px'],
  }),
};

export function loadDisplay(): DisplaySettings {
  try {
    const v = JSON.parse(window.localStorage.getItem(KEY) ?? '{}') as Partial<DisplaySettings>;
    return {
      theme: THEMES.includes(v.theme as ThemeId) ? (v.theme as ThemeId) : DEFAULTS.theme,
      mode: MODES.includes(v.mode as ModeId) ? (v.mode as ModeId) : DEFAULTS.mode,
    };
  } catch {
    return { ...DEFAULTS };
  }
}

export function saveDisplay(s: DisplaySettings): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(s));
  } catch {
    // storage unavailable: the settings last until the page is left
  }
  applyDisplay(s);
}

let themeSheet: CSSStyleSheet | null = null;
let darkQuery: MediaQueryList | null = null;
let current: DisplaySettings = DEFAULTS;

function applyCurrentMode() {
  const dark = current.mode === 'dark' || (current.mode === 'auto' && (darkQuery?.matches ?? false));
  applyMode(dark ? Mode.Dark : Mode.Light);
}

/** Applies the settings to the document (theme tokens, density, mode). */
export function applyDisplay(s: DisplaySettings = loadDisplay()): void {
  current = s;
  if (!darkQuery) {
    darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
    darkQuery.addEventListener('change', applyCurrentMode);
  }
  applyCurrentMode();
  applyDensity(s.theme === 'standard' ? Density.Comfortable : Density.Compact);
  applyThemeSheet(generateThemeStylesheet({ theme: { tokens: THEME_TOKENS[s.theme] } }));
}

/**
 * Theme tokens as a constructed stylesheet: the content security policy blocks inline <style>
 * elements (which applyTheme would add), but not stylesheets built through the CSSOM.
 */
function applyThemeSheet(css: string) {
  if (!themeSheet) {
    themeSheet = new CSSStyleSheet();
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, themeSheet];
  }
  themeSheet.replaceSync(css);
}
