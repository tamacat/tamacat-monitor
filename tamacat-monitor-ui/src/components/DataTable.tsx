import { useMemo, useState, type ReactNode } from 'react';
import { useCollection, type PropertyFilterQuery, type PropertyFilterToken } from '@cloudscape-design/collection-hooks';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import ButtonDropdown from '@cloudscape-design/components/button-dropdown';
import CollectionPreferences, { type CollectionPreferencesProps } from '@cloudscape-design/components/collection-preferences';
import FormField from '@cloudscape-design/components/form-field';
import Header from '@cloudscape-design/components/header';
import Input from '@cloudscape-design/components/input';
import Modal from '@cloudscape-design/components/modal';
import Pagination from '@cloudscape-design/components/pagination';
import PropertyFilter from '@cloudscape-design/components/property-filter';
import Select from '@cloudscape-design/components/select';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Table, { type TableProps } from '@cloudscape-design/components/table';

import { t } from '../lib/boot';

/**
 * Full-page list (Cloudscape "table with saved filter sets"): property filter with saved filter sets,
 * sorting, resizable columns, sticky header, pagination and preferences (page size, columns, wrapping).
 * Filtering, sorting and paging run in the browser over the rows the page fetched; preferences,
 * column widths and filter sets are kept per table in localStorage.
 */
export interface DataColumn<T> {
  id: string;
  header: string;
  cell: (item: T) => ReactNode;
  /** text matched by the filter; without it the column cannot be filtered on */
  filterValue?: (item: T) => string;
  /** sort key; defaults to filterValue. Columns with neither are not sortable */
  sortValue?: (item: T) => string | number;
  /** offer the distinct values of the column as filter suggestions */
  enumerated?: boolean;
  width?: number;
  minWidth?: number;
  isRowHeader?: boolean;
  /** hidden until shown in the preferences */
  hidden?: boolean;
}

export interface DataTableProps<T> {
  /** key of the stored preferences and filter sets */
  tableId: string;
  title: string;
  description?: ReactNode;
  /** buttons of the header (right side) */
  actions?: ReactNode;
  items: readonly T[];
  loading?: boolean;
  trackBy: string | ((item: T) => string);
  columns: DataColumn<T>[];
  /** fetch conditions (servers, period, ...) shown above the filter */
  toolbar?: ReactNode;
  empty: ReactNode;
  defaultSort?: { column: string; descending?: boolean };
  selectionType?: 'single' | 'multi';
  selectedItems?: T[];
  onSelectionChange?: (items: T[]) => void;
}

interface Prefs {
  pageSize: number;
  wrapLines: boolean;
  stripedRows: boolean;
  stickyFirst: number;
  contentDisplay: { id: string; visible: boolean }[];
  widths: Record<string, number>;
}

interface FilterSet {
  name: string;
  query: PropertyFilterQuery;
}

const EMPTY_QUERY: PropertyFilterQuery = { tokens: [], operation: 'and' };
/** Width of columns without their own (resizable tables otherwise shrink columns to fit). */
const DEFAULT_WIDTH = 160;

function readStore<V>(key: string, fallback: V): V {
  try {
    const v = window.localStorage.getItem(key);
    return v ? (JSON.parse(v) as V) : fallback;
  } catch {
    return fallback;
  }
}

function writeStore(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // storage unavailable: kept until the page is left
  }
}

/** Stored preferences merged with the current columns (new columns appended, removed ones dropped). */
function initialPrefs<T>(stored: Partial<Prefs> | null, columns: DataColumn<T>[]): Prefs {
  const known = new Set(columns.map((c) => c.id));
  const display = (stored?.contentDisplay ?? []).filter((d) => known.has(d.id));
  for (const c of columns) {
    if (!display.some((d) => d.id === c.id)) display.push({ id: c.id, visible: !c.hidden });
  }
  return {
    pageSize: stored?.pageSize ?? 50,
    wrapLines: stored?.wrapLines ?? false,
    stripedRows: stored?.stripedRows ?? false,
    stickyFirst: stored?.stickyFirst ?? 0,
    contentDisplay: display,
    widths: stored?.widths ?? {},
  };
}

function matchText(value: string, operator: string, token: string): boolean {
  const v = value.toLowerCase();
  const q = token.toLowerCase();
  switch (operator) {
    case '=':
      return v === q;
    case '!=':
      return v !== q;
    case '^':
      return v.startsWith(q);
    case '!:':
      return !v.includes(q);
    default:
      return v.includes(q);
  }
}

function matchToken<T>(item: T, token: PropertyFilterToken, columns: DataColumn<T>[]): boolean {
  const text = String(token.value ?? '');
  if (token.propertyKey) {
    const c = columns.find((x) => x.id === token.propertyKey);
    return c?.filterValue ? matchText(c.filterValue(item), token.operator, text) : true;
  }
  const values = columns.filter((c) => c.filterValue).map((c) => c.filterValue!(item));
  return token.operator === '!:'
    ? values.every((v) => matchText(v, '!:', text))
    : values.some((v) => matchText(v, ':', text));
}

function compare(a: string | number, b: string | number): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' });
}

export function DataTable<T>(props: DataTableProps<T>) {
  const { tableId, columns } = props;
  const prefsKey = `tmon.table.${tableId}`;
  const setsKey = `tmon.filters.${tableId}`;
  const [prefs, setPrefsState] = useState<Prefs>(() => initialPrefs(readStore<Partial<Prefs> | null>(prefsKey, null), columns));
  const [sets, setSetsState] = useState<FilterSet[]>(() => readStore<FilterSet[]>(setsKey, []));
  const [currentSet, setCurrentSet] = useState<string | null>(null);
  const [setDialog, setSetDialog] = useState<'save' | 'delete' | null>(null);
  const [setName, setSetName] = useState('');

  const setPrefs = (p: Prefs) => {
    setPrefsState(p);
    writeStore(prefsKey, p);
  };
  const setSets = (s: FilterSet[]) => {
    setSetsState(s);
    writeStore(setsKey, s);
  };

  const sortKey = (c: DataColumn<T>) => c.sortValue ?? c.filterValue;
  const definitions: TableProps.ColumnDefinition<T>[] = columns.map((c) => {
    const key = sortKey(c);
    return {
      id: c.id,
      header: c.header,
      cell: c.cell,
      isRowHeader: c.isRowHeader,
      width: prefs.widths[c.id] ?? c.width ?? DEFAULT_WIDTH,
      minWidth: c.minWidth ?? 80,
      sortingField: key ? c.id : undefined,
      sortingComparator: key ? (a: T, b: T) => compare(key(a), key(b)) : undefined,
    };
  });

  const filterable = columns.filter((c) => c.filterValue);
  const filteringProperties = filterable.map((c) => ({
    key: c.id,
    propertyLabel: c.header,
    groupValuesLabel: t('table.valuesOf', { name: c.header }),
    operators: c.enumerated ? ['=', '!=', ':', '!:'] : [':', '!:', '=', '!=', '^'],
  }));
  const filteringOptions = useMemo(() => {
    const options: { propertyKey: string; value: string }[] = [];
    for (const c of filterable) {
      if (!c.enumerated) continue;
      const seen = new Set<string>();
      for (const item of props.items) {
        const v = c.filterValue!(item);
        if (v && !seen.has(v) && seen.size < 200) {
          seen.add(v);
          options.push({ propertyKey: c.id, value: v });
        }
      }
    }
    return options;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.items]);

  const sortColumn = props.defaultSort ? definitions.find((d) => d.id === props.defaultSort!.column) : undefined;
  const clearFilter = () => {
    actions.setPropertyFiltering(EMPTY_QUERY);
    setCurrentSet(null);
  };

  const { items, actions, filteredItemsCount, collectionProps, propertyFilterProps, paginationProps } = useCollection(props.items, {
    propertyFiltering: {
      filteringProperties,
      filteringOptions,
      filteringFunction: (item, query) => {
        if (query.tokens.length === 0) return true;
        const results = query.tokens.map((tk) => matchToken(item, tk, columns));
        return query.operation === 'or' ? results.some(Boolean) : results.every(Boolean);
      },
      empty: props.empty,
      noMatch: (
        <Box textAlign="center" color="inherit">
          <SpaceBetween size="xs">
            <b>{t('table.noMatch')}</b>
            <Button onClick={clearFilter}>{t('table.clearFilter')}</Button>
          </SpaceBetween>
        </Box>
      ),
    },
    sorting: sortColumn ? { defaultState: { sortingColumn: sortColumn, isDescending: props.defaultSort?.descending } } : {},
    pagination: { pageSize: prefs.pageSize },
  });

  const filtered = propertyFilterProps.query.tokens.length > 0;
  const total = props.items.length;
  const counter = props.selectedItems && props.selectedItems.length > 0
    ? `(${props.selectedItems.length}/${total})`
    : filtered ? `(${filteredItemsCount ?? 0}/${total})` : `(${total})`;

  const active = sets.find((s) => s.name === currentSet);
  const modified = active !== undefined && JSON.stringify(active.query) !== JSON.stringify(propertyFilterProps.query);
  const setOptions = sets.map((s) => ({ value: s.name, label: s.name }));

  const visibleIds = prefs.contentDisplay.filter((d) => d.visible).map((d) => d.id);
  const onWidths = (widths: readonly number[]) => {
    const next = { ...prefs.widths };
    visibleIds.forEach((id, i) => {
      if (widths[i] !== undefined) next[id] = widths[i];
    });
    setPrefs({ ...prefs, widths: next });
  };

  const preferences: CollectionPreferencesProps.Preferences = {
    pageSize: prefs.pageSize,
    wrapLines: prefs.wrapLines,
    stripedRows: prefs.stripedRows,
    contentDisplay: prefs.contentDisplay,
    stickyColumns: { first: prefs.stickyFirst, last: 0 },
  };

  return (
    <>
      <Table<T>
        {...collectionProps}
        variant="full-page"
        stickyHeader
        resizableColumns
        onColumnWidthsChange={(e) => onWidths(e.detail.widths)}
        wrapLines={prefs.wrapLines}
        stripedRows={prefs.stripedRows}
        stickyColumns={{ first: prefs.stickyFirst, last: 0 }}
        columnDisplay={prefs.contentDisplay}
        columnDefinitions={definitions}
        items={items}
        trackBy={props.trackBy}
        loading={props.loading}
        loadingText={t('common.loading')}
        selectionType={props.selectionType}
        selectedItems={props.selectedItems}
        onSelectionChange={props.onSelectionChange ? (e) => props.onSelectionChange!([...e.detail.selectedItems]) : undefined}
        ariaLabels={{
          selectionGroupLabel: t('table.selection'),
          allItemsSelectionLabel: () => t('table.selectAll'),
          itemSelectionLabel: (_, item) => {
            const head = columns.find((c) => c.isRowHeader);
            return head?.filterValue ? head.filterValue(item) : t('table.selectRow');
          },
        }}
        header={
          <Header variant="awsui-h1-sticky" counter={counter} description={props.description} actions={props.actions}>
            {props.title}
          </Header>
        }
        filter={
          <SpaceBetween size="xs">
            {props.toolbar}
            <PropertyFilter
              {...propertyFilterProps}
              onChange={(e) => {
                propertyFilterProps.onChange(e);
                if (e.detail.tokens.length === 0 && !active) setCurrentSet(null);
              }}
              filteringPlaceholder={t('table.filterPlaceholder')}
              filteringAriaLabel={t('table.filterPlaceholder')}
              countText={t('table.matches', { count: filteredItemsCount ?? 0 })}
              expandToViewport
              customControl={
                <Select
                  inlineLabelText={t('table.savedFilters')}
                  options={setOptions}
                  selectedOption={active ? { value: active.name, label: modified ? `${active.name} *` : active.name } : null}
                  placeholder={t('table.chooseFilterSet')}
                  empty={t('table.noFilterSet')}
                  onChange={(e) => {
                    const s = sets.find((x) => x.name === e.detail.selectedOption.value);
                    if (s) {
                      setCurrentSet(s.name);
                      actions.setPropertyFiltering(s.query);
                    }
                  }}
                />
              }
              customFilterActions={
                <ButtonDropdown
                  items={[
                    { id: 'clear', text: t('table.clearFilter'), disabled: !filtered && !active },
                    { id: 'save', text: t('table.saveFilterSet'), disabled: !filtered },
                    { id: 'update', text: t('table.updateFilterSet'), disabled: !active || !modified },
                    { id: 'delete', text: t('table.deleteFilterSet'), disabled: !active },
                  ]}
                  onItemClick={(e) => {
                    if (e.detail.id === 'clear') clearFilter();
                    else if (e.detail.id === 'save') {
                      setSetName('');
                      setSetDialog('save');
                    } else if (e.detail.id === 'update' && active) {
                      setSets(sets.map((s) => (s.name === active.name ? { ...s, query: propertyFilterProps.query } : s)));
                    } else if (e.detail.id === 'delete') setSetDialog('delete');
                  }}
                >
                  {t('table.filterActions')}
                </ButtonDropdown>
              }
            />
          </SpaceBetween>
        }
        pagination={<Pagination {...paginationProps} />}
        preferences={
          <CollectionPreferences
            title={t('table.preferences')}
            confirmLabel={t('action.save')}
            cancelLabel={t('action.cancel')}
            preferences={preferences}
            onConfirm={(e) => {
              const p = e.detail;
              setPrefs({
                ...prefs,
                pageSize: p.pageSize ?? prefs.pageSize,
                wrapLines: p.wrapLines ?? prefs.wrapLines,
                stripedRows: p.stripedRows ?? prefs.stripedRows,
                stickyFirst: p.stickyColumns?.first ?? prefs.stickyFirst,
                contentDisplay: p.contentDisplay ? [...p.contentDisplay] : prefs.contentDisplay,
              });
            }}
            pageSizePreference={{
              title: t('table.pageSize'),
              options: [20, 50, 100, 200].map((n) => ({ value: n, label: t('table.rows', { count: n }) })),
            }}
            wrapLinesPreference={{ label: t('table.wrapLines'), description: t('table.wrapLinesHelp') }}
            stripedRowsPreference={{ label: t('table.stripedRows'), description: t('table.stripedRowsHelp') }}
            stickyColumnsPreference={{
              firstColumns: {
                title: t('table.stickyFirst'),
                description: t('table.stickyFirstHelp'),
                options: [
                  { label: t('table.none'), value: 0 },
                  { label: t('table.firstColumn'), value: 1 },
                  { label: t('table.firstTwoColumns'), value: 2 },
                ],
              },
            }}
            contentDisplayPreference={{
              title: t('table.columns'),
              description: t('table.columnsHelp'),
              options: columns.map((c) => ({ id: c.id, label: c.header, alwaysVisible: c.isRowHeader })),
            }}
          />
        }
      />
      {setDialog === 'save' && (
        <Modal
          visible
          onDismiss={() => setSetDialog(null)}
          header={t('table.saveFilterSet')}
          footer={
            <Box float="right">
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="link" onClick={() => setSetDialog(null)}>{t('action.cancel')}</Button>
                <Button
                  variant="primary"
                  disabled={!setName.trim()}
                  onClick={() => {
                    const name = setName.trim();
                    setSets([...sets.filter((s) => s.name !== name), { name, query: propertyFilterProps.query }]);
                    setCurrentSet(name);
                    setSetDialog(null);
                  }}
                >
                  {t('action.save')}
                </Button>
              </SpaceBetween>
            </Box>
          }
        >
          <FormField label={t('table.filterSetName')} description={t('table.filterSetHelp')}>
            <Input value={setName} onChange={(e) => setSetName(e.detail.value)} autoFocus />
          </FormField>
        </Modal>
      )}
      {setDialog === 'delete' && active && (
        <Modal
          visible
          onDismiss={() => setSetDialog(null)}
          header={t('table.deleteFilterSet')}
          footer={
            <Box float="right">
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="link" onClick={() => setSetDialog(null)}>{t('action.cancel')}</Button>
                <Button
                  variant="primary"
                  onClick={() => {
                    setSets(sets.filter((s) => s.name !== active.name));
                    setCurrentSet(null);
                    setSetDialog(null);
                  }}
                >
                  {t('action.delete')}
                </Button>
              </SpaceBetween>
            </Box>
          }
        >
          {t('table.deleteFilterSetConfirm', { name: active.name })}
        </Modal>
      )}
    </>
  );
}

/**
 * Header buttons: a dropdown with the operations on the selected row (or the shown object) and,
 * optionally, a primary button. Keeps the header to two buttons however many operations there are.
 */
export function ActionsMenu({
  items,
  onItemClick,
  primary,
  extra,
}: {
  items: { id: string; text: string; disabled?: boolean; disabledReason?: string }[];
  onItemClick: (id: string) => void;
  primary?: ReactNode;
  extra?: ReactNode;
}) {
  return (
    <SpaceBetween direction="horizontal" size="xs">
      {extra}
      {items.length > 0 && (
        <ButtonDropdown items={items} onItemClick={(e) => onItemClick(e.detail.id)}>
          {t('table.actions')}
        </ButtonDropdown>
      )}
      {primary}
    </SpaceBetween>
  );
}
