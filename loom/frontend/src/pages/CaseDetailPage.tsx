import { useState } from 'react';
import { useParams } from 'react-router';
import {
  Box, Stack, Text, Badge, Group, Button, Paper, Skeleton, ActionIcon,
  Timeline, Collapse, UnstyledButton, ThemeIcon,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Check, Route, Pencil, MessageSquareWarning,
  Database, Bot, ChevronDown, ChevronRight, Siren, User, Workflow, Tags,
} from 'lucide-react';
import { getCase, getCaseLogs, reviewCase, meetsRole, updateCaseMetadata, errorMessage, type Case, type AuditLog, type ReviewAction } from '../api/client';
import { useMe } from '../api/useMe';
import { stateMeta, ACTION_COLOR, PRIORITY_COLOR } from '../lib/colors';
import { LoadErrorState } from '../components/LoadErrorState';
import { ReviewTrace } from '../components/ReviewTrace';
import { SectionCard } from '../components/SectionCard';
import { MetadataEditor, metadataToRows, rowsToMetadata, valueText, type MetadataRow } from '../components/MetadataEditor';
import classes from './CaseDetailPage.module.css';


// Timeline bullet by WHO acted: a person for a human (green), the agent bot
// (cyan), or the platform (gray) for automated/system rows. Derived from the
// actor — 'agent'/'system' are the non-human sentinels, anything else is a human.
function whoStyle(actor: string): { Icon: typeof Bot; color: string } {
  if (actor === 'agent') return { Icon: Bot, color: 'cyan' };
  if (actor === 'system') return { Icon: Workflow, color: 'gray' };
  return { Icon: User, color: 'green' };
}
// The state a verdict resolves to (for the optimistic update before the
// workflow applies it). Mirrors ReviewAction.resultingState on the server —
// keep the two in step.
const STATE_BY_ACTION: Record<ReviewAction, string> = {
  agree: 'agreed',
  dispute: 'disputed',
  escalate: 'escalated',
};

/**
 * One timeline entry. For agent (`reviewed`) verdicts this is the teaching
 * surface: alongside the durable Temporal workflow event (when it ran) it can
 * expand to the agent's reasoning trace (what it did and why) — the two layers
 * side by side. Manual overrides render as a plain line.
 */
function TimelineDecision({ log }: { log: AuditLog }) {
  const [open, { toggle }] = useDisclosure(false);
  const reviewed = log.action === 'reviewed';
  const d = log.details ?? {};
  const trace = d.trace;

  return (
    <>
      <Group gap="xs" mb={reviewed && (d.action || d.priority) ? 6 : 0}>
        <Text size="xs" c="dimmed" ff="monospace">
          {`${log.action}${log.actor ? ` · ${log.actor}` : ''}${log.channel && log.channel !== log.actor ? ` · ${log.channel}` : ''}`}
        </Text>
        <Text size="xs" c="dimmed">&middot;</Text>
        <Text size="xs" c="dimmed">{new Date(log.timestamp).toLocaleString()}</Text>
      </Group>
      {reviewed && (d.action || d.priority) && (
        <Group gap={6}>
          {d.action && <Badge size="xs" variant="dot" color={ACTION_COLOR[d.action] ?? 'gray'}>{d.action}</Badge>}
          {d.priority && <Badge size="xs" variant="light" color={PRIORITY_COLOR[d.priority] ?? 'gray'}>{d.priority}</Badge>}
        </Group>
      )}
      {trace && (
        <Box mt={6}>
          <UnstyledButton onClick={toggle}>
            <Group gap={4}>
              <Box c="cyan.5" display="flex">{open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}</Box>
              <Text size="xs" c="cyan.5" fw={500}>{open ? 'Hide' : 'Show'} reasoning</Text>
            </Group>
          </UnstyledButton>
          <Collapse in={open}>
            <ReviewTrace trace={trace} />
          </Collapse>
        </Box>
      )}
    </>
  );
}

/**
 * The supervised's metadata bag. Read view renders each key/value
 * (priority as the same badge the timeline uses); members can edit in place
 * while the supervised is still open — closed items are records, refused by the
 * service on every surface. Saving replaces the object wholesale, while the
 * daemon's status writes re-merge `priority` without clobbering other keys.
 */
function MetadataSection({ supervised, canEdit }: { supervised: Case; canEdit: boolean }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [rows, setRows] = useState<MetadataRow[]>([]);
  const entries = Object.entries(supervised.metadata ?? {});

  const mutation = useMutation({
    mutationFn: (metadata: Record<string, unknown>) => updateCaseMetadata(supervised.id, metadata),
    onSuccess: (updated) => {
      queryClient.setQueryData(['cases', supervised.id], updated);
      queryClient.setQueryData(['cases'], (old: Case[] | undefined) =>
        old?.map((i) => (i.id === updated.id ? updated : i))
      );
      queryClient.invalidateQueries({ queryKey: ['cases', supervised.id, 'logs'] });
      setEditing(false);
    },
  });

  if (entries.length === 0 && !canEdit) return null;

  const startEditing = () => {
    mutation.reset();
    setRows(entries.length > 0 ? metadataToRows(supervised.metadata) : [{ key: '', value: '' }]);
    setEditing(true);
  };

  return (
    <Stack gap={8}>
      <Group gap={6} wrap="nowrap">
        <Box c="dimmed" display="flex"><Tags size={13} /></Box>
        <Text size="xs" fw={600} tt="uppercase" c="dimmed" lts={0.5} flex={1}>Metadata</Text>
        {canEdit && !editing && entries.length > 0 && (
          <ActionIcon variant="subtle" color="gray" size="sm" aria-label="Edit metadata" onClick={startEditing}>
            <Pencil size={13} />
          </ActionIcon>
        )}
      </Group>
      {editing ? (
        <Stack gap="xs">
          <MetadataEditor rows={rows} onChange={setRows} />
          {mutation.error && (
            <Text size="xs" c="red">{errorMessage(mutation.error, 'Failed to update metadata')}</Text>
          )}
          <Group gap="xs">
            <Button size="xs" variant="light" loading={mutation.isPending} onClick={() => mutation.mutate(rowsToMetadata(rows))}>
              Save
            </Button>
            <Button size="xs" variant="subtle" color="gray" disabled={mutation.isPending} onClick={() => setEditing(false)}>
              Cancel
            </Button>
          </Group>
        </Stack>
      ) : entries.length > 0 ? (
        <Stack gap={5}>
          {entries.map(([key, value]) => (
            <Group key={key} gap={8} wrap="nowrap" align="baseline">
              <Text size="xs" ff="monospace" c="dimmed">{key}</Text>
              <Box
                style={{
                  flex: 1,
                  minWidth: 12,
                  borderBottom: '1px dotted var(--mantine-color-default-border)',
                  transform: 'translateY(-3px)',
                }}
              />
              {key === 'priority' && typeof value === 'string' && PRIORITY_COLOR[value] ? (
                <Badge size="xs" variant="light" color={PRIORITY_COLOR[value]}>{value}</Badge>
              ) : (
                <Text size="sm" ta="right" style={{ wordBreak: 'break-word' }}>
                  {valueText(value)}
                </Text>
              )}
            </Group>
          ))}
        </Stack>
      ) : (
        <Button variant="subtle" size="xs" leftSection={<Pencil size={13} />} onClick={startEditing} w="fit-content">
          Add metadata
        </Button>
      )}
    </Stack>
  );
}

export function CaseDetailPage() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();

  const { data: supervised, isPending: itemPending, error, refetch } = useQuery({
    queryKey: ['cases', id],
    queryFn: () => getCase(id!),
    enabled: !!id,
    // Status changes asynchronously (the agent or a human signals processCase),
    // so poll like the board does instead of waiting for a manual refresh.
    // Polling a dead API re-enters pending each cycle (v5 refetches an errored
    // query back to pending), churning skeleton ⇄ error — so the poll stops on
    // error and the error surface owns the retry.
    refetchInterval: (query) =>
      (query.state.status === 'error' || !query.state.data ? false : 5000),
  });

  const { data: me } = useMe();
  const canWrite = meetsRole(me?.roles ?? [], 'member');

  const { data: logs = [] } = useQuery({
    queryKey: ['cases', id, 'logs'],
    queryFn: () => getCaseLogs(id!),
    enabled: !!id,
    refetchInterval: (query) => (query.state.status === 'error' || !supervised ? false : 5000),
  });

  const mutation = useMutation({
    mutationFn: ({ action }: { action: ReviewAction }) => reviewCase(id!, action),
    onMutate: ({ action }) => {
      const state = STATE_BY_ACTION[action];
      queryClient.setQueryData(['cases', id], (old: Case | undefined) =>
        old ? { ...old, state } : old
      );
      queryClient.setQueryData(['cases'], (old: Case[] | undefined) =>
        old?.map((c) => (c.id === id ? { ...c, state } : c))
      );
    },
    onSuccess: () => {
      // Refetch after Temporal processes the signal and writes audit logs
      setTimeout(() => {
        queryClient.invalidateQueries({ queryKey: ['cases', id] });
        queryClient.invalidateQueries({ queryKey: ['cases', id, 'logs'] });
      }, 1500);
    },
  });


  // A failed primary load is an error, never an empty page.
  if (error) {
    return (
      <LoadErrorState
        title="Can't load this supervised"
        detail={errorMessage(error, 'The API did not respond')}
        onRetry={() => refetch()}
      />
    );
  }

  if (itemPending || !supervised) {
    return (
      <Stack>
        <Skeleton height={22} width={60} />
        <Paper withBorder style={{ overflow: 'hidden' }}>
          <Stack gap="md" p="lg">
            <Group justify="space-between" align="flex-start">
              <Stack gap={6} flex={1}>
                <Skeleton height={18} width="40%" />
                <Skeleton height={10} width={320} />
              </Stack>
              <Skeleton height={26} width={110} radius="xl" />
            </Group>
            <Skeleton height={12} />
            <Skeleton height={12} width="70%" />
          </Stack>
          <Group px="lg" py="sm" style={{ borderTop: '1px solid var(--mantine-color-default-border)' }}>
            <Skeleton height={36} width={120} />
            <Skeleton height={36} width={120} />
          </Group>
        </Paper>
        <Paper withBorder style={{ overflow: 'hidden' }}>
          <Group gap="sm" p="sm" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
            <Skeleton height={32} width={4} radius={4} />
            <Skeleton height={14} width={140} />
          </Group>
          <Box p="md">
            <Skeleton height={120} />
          </Box>
        </Paper>
      </Stack>
    );
  }

  const state = stateMeta(supervised.state);
  const StateIcon = state.icon;
  // Watching and disputed are the open half - see CaseState.isOpen. A dispute
  // stays reviewable on purpose: loom said it disagreed and is waiting.
  const canReview = supervised.state === 'watching' || supervised.state === 'disputed';
  const showMetadataRail = Object.keys(supervised.metadata ?? {}).length > 0 || (canWrite && canReview);
  // The closed-state verdict: who applied the terminal status, from the log the
  // workflow wrote. Falls back to updated_at if the log hasn't landed yet.
  const verdictLog = [...logs].reverse().find((l) => ['agree', 'escalate', 'lapsed'].includes(l.action));
  const reviewPriority = typeof supervised.metadata?.priority === 'string' ? supervised.metadata.priority : undefined;

  return (
    <Stack>
      <Paper
        withBorder
        style={{ overflow: 'hidden', borderLeft: `4px solid var(--mantine-color-${state.color}-6)` }}
      >
        <Stack gap="md" p="lg">
          <Group justify="space-between" align="flex-start">
            <Stack gap={4} flex={1}>
              <Text fw={700} size="lg">{supervised.title}</Text>
              <Text size="xs" c="dimmed" ff="monospace">
                {`loom-case-${supervised.id.split('-')[0]} · created ${new Date(supervised.created_at).toLocaleString()} · updated ${new Date(supervised.updated_at).toLocaleString()}`}
              </Text>
            </Stack>
            <Badge
              color={state.color}
              variant="light"
              size="lg"
              leftSection={<StateIcon size={12} />}
            >
              {state.label}
            </Badge>
          </Group>

          <Box className={classes.split}>
            <Box flex={1} miw={0}>
              <Stack gap="sm">
                <Box>
                  <Text size="xs" fw={600} tt="uppercase" c="dimmed" lts={0.5}>Anvil decided</Text>
                  {supervised.anvil_decision ? (
                    <Group gap={6} mt={4}>
                      <Badge
                        size="sm"
                        variant="light"
                        color={ACTION_COLOR[supervised.anvil_decision.action] ?? 'gray'}
                      >
                        {supervised.anvil_decision.action}
                      </Badge>
                      <Text size="sm">{supervised.anvil_decision.rationale}</Text>
                    </Group>
                  ) : (
                    <Text size="sm" c="dimmed" mt={4}>
                      Nothing yet - this is what the deadline is measured against.
                    </Text>
                  )}
                </Box>
                <Box>
                  <Text size="xs" fw={600} tt="uppercase" c="dimmed" lts={0.5}>loom said</Text>
                  {supervised.review ? (
                    <Group gap={6} mt={4}>
                      <Badge size="sm" variant="light" color={ACTION_COLOR[supervised.review.action] ?? 'gray'}>
                        {supervised.review.action}
                      </Badge>
                      <Text size="sm">{supervised.review.rationale}</Text>
                    </Group>
                  ) : (
                    <Text size="sm" c="dimmed" mt={4}>Not reviewed yet.</Text>
                  )}
                </Box>
                <Text size="xs" c="dimmed" ff="monospace">
                  Anvil item {supervised.anvil_item_id} &middot; review due{' '}
                  {new Date(supervised.sla_due_at).toLocaleString()}
                </Text>
              </Stack>
            </Box>
            {showMetadataRail && (
              <Box className={classes.meta}>
                <MetadataSection supervised={supervised} canEdit={canWrite && canReview} />
              </Box>
            )}
          </Box>
        </Stack>

        {canReview && canWrite && (
          <Group
            gap="xs"
            px="lg"
            py="sm"
            style={{ borderTop: '1px solid var(--mantine-color-default-border)', background: 'var(--mantine-color-default-hover)' }}
          >
            <Group gap={6} wrap="nowrap" flex={1}>
              <Text size="xs" c="dimmed">
                Awaiting a verdict, due {new Date(supervised.sla_due_at).toLocaleString()}
              </Text>
            </Group>
            {/* The verdicts move as one unit: wrapping them individually
                strands Escalate on its own line. `0 1 120px` keeps the desktop
                width exactly and lets them shrink to share a narrow row. */}
            <Group gap="xs" wrap="nowrap">
              <Button
                variant="light"
                color="green"
                flex="0 1 120px"
                leftSection={<Check size={14} />}
                onClick={() => mutation.mutate({ action: 'agree' })}
              >
                Agree
              </Button>
              {/* Only from `watching`: disputing a case loom has already
                  disputed would say nothing new. */}
              {supervised.state === 'watching' && (
                <Button
                  variant="light"
                  color="orange"
                  flex="0 1 120px"
                  leftSection={<MessageSquareWarning size={14} />}
                  onClick={() => mutation.mutate({ action: 'dispute' })}
                >
                  Dispute
                </Button>
              )}
              <Button
                variant="light"
                color="red"
                flex="0 1 120px"
                leftSection={<Siren size={14} />}
                onClick={() => mutation.mutate({ action: 'escalate' })}
              >
                Escalate
              </Button>
            </Group>
          </Group>
        )}

        {!canReview && (
          <Group
            gap={8}
            px="lg"
            py="sm"
            style={{ borderTop: '1px solid var(--mantine-color-default-border)', background: 'var(--mantine-color-default-hover)' }}
          >
            <Box c={`${state.color}.5`} display="flex"><StateIcon size={15} /></Box>
            <Text size="sm" fw={500}>{state.label}</Text>
            {verdictLog && (
              <>
                <Text size="xs" c="dimmed">·</Text>
                <Text size="xs" ff="monospace" c="dimmed">{verdictLog.actor}</Text>
              </>
            )}
            {reviewPriority && (
              <>
                <Text size="xs" c="dimmed">·</Text>
                <Badge size="xs" variant="light" color={PRIORITY_COLOR[reviewPriority] ?? 'gray'}>{reviewPriority}</Badge>
              </>
            )}
            <Text size="xs" c="dimmed">·</Text>
            <Text size="xs" c="dimmed">{new Date(verdictLog?.timestamp ?? supervised.updated_at).toLocaleString()}</Text>
          </Group>
        )}
      </Paper>

      {logs.length > 0 && (
        <SectionCard icon={<Route size={14} />} title="Haven Workflows">
          <Box p="md">
            <Timeline
              active={logs.length - 1}
              bulletSize={20}
              lineWidth={2}
              color="gray"
              styles={{ itemBullet: { backgroundColor: 'transparent', border: 0 } }}
            >
              {logs.map((log) => {
                const who = whoStyle(log.actor);
                return (
                  <Timeline.Item
                    key={log.id}
                    bullet={
                      <ThemeIcon size={20} radius="xl" color={who.color}>
                        <who.Icon size={12} />
                      </ThemeIcon>
                    }
                    title={<Text size="sm" fw={500}>{log.message}</Text>}
                  >
                    <TimelineDecision log={log} />
                  </Timeline.Item>
                );
              })}
            </Timeline>
          </Box>
        </SectionCard>
      )}


      {/* Haven Records */}
      <SectionCard icon={<Database size={16} />} title="Haven Records">
        <Box px="md" pt="xs" pb="xs">
          <Text size="xs" c="dimmed">Persists supervised data to the database.</Text>
        </Box>
        <Box px="md" pb="md">
          <Group gap="sm" align="stretch">
            <Paper withBorder p="sm" radius="sm" flex="2 1 110px">
              <Text size="xs" c="dimmed">Storage</Text>
              <Text size="sm" fw={500}>PostgreSQL</Text>
            </Paper>
            <Paper withBorder p="sm" radius="sm" flex="3 1 110px">
              <Text size="xs" c="dimmed">Schema</Text>
              <Text size="sm" ff="monospace" fw={500}>loom</Text>
            </Paper>
            <Paper withBorder p="sm" radius="sm" flex="3 1 160px">
              <Text size="xs" c="dimmed">Table</Text>
              <Text size="sm" ff="monospace" fw={500}>items</Text>
            </Paper>
          </Group>
        </Box>
      </SectionCard>
    </Stack>
  );
}
