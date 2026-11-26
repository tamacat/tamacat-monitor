import { useState } from 'react';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import FormField from '@cloudscape-design/components/form-field';
import Modal from '@cloudscape-design/components/modal';
import SegmentedControl from '@cloudscape-design/components/segmented-control';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Tiles from '@cloudscape-design/components/tiles';

import { t } from '../lib/boot';
import { applyDisplay, loadDisplay, MODES, saveDisplay, THEMES, type DisplaySettings } from '../lib/display';

/** Theme and light / dark mode of this browser; changes are previewed at once and kept on save. */
export function DisplaySettingsModal({ onClose }: { onClose: () => void }) {
  const [initial] = useState(loadDisplay);
  const [value, setValue] = useState<DisplaySettings>(initial);

  const change = (v: DisplaySettings) => {
    setValue(v);
    applyDisplay(v);
  };
  const cancel = () => {
    applyDisplay(initial);
    onClose();
  };

  return (
    <Modal
      visible
      onDismiss={cancel}
      header={t('display.title')}
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={cancel}>{t('action.cancel')}</Button>
            <Button variant="primary" onClick={() => { saveDisplay(value); onClose(); }}>{t('action.save')}</Button>
          </SpaceBetween>
        </Box>
      }
    >
      <SpaceBetween size="l">
        <FormField label={t('display.theme')} description={t('display.themeHelp')}>
          <Tiles
            columns={3}
            value={value.theme}
            onChange={(e) => change({ ...value, theme: e.detail.value as DisplaySettings['theme'] })}
            items={THEMES.map((id) => ({ value: id, label: t(`display.theme.${id}`), description: t(`display.theme.${id}.help`) }))}
          />
        </FormField>
        <FormField label={t('display.mode')}>
          <SegmentedControl
            selectedId={value.mode}
            onChange={(e) => change({ ...value, mode: e.detail.selectedId as DisplaySettings['mode'] })}
            options={MODES.map((id) => ({ id, text: t(`display.mode.${id}`) }))}
          />
        </FormField>
        <Box variant="small" color="text-body-secondary">{t('display.storedInBrowser')}</Box>
      </SpaceBetween>
    </Modal>
  );
}
