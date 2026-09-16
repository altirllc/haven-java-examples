import { Alert, type AlertProps } from '@mantine/core';
import { AlertCircle } from 'lucide-react';

interface MutationErrorAlertProps extends Omit<AlertProps, 'color' | 'icon' | 'children'> {
  error: string | null | undefined;
}

/** The house error alert. Renders nothing when there is no error. Pass
 *  `onClose` to make it dismissible. */
export function MutationErrorAlert({ error, onClose, ...rest }: MutationErrorAlertProps) {
  if (!error) return null;
  return (
    <Alert color="red" icon={<AlertCircle size={16} />} withCloseButton={!!onClose} onClose={onClose} {...rest}>
      {error}
    </Alert>
  );
}
