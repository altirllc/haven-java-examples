import { Center, Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';

/** The house empty state: one glyph, one line, nothing to click — the page
 *  header already carries the action that fills it. */
export function EmptyState({ icon, message }: { icon: ReactNode; message: string }) {
  return (
    <Center h={300}>
      <Stack align="center" gap="md">
        <Center c="dimmed">{icon}</Center>
        <Text c="dimmed" size="sm">{message}</Text>
      </Stack>
    </Center>
  );
}
