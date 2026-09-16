import { Stack, Group, Text, Badge, ScrollArea, Paper, Box, Skeleton } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Activity } from 'lucide-react';
import { getAgentActivity, type AgentAction } from '../api/client';
import { ACTION_COLOR, PRIORITY_COLOR } from '../lib/colors';
import { relativeTime } from '../lib/format';
import classes from './Layout.module.css';

function ActionRow({ d, onClick }: { d: AgentAction; onClick: () => void }) {
  return (
    <Paper component="button" type="button" withBorder p="xs" radius="sm" className={classes.clickable} onClick={onClick}>
      <Group justify="space-between" gap="xs" wrap="nowrap">
        <Text size="xs" fw={600} lineClamp={1} flex={1}>{d.title}</Text>
        <Text size="xs" c="dimmed" ff="monospace">{relativeTime(d.at)}</Text>
      </Group>
      <Group gap={6} mt={4}>
        {d.action && <Badge size="xs" variant="dot" color={ACTION_COLOR[d.action] ?? 'gray'}>{d.action}</Badge>}
        {d.priority && <Badge size="xs" variant="light" color={PRIORITY_COLOR[d.priority] ?? 'gray'}>{d.priority}</Badge>}
      </Group>
      {d.rationale && <Text size="xs" c="dimmed" mt={4} lineClamp={3}>{d.rationale}</Text>}
    </Paper>
  );
}

/**
 * The agent-mode rail: a live feed of the daemon's actions. The agent's vital
 * signs (models, schedule, counts) live in the header above; its full definition
 * lives in the footer below. Click a verdict to open the case and see the full
 * reasoning trace.
 */
export function AgentPanel({ onOpenCase }: { onOpenCase: (id: string) => void }) {
  const { data, isPending } = useQuery({
    queryKey: ['agent', 'activity'],
    queryFn: getAgentActivity,
    refetchInterval: 4000,
  });

  return (
    <Stack gap="sm" h="100%" mih={0}>
      <Group gap={6}>
        <Box c="dimmed" display="flex"><Activity size={13} /></Box>
        <Text size="xs" fw={600} tt="uppercase" c="dimmed" ff="monospace">Activity</Text>
      </Group>

      <ScrollArea flex={1} mih={0} type="hover">
        {isPending ? (
          <Stack gap="xs">
            {Array.from({ length: 3 }, (_, i) => (
              <Paper key={i} withBorder p="xs" radius="sm">
                <Group justify="space-between" gap="xs" wrap="nowrap">
                  <Skeleton height={12} width="60%" />
                  <Skeleton height={10} width={40} />
                </Group>
                <Group gap={6} mt={6}>
                  <Skeleton height={14} width={54} radius="xl" />
                  <Skeleton height={14} width={44} radius="xl" />
                </Group>
                <Skeleton height={9} mt={8} />
                <Skeleton height={9} mt={4} width="85%" />
              </Paper>
            ))}
          </Stack>
        ) : !data?.actions.length ? (
          <Text size="xs" c="dimmed">No verdicts yet. Open cases are reviewed on the next sweep.</Text>
        ) : (
          <Stack gap="xs">
            {data.actions.map((d, i) => (
              <ActionRow key={`${d.caseId}-${i}`} d={d} onClick={() => onOpenCase(d.caseId)} />
            ))}
          </Stack>
        )}
      </ScrollArea>
    </Stack>
  );
}
