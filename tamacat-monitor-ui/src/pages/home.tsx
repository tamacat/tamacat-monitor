import { useEffect, useState } from 'react';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import Cards from '@cloudscape-design/components/cards';
import Container from '@cloudscape-design/components/container';
import ContentLayout from '@cloudscape-design/components/content-layout';
import Header from '@cloudscape-design/components/header';
import Link from '@cloudscape-design/components/link';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Toggle from '@cloudscape-design/components/toggle';

import { CardStatus, serverHref, SeverityBreakdown, SummaryTiles, type ServerCard, type Summary } from '../components/Dashboard';
import { api, errorText } from '../lib/api';
import { boot, formatDateTime, hasRole, t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import { Shell, useFlash } from '../shell/Shell';

interface Overview {
  servers: ServerCard[];
  totals: Summary;
  generatedAt: string;
}

const REFRESH_MS = 60_000;

function HomePage() {
  const [data, setData] = useState<Overview | null>(null);
  const [loading, setLoading] = useState(false);
  const [auto, setAuto] = useState(true);
  const [flash, push] = useFlash();
  const scoped = !hasRole('SYSTEM_ADMIN') && !hasRole('SERVER_ADMIN');

  const load = async () => {
    setLoading(true);
    try {
      setData(await api.get<Overview>('/api/dashboard'));
    } catch (e) {
      push('error', errorText(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    if (!auto) return;
    const id = window.setInterval(() => void load(), REFRESH_MS);
    return () => window.clearInterval(id);
  }, [auto]);

  return (
    <Shell active="/" flash={flash}>
      <ContentLayout
        header={
          <Header
            variant="h1"
            description={
              (scoped ? t('dashboard.scopedHelp') + ' ' : '') +
              (data ? t('dashboard.updated', { time: formatDateTime(data.generatedAt) }) : '')
            }
            actions={
              <SpaceBetween direction="horizontal" size="m" alignItems="center">
                <Toggle checked={auto} onChange={(e) => setAuto(e.detail.checked)}>{t('dashboard.autoRefresh')}</Toggle>
                <Button iconName="refresh" loading={loading} onClick={() => void load()}>{t('action.reload')}</Button>
              </SpaceBetween>
            }
          >
            {t('home.welcome', { name: boot.user?.displayName ?? '' })}
          </Header>
        }
      >
        <SpaceBetween size="l">
          <Container header={<Header variant="h2">{t('dashboard.total')}</Header>}>
            {data ? (
              <SpaceBetween size="l">
                <SummaryTiles s={data.totals} problemsHref={url('/problems')} />
                <SeverityBreakdown counts={data.totals.problemsBySeverity} />
              </SpaceBetween>
            ) : (
              <Box color="text-body-secondary">{t('common.loading')}</Box>
            )}
          </Container>

          <Cards
            loading={!data && loading}
            loadingText={t('common.loading')}
            items={data?.servers ?? []}
            trackBy="serverId"
            cardsPerRow={[{ cards: 1 }, { minWidth: 600, cards: 2 }, { minWidth: 1000, cards: 3 }]}
            header={<Header variant="h2" counter={data ? `(${data.servers.length})` : undefined}>{t('dashboard.servers')}</Header>}
            empty={
              <Box textAlign="center" color="text-body-secondary">
                {hasRole('SYSTEM_ADMIN') ? <Link href={url('/servers')}>{t('dashboard.noServerAdmin')}</Link> : t('dashboard.noServer')}
              </Box>
            }
            cardDefinition={{
              header: (c) => <Link href={serverHref(c.serverId)} fontSize="heading-m">{c.name}</Link>,
              sections: [
                { id: 'status', header: t('field.status'), content: (c) => <CardStatus c={c} /> },
                { id: 'version', header: t('field.zabbixVersion'), content: (c) => c.zabbixVersion ?? '-' },
                {
                  id: 'problems',
                  header: t('nav.problems'),
                  content: (c) => (c.summary ? <SeverityBreakdown counts={c.summary.problemsBySeverity} compact /> : '-'),
                },
                {
                  id: 'hosts',
                  header: t('nav.hosts'),
                  content: (c) =>
                    c.summary
                      ? t('dashboard.hostsLine', { monitored: c.summary.hostsMonitored, unavailable: c.summary.hostsUnavailable, disabled: c.summary.hostsDisabled })
                      : '-',
                },
                {
                  id: 'items',
                  header: t('nav.items'),
                  content: (c) => (c.summary ? t('dashboard.itemsLine', { enabled: c.summary.itemsEnabled, unsupported: c.summary.itemsUnsupported }) : '-'),
                },
              ],
            }}
          />
        </SpaceBetween>
      </ContentLayout>
    </Shell>
  );
}

mount(<HomePage />);
