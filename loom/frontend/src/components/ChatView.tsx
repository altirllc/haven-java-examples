import { useRef, useState, useEffect } from 'react';
import { Stack, Paper, Text, Textarea, ActionIcon, ScrollArea, Box, Center, Group } from '@mantine/core';
import { Send, Bot } from 'lucide-react';
import { streamChat, meetsRole } from '../api/client';
import { useMe } from '../api/useMe';

interface Msg { role: 'user' | 'assistant'; content: string }

function Bubble({ m }: { m: Msg }) {
  const isUser = m.role === 'user';
  return (
    <Paper
      p="sm"
      maw="72%"
      bg={isUser ? 'cyan.6' : 'var(--mantine-color-default-hover)'}
      c={isUser ? 'white' : undefined}
      style={{
        alignSelf: isUser ? 'flex-end' : 'flex-start',
        borderBottomRightRadius: isUser ? 4 : undefined,
        borderBottomLeftRadius: isUser ? undefined : 4,
        whiteSpace: 'pre-wrap',
      }}
    >
      <Text size="sm">{m.content || '…'}</Text>
    </Paper>
  );
}

export function ChatView({ active }: { active: boolean }) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const threadId = useRef<string>(crypto.randomUUID());
  const viewport = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const { data: me } = useMe();
  const canWrite = meetsRole(me?.roles ?? [], 'member');

  useEffect(() => {
    viewport.current?.scrollTo({ top: viewport.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  // Put the cursor in the message box when the user enters agent mode.
  useEffect(() => {
    if (active) inputRef.current?.focus();
  }, [active]);

  const send = async () => {
    const text = input.trim();
    if (!text || sending) return;
    setInput('');
    setSending(true);
    setMessages((m) => [...m, { role: 'user', content: text }, { role: 'assistant', content: '' }]);
    try {
      await streamChat(text, threadId.current, (delta) => {
        setMessages((m) => {
          const next = [...m];
          const last = next[next.length - 1];
          next[next.length - 1] = { ...last, content: last.content + delta };
          return next;
        });
      });
    } catch {
      setMessages((m) => {
        const next = [...m];
        const last = next[next.length - 1];
        next[next.length - 1] = { ...last, content: last.content || '⚠ Chat failed — check the agent logs.' };
        return next;
      });
    } finally {
      setSending(false);
    }
  };

  return (
    <Stack gap="sm" h="100%" mih={0}>
      {messages.length === 0 ? (
        <Center flex={1}>
          <Stack align="center" gap="xs">
            <Box c="dimmed" display="flex"><Bot size={40} strokeWidth={1.5} /></Box>
            <Text c="dimmed" size="sm">Ask the agent about the items it's triaging.</Text>
          </Stack>
        </Center>
      ) : (
        <ScrollArea viewportRef={viewport} flex={1} mih={0} type="hover">
          <Stack gap="sm" pr="xs">
            {messages.map((m, i) => <Bubble key={i} m={m} />)}
          </Stack>
        </ScrollArea>
      )}

      <Group gap="xs" align="flex-end">
        <Textarea
          ref={inputRef}
          flex={1}
          placeholder={canWrite ? 'Message the agent…' : 'Read-only — the member role is required to chat with the agent.'}
          disabled={!canWrite}
          autosize
          minRows={1}
          maxRows={5}
          value={input}
          onChange={(e) => setInput(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
          }}
        />
        <ActionIcon size="lg" color="cyan" aria-label="Send" onClick={send} loading={sending} disabled={!canWrite || !input.trim()}>
          <Send size={16} />
        </ActionIcon>
      </Group>
    </Stack>
  );
}
