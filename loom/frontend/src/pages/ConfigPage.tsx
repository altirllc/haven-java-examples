import {
  Box, Stack, Text, Badge, Group, Paper, Code, SimpleGrid, Skeleton,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Cpu, FileText, Wrench, Brain, Clock } from 'lucide-react';
import { getAgentConfig, errorMessage, type AgentToolSpec } from '../api/client';
import { LoadErrorState } from '../components/LoadErrorState';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';

/** SectionCard with the settings page's padded body. */
function Card({ icon, title, tagline, children }: { icon: React.ReactNode; title: string; tagline?: string; children: React.ReactNode }) {
  return (
    <SectionCard icon={icon} title={title} tagline={tagline}>
      <Box p="md">{children}</Box>
    </SectionCard>
  );
}

function Field({ label, value, color, mono = true }: { label: string; value: string; color?: string; mono?: boolean }) {
  return (
    <Paper withBorder p="xs" radius="sm">
      <Text size="xs" c="dimmed">{label}</Text>
      <Text size="sm" fw={500} ff={mono ? 'monospace' : undefined} c={color} style={{ wordBreak: 'break-word' }}>{value}</Text>
    </Paper>
  );
}

/** A labelled concept with its setting and a one-line explanation. */
function Concept({ label, value, note, color }: { label: string; value: string; note: string; color?: string }) {
  return (
    <Paper withBorder p="xs" radius="sm">
      <Text size="xs" c="dimmed">{label}</Text>
      <Text size="sm" fw={600} c={color}>{value}</Text>
      <Text size="xs" c="dimmed" mt={4}>{note}</Text>
    </Paper>
  );
}

function ToolCard({ t }: { t: AgentToolSpec }) {
  return (
    <Paper withBorder p="sm" radius="sm">
      <Group gap="xs" wrap="nowrap" mb={t.inputFields.length ? 4 : 0}>
        <Text size="sm" fw={600} ff="monospace">{t.id}</Text>
        <Badge size="xs" variant="light" color={t.kind === 'act' ? 'orange' : 'blue'}>{t.kind}</Badge>
        {t.inputFields.length > 0 && (
          <Text size="xs" c="dimmed" ff="monospace">({t.inputFields.join(', ')})</Text>
        )}
      </Group>
      <Text size="xs" c="dimmed">{t.description}</Text>
    </Paper>
  );
}

/**
 * Agent settings — the agent's full definition on one page: models, system
 * prompt, tools, memory, trigger. Read-only today; this is the surface where a
 * user would tune the agent's behaviour in future.
 */
export function ConfigPage() {
  const { data: c, isPending, error, refetch } = useQuery({ queryKey: ['agent', 'config'], queryFn: getAgentConfig, staleTime: Infinity });

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

  return (
    <Stack>
      {/* The title and description are known at build time — only the cards are
          waiting on the API, so only the cards are placeheld. */}
      <PageHeader
        title="Config"
        description="Every loop, the agent performs its role: an AI model gathers context and makes a tool call. Read-only today; tune it here later."
      />

      {isPending || !c ? (
        Array.from({ length: 3 }, (_, i) => (
          <Paper key={i} withBorder style={{ overflow: 'hidden' }}>
            <Group gap="sm" p="sm" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
              <Skeleton height={32} width={4} radius={4} />
              <Skeleton height={16} width={16} radius="sm" />
              <Skeleton height={12} width={180} />
            </Group>
            <Box p="md">
              <Skeleton height={64} />
            </Box>
          </Paper>
        ))
      ) : (
        <>
          <Card icon={<FileText size={16} />} title="Prompt" tagline="who it is">
            <Code block fz={12.5} style={{ whiteSpace: 'pre-wrap' }}>{c.instructions}</Code>
          </Card>

          <Card icon={<Wrench size={16} />} title={`Tools (${c.tools.length})`} tagline="what it can do">
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
              {c.tools.map((t) => <ToolCard key={t.id} t={t} />)}
            </SimpleGrid>
          </Card>

          <Card icon={<Brain size={16} />} title="Memory" tagline="the context it brings to each decision">
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
              <Concept
                label="Semantic similarity"
                color="grape.6"
                value={`top ${c.memory.semanticRecall.topK}`}
                note="The most relevant past messages, found by embedding the prompt (kNN search)."
              />
              <Concept
                label="Surrounding context"
                color="grape.6"
                value={`±${c.memory.semanticRecall.messageRange} messages`}
                note="Neighbours pulled in around each match, so it isn't read out of context."
              />
            </SimpleGrid>
          </Card>

          <Card icon={<Cpu size={16} />} title="Models" tagline="its reasoning engine">
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
              <Concept
                label="Reasoning"
                color="cyan.7"
                value={c.models.reasoning.model}
                note="Reads each item and reasons out its status — the decision-making brain."
              />
              <Concept
                label="Embedding"
                color="grape.6"
                value={c.models.embedding.model}
                note="Turns text into vectors so the agent can recall similar past messages."
              />
            </SimpleGrid>
          </Card>

          <Card icon={<Clock size={16} />} title="Trigger" tagline="the autonomous loop">
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
              <Field label="Workflow Name" value={c.trigger.workflowId} />
              <Field label="Loop Frequency" value={`every ${c.trigger.interval} · ≤${c.trigger.batch}/run`} mono={false} />
            </SimpleGrid>
          </Card>
        </>
      )}
    </Stack>
  );
}
