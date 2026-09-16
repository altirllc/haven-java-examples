import { Box, Group, Paper, Text } from '@mantine/core';
import type { ReactNode } from 'react';

/** The "Haven X" section card: accent bar + icon + title header over a body.
 *  Shared by the item detail's platform cards and the agent settings page. */
export function SectionCard({ icon, title, tagline, actions, children }: {
  icon: ReactNode;
  title: string;
  tagline?: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Paper withBorder style={{ overflow: 'hidden' }}>
      <Group gap="sm" p="sm" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Box w={4} h={32} style={{ borderRadius: 4, background: 'var(--mantine-color-cyan-5)' }} />
        <Box c="cyan.5" display="flex">{icon}</Box>
        <Group gap={6} wrap="nowrap" flex={1} miw={0}>
          <Text size="sm" fw={600}>{title}</Text>
          {tagline && <Text size="sm" c="dimmed" truncate>— {tagline}</Text>}
        </Group>
        {actions}
      </Group>
      {children}
    </Paper>
  );
}
