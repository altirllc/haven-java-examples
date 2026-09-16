import { ActionIcon, Button, Group, Stack, TextInput } from '@mantine/core';
import { Plus, X } from 'lucide-react';

export interface MetadataRow {
  key: string;
  value: string;
}

// A value's canonical display text — exactly what rowsToMetadata parses back
// to the same value: strings that would themselves parse as JSON (e.g. '2.1',
// 'true') are shown quoted, so an untouched row never changes type on save.
// The read view uses it too, so a value never changes face when the pencil opens.
// eslint-disable-next-line react-refresh/only-export-components
export function valueText(value: unknown): string {
  if (typeof value !== 'string') return JSON.stringify(value);
  try {
    JSON.parse(value);
    return JSON.stringify(value);
  } catch {
    return value;
  }
}

// eslint-disable-next-line react-refresh/only-export-components
export function metadataToRows(metadata: Record<string, unknown>): MetadataRow[] {
  return Object.entries(metadata).map(([key, value]) => ({ key, value: valueText(value) }));
}

// Values round-trip as JSON when they parse ('2026' → number, 'true' → boolean),
// else stay strings — the same typed values the agent and MCP tools write.
// eslint-disable-next-line react-refresh/only-export-components
export function rowsToMetadata(rows: MetadataRow[]): Record<string, unknown> {
  const metadata: Record<string, unknown> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (!key) continue;
    try {
      metadata[key] = JSON.parse(row.value);
    } catch {
      metadata[key] = row.value;
    }
  }
  return metadata;
}

export function MetadataEditor({
  rows,
  onChange,
}: {
  rows: MetadataRow[];
  onChange: (rows: MetadataRow[]) => void;
}) {
  const update = (i: number, patch: Partial<MetadataRow>) =>
    onChange(rows.map((row, j) => (j === i ? { ...row, ...patch } : row)));

  return (
    <Stack gap={6}>
      {rows.map((row, i) => (
        <Group key={i} gap="xs" wrap="nowrap">
          <TextInput
            size="xs"
            placeholder="key"
            value={row.key}
            flex={2}
            styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
            onChange={(e) => update(i, { key: e.currentTarget.value })}
          />
          <TextInput
            size="xs"
            placeholder="value"
            value={row.value}
            flex={3}
            onChange={(e) => update(i, { value: e.currentTarget.value })}
          />
          <ActionIcon
            variant="subtle"
            color="gray"
            aria-label="Remove row"
            onClick={() => onChange(rows.filter((_, j) => j !== i))}
          >
            <X size={14} />
          </ActionIcon>
        </Group>
      ))}
      <Button
        variant="subtle"
        size="xs"
        leftSection={<Plus size={13} />}
        onClick={() => onChange([...rows, { key: '', value: '' }])}
        w="fit-content"
      >
        {rows.length > 0 ? 'Add another' : 'Add metadata'}
      </Button>
    </Stack>
  );
}
