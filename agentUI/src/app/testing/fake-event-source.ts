import { EventSourceFactory, EventSourceLike } from '../core/events/event-source.token';

export class FakeEventSource implements EventSourceLike {
  readyState = 0; // CONNECTING
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;

  readonly listeners = new Map<string, Set<(event: MessageEvent<string>) => void>>();

  constructor(
    readonly url: string,
    readonly init?: EventSourceInit
  ) {}

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    let bucket = this.listeners.get(type);
    if (!bucket) {
      bucket = new Set();
      this.listeners.set(type, bucket);
    }
    bucket.add(listener);
  }

  removeEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.get(type)?.delete(listener);
  }

  close(): void {
    this.closed = true;
    this.readyState = 2; // CLOSED
  }

  emitOpen(): void {
    this.readyState = 1; // OPEN
    this.onopen?.(new Event('open'));
  }

  emitError(nextReadyState = 0): void {
    this.readyState = nextReadyState;
    this.onerror?.(new Event('error'));
  }

  emitNamedEvent(type: string, payload: unknown, lastEventId = '1'): void {
    const data = typeof payload === 'string' ? payload : JSON.stringify(payload);
    const message = new MessageEvent<string>(type, { data, lastEventId });
    for (const listener of this.listeners.get(type) ?? []) {
      listener(message);
    }
  }
}

export interface FakeEventSourceController {
  readonly instances: FakeEventSource[];
  readonly factory: EventSourceFactory;
  latest(): FakeEventSource | undefined;
}

export function createFakeEventSourceController(): FakeEventSourceController {
  const instances: FakeEventSource[] = [];
  return {
    instances,
    factory: (url: string, init?: EventSourceInit): EventSourceLike => {
      const instance = new FakeEventSource(url, init);
      instances.push(instance);
      return instance;
    },
    latest: () => instances[instances.length - 1],
  };
}
