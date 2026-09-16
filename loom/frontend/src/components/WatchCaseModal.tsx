import { useState } from 'react';
import { TextInput, Button, Stack, Group, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useModals } from '@mantine/modals';
import { useNavigate } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ArrowRight } from 'lucide-react';
import { openCase, errorMessage, type Case } from '../api/client';
import { MetadataEditor, rowsToMetadata, type MetadataRow } from './MetadataEditor';
import { MutationErrorAlert } from './MutationErrorAlert';

function buildCurl(anvilItemId: string, title: string, metadata: Record<string, unknown>): string {
  const body: Record<string, unknown> = { anvilItemId: anvilItemId || '{anvil item id}' };
  if (title) body.title = title;
  if (Object.keys(metadata).length > 0) body.metadata = metadata;
  return [
    "curl -X POST /api/cases \\",
    "    -H 'Content-Type: application/json' \\",
    `    -d '${JSON.stringify(body)}'`,
  ].join('\n');
}

// eslint-disable-next-line react-refresh/only-export-components
function WatchCaseForm() {
  const modals = useModals();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [anvilItemId, setAnvilItemId] = useState('');
  const [title, setTitle] = useState('');
  const [metadataRows, setMetadataRows] = useState<MetadataRow[]>([]);
  const metadata = rowsToMetadata(metadataRows);

  const mutation = useMutation({
    mutationFn: openCase,
    onSuccess: (opened) => {
      // Opening is idempotent, so this may be a case that already existed.
      // Replace rather than prepend, or pressing the button twice shows it twice.
      queryClient.setQueryData(['cases'], (old: Case[] | undefined) => [
        opened,
        ...(old ?? []).filter((c) => c.id !== opened.id),
      ]);
      modals.closeAll();
      navigate('/cases');
    },
  });

  return (
    <Stack gap="lg">
      <Stack gap={4}>
        <TextInput
          label="Anvil item id"
          placeholder="The item in Anvil that loom should supervise"
          value={anvilItemId}
          onChange={(e) => setAnvilItemId(e.currentTarget.value)}
          data-autofocus
          required
        />
        <Text size="xs" c="dimmed">
          One case per Anvil item. Opening the same item twice returns the case that already exists.
        </Text>
      </Stack>

      <TextInput
        label="Title"
        placeholder="Optional — defaults to the Anvil item id"
        value={title}
        onChange={(e) => setTitle(e.currentTarget.value)}
      />

      <Stack gap={4}>
        <Text size="sm" fw={500}>Metadata</Text>
        <Text size="xs" c="dimmed">Optional key/value context — readable by the agent and MCP clients.</Text>
        <MetadataEditor rows={metadataRows} onChange={setMetadataRows} />
      </Stack>

      <CodeHighlight code={buildCurl(anvilItemId, title, metadata)} language="bash" />

      <MutationErrorAlert error={mutation.error ? errorMessage(mutation.error, 'Failed to open the case') : null} />

      <Group justify="flex-end">
        <Button
          onClick={() =>
            mutation.mutate({
              anvilItemId: anvilItemId.trim(),
              title: title.trim() || undefined,
              metadata,
            })
          }
          disabled={!anvilItemId.trim() || mutation.isPending}
          loading={mutation.isPending}
          size="md"
          radius="xl"
          rightSection={<ArrowRight size={16} />}
        >
          Watch
        </Button>
      </Group>
    </Stack>
  );
}

export function openWatchCaseModal(modals: ReturnType<typeof useModals>) {
  modals.openModal({
    title: 'Watch an Anvil item',
    size: 'lg',
    children: <WatchCaseForm />,
  });
}
