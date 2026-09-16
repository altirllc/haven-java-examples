import { CheckCircle, Clock, Eye, MessageSquareWarning, TimerOff } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

/** Case lifecycle state → color, icon, label — one source for board and detail. */
export const STATE_META: Record<string, { color: string; icon: LucideIcon; label: string }> = {
  watching: { color: 'gray', icon: Eye, label: 'Watching' },
  agreed: { color: 'green', icon: CheckCircle, label: 'Agreed' },
  disputed: { color: 'orange', icon: MessageSquareWarning, label: 'Disputed' },
  escalated: { color: 'red', icon: Clock, label: 'Escalated' },
  // The one nobody wants: the deadline passed with no verdict at all.
  lapsed: { color: 'red', icon: TimerOff, label: 'Lapsed' },
};

export const stateMeta = (state: string) => STATE_META[state] ?? STATE_META.watching;

export const ACTION_COLOR: Record<string, string> = { agree: 'green', dispute: 'orange', escalate: 'red' };
export const PRIORITY_COLOR: Record<string, string> = { high: 'red', medium: 'yellow', low: 'green' };
