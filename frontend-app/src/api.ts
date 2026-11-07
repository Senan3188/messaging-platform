export interface Profile {
  subject: string;
  displayName: string;
}

export interface Conversation {
  id: string;
  recipientSubject: string;
  createdAt: string;
}

export interface Message {
  id: string;
  senderSubject: string;
  body: string;
  createdAt: string;
}

export interface MessagePage {
  messages: Message[];
  nextBefore: string | null;
}

export class ApiError extends Error {
  constructor(readonly status: number) {
    super('API request failed');
  }
}

export class Api {
  private readonly pending = new Set<Promise<unknown>>();

  request<T>(path: string, body?: object, key?: string): Promise<T> {
    const result = this.fetch<T>(path, body, key);
    this.pending.add(result);
    const done = () => this.pending.delete(result);
    void result.then(done, done);
    return result;
  }

  async idle(): Promise<void> {
    await Promise.allSettled(this.pending);
  }

  private async fetch<T>(path: string, body?: object, key?: string): Promise<T> {
    const headers = new Headers();
    if (body) headers.set('Content-Type', 'application/json');
    if (key) headers.set('Idempotency-Key', key);
    const response = await fetch(path, {
      headers,
      method: body ? 'POST' : 'GET',
      body: body && JSON.stringify(body),
      credentials: 'same-origin',
      cache: 'no-store',
    });
    if (!response.ok) throw new ApiError(response.status);
    if (response.status === 202 || response.status === 204) return undefined as T;
    return response.json();
  }
}
