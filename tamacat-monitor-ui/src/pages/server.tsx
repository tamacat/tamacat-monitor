import { useEffect, useState } from 'react';
import Box from '@cloudscape-design/components/box';
import Button from '@cloudscape-design/components/button';
import ColumnLayout from '@cloudscape-design/components/column-layout';
import Container from '@cloudscape-design/components/container';
import ContentLayout from '@cloudscape-design/components/content-layout';
import Header from '@cloudscape-design/components/header';
import Link from '@cloudscape-design/components/link';
import SpaceBetween from '@cloudscape-design/components/space-between';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Table from '@cloudscape-design/components/table';

import { CardStatus, SeverityBreakdown, SummaryTiles, type ServerCard } from '../components/Dashboard';
import { api, errorText } from '../lib/api';
import { formatDateTime, t, url } from '../lib/boot';
import { mount } from '../lib/mount';
import { duration, label, severityLabel, severityStatus } from '../lib/zabbix';
import { Shell, useFlash } from '../shell/Shell';

interface Detail {
  card: ServerCard;
  problems: { eventId: number; name: string; severity: number; clock: string; acknowledged: boolean; hosts: string[] }[];
  unavailableHosts: { hostId: number; name: string; interfaceType: number; error: string }[];
}

function ServerDashboardPage() {
  const id = Number(new URLSearchParams(window.location.search).get('id'));
  const [d, setD] = useState<Detail | null>(null);
  const [loading, setLoading] = useState(false);
  const [flash, push] = useFlash();

  const load = async () => {
    setLoading(true);
    try {
      setD(await api.get<Detail>(`/api/dashboard/servers/${id}`));
    } catch (e) {
      push('error', errorText(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), 60_000);
    return () => window.clearInterval(timer);
  }, [id]);

  const c = d?.card;
  return (
    <Shell active="/" flash={flash} breadcrumbs={[{ text: c?.name ?? '...', href: `/server?id=${id}` }]}>
      <ContentLayout
        header={
          <Header
            variant="h1"
            description={c ? `Zabbix ${c.zabbixVersion ?? '-'} / ${c.collectedAt ? t('dashboard.updated', { time: formatDateTime(c.collectedAt) }) : ''}` : ''}
            actions={<Button iconName="refresh" loading={loading} onClick={() => void load()}>{t('action.reload')}</Button>}
          >
            {c?.name ?? t('common.loading')}
          </Header>
        }
      >
        {c && (
          <SpaceBetween size="l">
            <Container header={<Header variant="h2" actions={<CardStatus c={c} />}>{t('dashboard.summary')}</Header>}>
              {c.summary ? (
                <SpaceBetween size="l">
                  <SummaryTiles s={c.summary} problemsHref={url(`/problems`)} />
                  <SeverityBreakdown counts={c.summary.problemsBySeverity} />
                  <ColumnLayout columns={3} variant="text-grid">
                    <div><Box variant="awsui-key-label">{t('dashboard.hostsDisabled')}</Box>{c.summary.hostsDisabled}</div>
                    <div><Box variant="awsui-key-label">{t('dashboard.hostsInMaintenance')}</Box>{c.summary.hostsInMaintenance}</div>
                    <div><Box variant="awsui-key-label">{t('dashboard.itemsEnabled')}</Box>{c.summary.itemsEnabled}</div>
                  </ColumnLayout>
                </SpaceBetween>
              ) : (
                <Box color="text-body-secondary">{t('dashboard.noData')}</Box>
              )}
            </Container>

            <Table
              header={<Header variant="h2" counter={`(${d.problems.length})`} description={t('dashboard.topProblemsHelp')} actions={<Link href={url('/problems')}>{t('dashboard.allProblems')}</Link>}>{t('dashboard.topProblems')}</Header>}
              items={d.problems}
              trackBy="eventId"
              empty={<Box textAlign="center"><StatusIndicator type="success">{t('dashboard.noProblem')}</StatusIndicator></Box>}
              columnDefinitions={[
                { id: 'sev', header: t('field.severity'), cell: (p) => <StatusIndicator type={severityStatus(p.severity)}>{severityLabel(p.severity)}</StatusIndicator> },
                { id: 'name', header: t('field.problem'), cell: (p) => p.name, isRowHeader: true },
                { id: 'hosts', header: t('field.host'), cell: (p) => p.hosts.join(', ') },
                { id: 'since', header: t('field.since'), cell: (p) => formatDateTime(p.clock) },
                { id: 'dur', header: t('field.duration'), cell: (p) => duration(p.clock) },
                { id: 'ack', header: t('field.acknowledged'), cell: (p) => (p.acknowledged ? t('common.yes') : t('common.no')) },
              ]}
            />

            <Table
              header={<Header variant="h2" counter={`(${d.unavailableHosts.length})`}>{t('dashboard.unavailableHosts')}</Header>}
              items={d.unavailableHosts}
              trackBy="hostId"
              empty={<Box textAlign="center"><StatusIndicator type="success">{t('dashboard.allAvailable')}</StatusIndicator></Box>}
              columnDefinitions={[
                { id: 'name', header: t('field.host'), cell: (h) => h.name, isRowHeader: true },
                { id: 'type', header: t('field.type'), cell: (h) => label('interface', h.interfaceType) },
                { id: 'error', header: t('dashboard.error'), cell: (h) => h.error },
              ]}
            />
          </SpaceBetween>
        )}
      </ContentLayout>
    </Shell>
  );
}

mount(<ServerDashboardPage />);
