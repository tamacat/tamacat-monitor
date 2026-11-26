import { useState, type ReactNode } from 'react';
import Alert from '@cloudscape-design/components/alert';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Modal from '@cloudscape-design/components/modal';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Table from '@cloudscape-design/components/table';

import { ApiError, errorText } from '../lib/api';
import { t } from '../lib/boot';

export interface Change {
  table: string;
  operation: 'insert' | 'update' | 'delete';
  id: number;
  before: Record<string, string>;
  after: Record<string, string>;
}

export interface WriteResponse {
  preview: boolean;
  changes: Change[];
  id?: number;
}

function describe(values: Record<string, string>): string {
  return Object.entries(values)
    .map(([k, v]) => `${k}=${v}`)
    .join(', ');
}

/**
 * A change to a Zabbix DB in two steps: preview (dry-run, rolled back) shows the rows that will be
 * changed, then execute. Changing the form invalidates the preview (pass a new {@code formKey}).
 */
export function WriteDialog({
  title,
  formKey,
  children,
  danger,
  run,
  onClose,
  onDone,
}: {
  title: string;
  /** changes whenever the form input changes */
  formKey: string;
  children?: ReactNode;
  danger?: boolean;
  run: (preview: boolean) => Promise<WriteResponse>;
  onClose: () => void;
  onDone: (r: WriteResponse) => void;
}) {
  const [preview, setPreview] = useState<{ key: string; changes: Change[] } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const fresh = preview !== null && preview.key === formKey;

  const doPreview = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await run(true);
      setPreview({ key: formKey, changes: r.changes });
    } catch (e) {
      setError(e);
      setPreview(null);
    } finally {
      setBusy(false);
    }
  };

  const execute = async () => {
    setBusy(true);
    setError(null);
    try {
      onDone(await run(false));
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };

  return (
    <Modal
      visible
      size="large"
      onDismiss={onClose}
      header={title}
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onClose}>{t('action.cancel')}</Button>
            <Button onClick={() => void doPreview()} loading={busy && !fresh}>{t('write.preview')}</Button>
            <Button variant="primary" disabled={!fresh} loading={busy && fresh} onClick={() => void execute()}>
              {danger ? t('write.executeDanger') : t('write.execute')}
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <SpaceBetween size="m">
        {children}
        {error !== null && (
          <Alert type="error">
            {errorText(error)}
            {/* field errors of the form (validation): show each message, the form has no per-field slots */}
            {error instanceof ApiError && Object.keys(error.fields).length > 0 && (
              <ul>
                {[...new Set(Object.values(error.fields))].map((k) => <li key={k}>{t(k)}</li>)}
              </ul>
            )}
          </Alert>
        )}
        {fresh ? (
          <Table
            variant="embedded"
            header={<Box variant="h4">{t('write.changes', { n: preview.changes.length })}</Box>}
            items={preview.changes}
            columnDefinitions={[
              { id: 'op', header: t('write.operation'), cell: (c) => t(`write.op.${c.operation}`) },
              { id: 'table', header: t('write.table'), cell: (c) => `${c.table} #${c.id}` },
              { id: 'before', header: t('write.before'), cell: (c) => describe(c.before) },
              { id: 'after', header: t('write.after'), cell: (c) => describe(c.after) },
            ]}
          />
        ) : (
          <Box color="text-body-secondary">{t('write.previewFirst')}</Box>
        )}
      </SpaceBetween>
    </Modal>
  );
}
