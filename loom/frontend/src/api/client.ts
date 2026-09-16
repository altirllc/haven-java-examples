import axios, { isAxiosError } from 'axios';

const api = axios.create({
  baseURL: `${import.meta.env.BASE_URL}api`,
});

// Surface the API's error body instead of axios's generic status-code
// message, so a 402 reads "Free allowance of 10 cases reached", not
// "Request failed with status code 402".
api.interceptors.response.use(undefined, (error) => {
  const message = error?.response?.data?.error;
  if (typeof message === 'string' && message.length > 0) error.message = message;
  return Promise.reject(error);
});

export type CaseState = 'watching' | 'agreed' | 'disputed' | 'escalated' | 'lapsed';

// What Anvil decided about the item, as loom last saw it.
//
// Every field is a plain string on purpose: this is ANVIL's vocabulary, and it
// is free to add a verb without telling us. `null` means Anvil has not decided
// yet — a normal state for a fresh case, and the thing the deadline measures.
export interface AnvilDecision {
  action: string;
  priority: string;
  rationale: string;
  at: string;
}

// loom's own verdict. Typed, unlike the Anvil side, because this vocabulary is
// ours. `null` until loom has reviewed.
export interface Review {
  action: ReviewAction;
  priority: Priority;
  rationale: string;
  at: string;
}

// loom's supervision record for one Anvil item — not the item itself.
export interface Case {
  id: string;
  anvil_item_id: string;
  title: string;
  state: CaseState;
  anvil_status: string | null;
  anvil_decision: AnvilDecision | null;
  review: Review | null;
  sla_due_at: string;
  metadata: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

// One step of the agent's reason→act loop, as captured per review.
export interface TraceStep {
  text: string;
  finishReason: string | null;
  toolCalls: { name: string; args: unknown }[];
  toolResults: { name: string; result: unknown }[];
}

// The full reasoning trace for a single review (persisted by jobs/).
export interface AgentTrace {
  prompt: string;
  ms: number;
  steps: TraceStep[];
  remembered: { role: string; text: string }[];
  usage: { input: number | null; output: number | null; total: number | null };
}

export interface LogDetails {
  action?: string;
  priority?: string;
  rationale?: string;
  trace?: AgentTrace;
}

export interface AuditLog {
  id: string;
  case_id: string;
  timestamp: string;
  action: string;
  actor: string;
  channel: string;
  message: string;
  details: LogDetails;
}

export async function listCases(): Promise<Case[]> {
  const { data } = await api.get('/cases');
  return data;
}

export async function getCase(id: string): Promise<Case> {
  const { data } = await api.get(`/cases/${id}`);
  return data;
}

export async function getCaseLogs(id: string): Promise<AuditLog[]> {
  const { data } = await api.get(`/cases/${id}/logs`);
  return data;
}

export interface OpenCaseBody {
  anvilItemId: string;
  title?: string;
  anvilStatus?: string;
  metadata?: Record<string, unknown>;
}

// Start supervising an Anvil item. Idempotent on anvilItemId: the api answers
// 200 with the existing case rather than opening a second one, so this is safe
// to press twice.
export async function openCase(body: OpenCaseBody): Promise<Case> {
  const { data } = await api.post('/cases', body);
  return data;
}

// Replaces the case's metadata wholesale — the same semantics every write
// surface uses, so they agree on who wins (last writer).
export async function updateCaseMetadata(id: string, metadata: Record<string, unknown>): Promise<Case> {
  const { data } = await api.patch(`/cases/${id}`, { metadata });
  return data;
}

export type ReviewAction = 'agree' | 'dispute' | 'escalate';
export type Priority = 'low' | 'medium' | 'high';

// A human verdict, sent to the case's processCase workflow — the same path and
// vocabulary the agent uses. The action is the route verb:
// POST /cases/:id/{agree|dispute|escalate}. A 409 means the case already
// closed, so the verdict had nowhere to land.
export async function reviewCase(
  id: string,
  action: ReviewAction,
  priority?: Priority,
  rationale?: string,
): Promise<void> {
  await api.post(`/cases/${id}/${action}`, { priority, rationale });
}

// ---- Identity ----

export interface Me {
  sub: string;
  email: string;
  tenant: string;
  roles: string[];
  // Capabilities the tenant may not have configured. Empty until the Dispatch
  // notifier is wired — the UI must not assume a key is present.
  features: Record<string, boolean>;
}

// The signed-in tenant user, resolved by the api from the Haven edge headers
// (X-Auth-Request-*). Drives the header account menu.
export async function getMe(): Promise<Me> {
  const { data } = await api.get('/me');
  return data;
}

export const ROLES = ['viewer', 'member', 'admin'] as const;
export type Role = (typeof ROLES)[number];

// UI mirror of the server's Role.meets (nested hierarchy + viewer floor). The
// server enforces via require(); this only reflects capability so viewers
// aren't shown write actions.
export function meetsRole(held: readonly string[], min: Role): boolean {
  const best = held.reduce((m, r) => Math.max(m, ROLES.indexOf(r as Role)), 0);
  return best >= ROLES.indexOf(min);
}

// ---- Agent ----

export interface AgentAction {
  caseId: string;
  title: string;
  action: string;
  priority: string;
  rationale: string;
  at: string;
}

export interface AgentActivity {
  status: {
    interval: string;
    lastActionAt: string | null;
    // Open cases, closed cases, and the ones already past their deadline.
    watching: number;
    reviewed: number;
    overdue: number;
  };
  actions: AgentAction[];
}

export async function getAgentActivity(): Promise<AgentActivity> {
  const { data } = await api.get('/agent/activity');
  return data;
}

export interface AgentToolSpec {
  id: string;
  kind: 'read' | 'act';
  description: string;
  inputFields: string[];
}

// Static agent definition — what the agent IS. Drives the header/footer in the
// agent panel so developers can see its models, system prompt, tools, and
// memory shape. Fetched once (never changes at runtime).
export interface AgentConfig {
  models: {
    reasoning: { provider: string; model: string };
    embedding: { provider: string; model: string };
  };
  instructions: string;
  tools: AgentToolSpec[];
  memory: { lastMessages: number; semanticRecall: { topK: number; messageRange: number } };
  trigger: { source: string; workflowId: string; interval: string; batch: number };
}

export async function getAgentConfig(): Promise<AgentConfig> {
  const { data } = await api.get('/agent/config');
  return data;
}

/** Stream a chat reply (SSE). Calls onDelta for each text chunk. */
export async function streamChat(
  message: string,
  threadId: string,
  onDelta: (text: string) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${import.meta.env.BASE_URL}api/agent/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, threadId }),
    signal,
  });
  if (!res.ok || !res.body) throw new Error(`Chat failed: ${res.status}`);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split('\n\n');
    buffer = blocks.pop() ?? '';
    for (const block of blocks) {
      const line = block.trim();
      if (!line.startsWith('data:')) continue;
      const json = line.slice(5).trim();
      if (!json) continue;
      try {
        const ev = JSON.parse(json) as { type?: string; textDelta?: string };
        if (ev.type === 'text-delta' && ev.textDelta) onDelta(ev.textDelta);
      } catch {
        /* ignore partial frames */
      }
    }
  }
}

// The api's error body is a flat `{ error: string }`.
export function errorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const body = error.response?.data?.error;
    return typeof body === 'string' ? body : error.message;
  }
  return error instanceof Error ? error.message : fallback;
}
