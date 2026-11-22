import { ApiError } from '../api/client';

/**
 * What to tell the operator when a command is refused. A lost race (another operator was first) is
 * a normal outcome and is explained, not shown as a generic error.
 */
export function explainCommandError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Something went wrong. Please try again.';
  }
  switch (error.code) {
    case 'ALERT_ALREADY_CLAIMED':
      return `Not claimed: ${error.detail}`;
    case 'NOT_ALERT_OWNER':
      return 'Only the owner of the alert or a supervisor can do this.';
    case 'INVALID_STATE':
      return `Nothing was changed: ${error.detail}`;
    case 'VALIDATION_FAILED':
      return 'Write a note of 1 to 500 characters.';
    case 'NOT_FOUND':
      return 'This alert no longer exists.';
    case 'UNAUTHENTICATED':
      return 'Your session ended. Please sign in again.';
    case 'NETWORK':
      return 'The server cannot be reached. Nothing was changed.';
    default:
      return 'Something went wrong. Please try again.';
  }
}
