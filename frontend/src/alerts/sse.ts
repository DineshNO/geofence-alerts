export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Reads Server-Sent Events from pieces of text that may end anywhere, even in the middle of a line.
 * The browser's EventSource cannot send an Authorization header, so the console reads the stream
 * itself with fetch and this parser. Comment lines (": ping") carry no event.
 */
export class SseParser {
  private buffer = '';
  private event = 'message';
  private data: string[] = [];

  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const messages: SseMessage[] = [];
    let newline = this.buffer.indexOf('\n');
    while (newline >= 0) {
      let line = this.buffer.slice(0, newline);
      this.buffer = this.buffer.slice(newline + 1);
      if (line.endsWith('\r')) {
        line = line.slice(0, -1);
      }
      this.handleLine(line, messages);
      newline = this.buffer.indexOf('\n');
    }
    return messages;
  }

  private handleLine(line: string, messages: SseMessage[]) {
    if (line === '') {
      // a blank line ends one event
      if (this.data.length > 0) {
        messages.push({ event: this.event, data: this.data.join('\n') });
      }
      this.event = 'message';
      this.data = [];
    } else if (line.startsWith(':')) {
      // a comment, used as a heartbeat
    } else if (line.startsWith('event:')) {
      this.event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      const value = line.slice(5);
      this.data.push(value.startsWith(' ') ? value.slice(1) : value);
    }
  }
}
