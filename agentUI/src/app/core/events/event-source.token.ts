import { InjectionToken } from '@angular/core';

export interface EventSourceLike {
  readonly url: string;
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onerror: ((event: Event) => void) | null;
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  removeEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  close(): void;
}

export type EventSourceFactory = (url: string, init?: EventSourceInit) => EventSourceLike;

export const EVENT_SOURCE_FACTORY = new InjectionToken<EventSourceFactory>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string, init?: EventSourceInit): EventSourceLike =>
    new EventSource(url, init) as unknown as EventSourceLike,
});
