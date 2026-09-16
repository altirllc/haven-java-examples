import {
  Stack, Text, Paper, Group, Badge, Button, SimpleGrid,
  Skeleton,
} from '@mantine/core';
import { Link, useOutletContext } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Plus, Eye } from 'lucide-react';
import { listCases, errorMessage, type Case } from '../api/client';
import { EmptyState } from '../components/EmptyState';
import { LoadErrorState } from '../components/LoadErrorState';
import { PageHeader } from '../components/PageHeader';
import { stateMeta } from '../lib/colors';
import classes from './CasesPage.module.css';

const DESCRIPTION = "Every Anvil item loom is supervising. An autonomous agent reviews what Anvil's triager "
  + 'decided and agrees, disputes or escalates — and a case that nobody reviews before its deadline lapses.';

function CaseCard({ supervised, to }: { supervised: Case; to: string }) {
  const state = stateMeta(supervised.state);

  return (
    <Paper
      component={Link}
      to={to}
      withBorder
      p="md"
      className={classes.card}
      style={{ borderLeft: `3px solid var(--mantine-color-${state.color}-6)` }}
    >
      <Group justify="space-between" mb="xs">
        <Text fw={600} size="sm" lineClamp={1} flex={1}>
          {supervised.title}
        </Text>
        <Badge
          color={`${state.color}.6`}
          variant="dot"
          size="sm"
        >
          {supervised.state}
        </Badge>
      </Group>
      <Text size="xs" c="dimmed" lineClamp={1} mb="xs">
        Anvil decided:{' '}
        {supervised.anvil_decision
          ? `${supervised.anvil_decision.action} — ${supervised.anvil_decision.rationale}`
          : 'nothing yet'}
      </Text>
      <Text size="xs" c="dimmed" ff="monospace">
        due {new Date(supervised.sla_due_at).toLocaleString()}
      </Text>
    </Paper>
  );
}

export function CasesPage() {
  const { openWatch, canWrite } = useOutletContext<{ openWatch: () => void; canWrite: boolean }>();

  // Polling a dead API re-enters pending each cycle (v5 refetches an errored
  // query back to pending), churning skeleton ⇄ error — so the poll stops on
  // error and the error surface owns the retry.
  const { data: cases = [], isPending, error, refetch } = useQuery({
    queryKey: ['cases'],
    queryFn: listCases,
    refetchInterval: (query) => (query.state.status === 'error' ? false : 5000),
  });

  // A failed primary load is an error, never an empty page.
  if (error) {
    return (
      <LoadErrorState
        title="Can't reach loom"
        detail={errorMessage(error, 'The API did not respond')}
        onRetry={() => refetch()}
      />
    );
  }

  // Open on the left, settled on the right — `disputed` counts as open,
  // because loom said something and is still waiting to see what happens.
  const open = cases.filter((c) => c.state === 'watching' || c.state === 'disputed');
  const settled = cases.filter((c) => c.state !== 'watching' && c.state !== 'disputed');

  return (
    <>
      <Stack gap="lg">
        {/* The title and description are known at build time — only the cards are
            waiting on the API, so only the cards are placeheld. */}
        <PageHeader
          title="Cases"
          description={DESCRIPTION}
          action={canWrite && (
            <Button leftSection={<Plus size={16} />} onClick={() => openWatch()}>Watch an item</Button>
          )}
        />
        {isPending ? (
          <>
            <Group gap="xs">
              <Skeleton height={12} width={110} />
              <Skeleton height={18} circle />
            </Group>
            <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
              {Array.from({ length: 6 }, (_, i) => (
                <Paper key={i} withBorder p="md">
                  <Group justify="space-between" mb="xs">
                    <Skeleton height={14} width="55%" />
                    <Skeleton height={16} width={64} radius="xl" />
                  </Group>
                  <Skeleton height={10} mb={6} />
                  <Skeleton height={10} width="80%" mb="xs" />
                  <Skeleton height={10} width={70} />
                </Paper>
              ))}
            </SimpleGrid>
          </>
        ) : cases.length === 0 ? (
          <EmptyState icon={<Eye size={48} strokeWidth={1.5} />} message="Nothing under supervision yet" />
        ) : (
          <>
            {open.length > 0 && (
              <>
                <Group gap="xs">
                  <Text size="xs" fw={600} tt="uppercase" c="dimmed" ff="monospace">
                    Awaiting a verdict
                  </Text>
                  <Badge size="sm" color="yellow" miw="var(--badge-height)" px={2}>{open.length}</Badge>
                </Group>
                <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
                  {open.map((supervised) => (
                    <CaseCard
                      key={supervised.id}
                      supervised={supervised}
                      to={`/${supervised.id}`}
                    />
                  ))}
                </SimpleGrid>
              </>
            )}

            {settled.length > 0 && (
              <>
                <Group gap="xs" mt={open.length > 0 ? 'md' : undefined}>
                  <Text size="xs" fw={600} tt="uppercase" c="dimmed" ff="monospace">
                    Settled
                  </Text>
                  <Badge size="sm" color="gray" miw="var(--badge-height)" px={2}>{settled.length}</Badge>
                </Group>
                <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
                  {settled.map((supervised) => (
                    <CaseCard
                      key={supervised.id}
                      supervised={supervised}
                      to={`/${supervised.id}`}
                    />
                  ))}
                </SimpleGrid>
              </>
            )}
          </>
        )}
      </Stack>
    </>
  );
}
