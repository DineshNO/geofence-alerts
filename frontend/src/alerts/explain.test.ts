import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { explainCommandError } from './explain';

describe('explainCommandError', () => {
  it('explains a lost race by naming who got there first', () => {
    const text = explainCommandError(
      new ApiError(409, 'ALERT_ALREADY_CLAIMED', 'The alert is already claimed by op02.'),
    );
    expect(text).toContain('op02');
    expect(text).not.toMatch(/error/i);
  });

  it('explains the other refusals in plain words', () => {
    expect(explainCommandError(new ApiError(403, 'NOT_ALERT_OWNER', 'x'))).toContain('owner');
    expect(explainCommandError(new ApiError(409, 'INVALID_STATE', 'The alert is resolved.'))).toContain('resolved');
    expect(explainCommandError(new ApiError(400, 'VALIDATION_FAILED', 'x'))).toContain('1 to 500');
    expect(explainCommandError(new ApiError(0, 'NETWORK', 'x'))).toContain('Nothing was changed');
  });

  it('gives a generic message for anything unexpected', () => {
    expect(explainCommandError(new Error('boom'))).toBe('Something went wrong. Please try again.');
    expect(explainCommandError(new ApiError(500, 'INTERNAL_ERROR', 'x'))).toBe('Something went wrong. Please try again.');
  });
});
