import { Button, Center, Stack, Text } from '@mantine/core';
import { CloudOff, RefreshCw } from 'lucide-react';

/** Full-page state for a failed primary load: human headline, the server's
 *  message demoted to a dimmed diagnostic line, one recovery action. */
export function LoadErrorState({ title, detail, onRetry }: {
  title: string;
  detail?: string;
  onRetry: () => void;
}) {
  return (
    <Stack align="center" gap={6} py={96}>
      <Center c="red.4" mb={4}><CloudOff size={40} strokeWidth={1.5} /></Center>
      <Text fw={600}>{title}</Text>
      {detail && <Text size="xs" c="dimmed" ff="monospace">{detail}</Text>}
      <Button variant="default" size="xs" leftSection={<RefreshCw size={14} />} onClick={onRetry} mt="md">
        Retry
      </Button>
    </Stack>
  );
}
