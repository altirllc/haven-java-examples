import { Box, Group, Stack, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';

/** The page's identity: its name — the same word the rail and the breadcrumb
 *  use — what it is for, and its primary action. Renders at mount: every value
 *  here is known before any request, so none of it is ever placeheld. */
export function PageHeader({ title, description, action, count }: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  count?: ReactNode;
}) {
  const bar = (
    <Group justify="space-between" align="flex-start" wrap="nowrap">
      <Box flex={1} miw={0}>
        <Title order={1} size="lg" fw={700}>{title}</Title>
        {description && <Text size="sm" c="dimmed">{description}</Text>}
      </Box>
      {action && <Box flex="0 0 auto">{action}</Box>}
    </Group>
  );

  if (!count) return bar;
  return (
    <Stack gap={4}>
      {bar}
      <Text size="xs" c="dimmed">{count}</Text>
    </Stack>
  );
}
