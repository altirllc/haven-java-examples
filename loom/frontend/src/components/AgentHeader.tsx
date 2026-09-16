import { Paper, Group, Text, Box } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Bot } from 'lucide-react';
import { getAgentConfig, getAgentActivity } from '../api/client';
import { relativeTime } from '../lib/format';
import classes from './Layout.module.css';
import header from './AgentHeader.module.css';

/**
 * The agent's behaviour summary — the opening of its system prompt, sliced to a
 * character budget at a word boundary (the row truncates to one line anyway).
 */
function promptSummary(text?: string, max = 145): string {
  if (!text) return '';
  const full = text.trim();
  if (full.length <= max) return full;
  const cut = full.slice(0, max);
  const lastSpace = cut.lastIndexOf(' ');
  return `${cut.slice(0, lastSpace > 0 ? lastSpace : max).replace(/[.,;\s]+$/, '')} ...`;
}

function Stat({ value, label, color }: { value: number | string; label: string; color?: string }) {
  const active = typeof value === 'number' && value > 0;
  return (
    <Box>
      <Text fw={800} size="lg" lh={1} c={active ? color : undefined}>{value}</Text>
      <Text size="xs" c="dimmed">{label}</Text>
    </Box>
  );
}

function Cell({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <Box className={header.cell}>
      <Text className={header.microLabel}>{label}</Text>
      <Text className={header.val} c={color}>{value}</Text>
    </Box>
  );
}

/**
 * The agent's vital signs.
 *   row 1 — identity + a one-line behaviour summary (lifted from the system
 *           prompt) + the live counts.
 *   row 2 — a labelled spec strip of the runtime: reasoning model, embedding
 *           model, schedule, last run. Cells reflow 4→2×2 when narrow.
 * The full definition (system prompt, tools, memory) lives in the footer, so
 * this stays a glance — no hover detail layered on top.
 */
export function AgentHeader() {
  const { data: config } = useQuery({ queryKey: ['agent', 'config'], queryFn: getAgentConfig, staleTime: Infinity });
  const { data: activity } = useQuery({ queryKey: ['agent', 'activity'], queryFn: getAgentActivity, refetchInterval: 4000 });
  const s = activity?.status;
  const reasoning = config?.models.reasoning;
  const embedding = config?.models.embedding;

  return (
    <Paper withBorder style={{ overflow: 'hidden' }}>
      <Group gap="sm" p="sm" wrap="nowrap" align="center">
        <Group gap="sm" wrap="nowrap" flex={1} miw={0}>
          <Box display="flex"><Bot size={18} className={classes.alive} /></Box>
          <Box miw={0}>
            <Text size="sm" fw={650} lh={1.2}>Agent</Text>
            <Text size="xs" c="dimmed" truncate mt={5}>{promptSummary(config?.instructions)}</Text>
          </Box>
        </Group>
        <Group gap="lg" wrap="nowrap">
          <Stat value={s?.reviewed ?? '–'} label="reviewed" />
          <Stat value={s?.overdue ?? '–'} label="overdue" color="red.6" />
          <Stat value={s?.watching ?? '–'} label="watching" color="yellow.7" />
        </Group>
      </Group>

      <Box className={header.spec}>
        <Cell label="Reasoning" value={reasoning?.model ?? '—'} color="cyan.7" />
        <Cell label="Embeddings" value={embedding?.model ?? '—'} color="grape.6" />
        <Cell label="Schedule" value={s?.interval ? `every ${s.interval}` : '—'} />
        <Cell label="Last run" value={s?.lastActionAt ? relativeTime(s.lastActionAt) : 'awaiting'} />
      </Box>
    </Paper>
  );
}
