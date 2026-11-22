import { describe, expect, it } from 'vitest';
import { SseParser } from './sse';

describe('SseParser', () => {
  it('reads an event with a name and data', () => {
    const parser = new SseParser();
    expect(parser.push('event: alert.changed\ndata: {"a":1}\n\n')).toEqual([
      { event: 'alert.changed', data: '{"a":1}' },
    ]);
  });

  it('reads the form the server writes, without a space after the colon', () => {
    expect(new SseParser().push('event:ready\ndata:{}\n\n')).toEqual([{ event: 'ready', data: '{}' }]);
  });

  it('puts together an event that arrives in pieces, even split inside a word', () => {
    const parser = new SseParser();
    expect(parser.push('event:alert.cha')).toEqual([]);
    expect(parser.push('nged\nda')).toEqual([]);
    expect(parser.push('ta:{"x":2}\n')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ event: 'alert.changed', data: '{"x":2}' }]);
  });

  it('reads several events from one piece', () => {
    const messages = new SseParser().push('event:a\ndata:1\n\nevent:b\ndata:2\n\n');
    expect(messages.map((m) => m.event)).toEqual(['a', 'b']);
  });

  it('ignores comment lines, which are only heartbeats', () => {
    const parser = new SseParser();
    expect(parser.push(': ping\n\n')).toEqual([]);
    expect(parser.push(': ping\nevent:a\ndata:1\n\n')).toEqual([{ event: 'a', data: '1' }]);
  });

  it('joins several data lines with a line break', () => {
    expect(new SseParser().push('data:one\ndata:two\n\n')).toEqual([{ event: 'message', data: 'one\ntwo' }]);
  });

  it('accepts carriage returns before the line breaks', () => {
    expect(new SseParser().push('event:a\r\ndata:1\r\n\r\n')).toEqual([{ event: 'a', data: '1' }]);
  });

  it('does not let one event carry the name of the one before', () => {
    const messages = new SseParser().push('event:a\ndata:1\n\ndata:2\n\n');
    expect(messages[1]).toEqual({ event: 'message', data: '2' });
  });
});
