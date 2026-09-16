import { Stack, Group, Text, Code, Badge, Box, Paper, ScrollArea } from '@mantine/core';
import type { AgentTrace } from '../api/client';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Stack gap={4}>
      <Text size="xs" fw={600} tt="uppercase" c="dimmed" ff="monospace">{label}</Text>
      {children}
    </Stack>
  );
}

/**
 * The anatomy of one triage decision — the reasoning trace persisted by the
 * daemon. Read top-to-bottom it tells the whole story: there was no human
 * (the trigger is synthesized), the agent may have recalled past context, it
 * ran a reason→act loop calling tools, and it cost N tokens. This is the
 * lesson the outcome badge alone can't teach.
 */
export function ReviewTrace({ trace }: { trace: AgentTrace }) {
  return (
    <Stack gap="sm" mt="sm">
      <Field label="Trigger message — synthesized from the row, no human prompt">
        <Code block fz={11} style={{ whiteSpace: 'pre-wrap' }}>{trace.prompt}</Code>
      </Field>

      {trace.remembered.length > 0 && (
        <Field label={`Recalled from memory · ${trace.remembered.length} message${trace.remembered.length === 1 ? '' : 's'}`}>
          <Stack gap={4}>
            {trace.remembered.map((m, i) => (
              <Group gap={6} key={i} wrap="nowrap" align="flex-start">
                <Badge size="xs" variant="light" color="grape">{m.role}</Badge>
                <Text size="xs" c="dimmed" lineClamp={2}>{m.text}</Text>
              </Group>
            ))}
          </Stack>
        </Field>
      )}

      <Field label={`Reason → act loop · ${trace.steps.length} step${trace.steps.length === 1 ? '' : 's'}`}>
        <Stack gap={6}>
          {trace.steps.map((s, i) => (
            <Paper key={i} withBorder p="xs" radius="sm">
              <Text size="xs" c="dimmed" ff="monospace" mb={s.text || s.toolCalls.length ? 4 : 0}>
                step {i + 1}{s.finishReason ? ` · ${s.finishReason}` : ''}
              </Text>
              {s.text && <Text size="xs" mb={s.toolCalls.length ? 6 : 0}>{s.text}</Text>}
              {s.toolCalls.map((t, j) => (
                <Box key={j} mb={4}>
                  <Group gap={6} mb={2}>
                    <Badge size="xs" variant="filled" color="orange">{t.name}</Badge>
                    <Text size="xs" c="dimmed">called</Text>
                  </Group>
                  <ScrollArea.Autosize mah={140}>
                    <Code block fz={11}>{JSON.stringify(t.args, null, 2)}</Code>
                  </ScrollArea.Autosize>
                </Box>
              ))}
            </Paper>
          ))}
        </Stack>
      </Field>

      <Group gap="xs">
        <Badge size="xs" variant="light" color="gray" tt="none">in {trace.usage.input ?? '–'} tok</Badge>
        <Badge size="xs" variant="light" color="gray" tt="none">out {trace.usage.output ?? '–'} tok</Badge>
        <Badge size="xs" variant="light" color="gray" tt="none">total {trace.usage.total ?? '–'} tok</Badge>
        <Badge size="xs" variant="light" color="gray" tt="none">{trace.ms} ms</Badge>
      </Group>
    </Stack>
  );
}
