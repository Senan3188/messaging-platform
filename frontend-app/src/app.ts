import { DatePipe } from '@angular/common';
import { Component, ElementRef, OnDestroy, OnInit, computed, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, ApiError, Conversation, Message, MessagePage, Profile } from './api';

@Component({
  selector: 'relay-app',
  imports: [DatePipe, FormsModule],
  templateUrl: './app.html',
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly api = new Api();
  private poll?: number;
  private syncing = false;
  private signupAttempt?: { payload: string; key: string };
  private sendAttempt?: { conversation: string; body: string; key: string };
  private readonly timeline = viewChild<ElementRef<HTMLElement>>('timeline');

  readonly loading = signal(true);
  readonly profile = signal<Profile | null>(null);
  readonly authMode = signal<'signin' | 'signup' | 'sent'>('signin');
  readonly authError = signal('');
  readonly notice = signal('');
  readonly registering = signal(false);
  readonly conversations = signal<Conversation[]>([]);
  readonly search = signal('');
  readonly visibleConversations = computed(() => {
    const search = this.search().trim().toLowerCase();
    return this.conversations().filter(chat => this.contact(chat.recipientSubject).toLowerCase().includes(search)
      || chat.recipientSubject.toLowerCase().includes(search));
  });
  readonly active = signal<Conversation | null>(null);
  readonly messages = signal<Message[]>([]);
  readonly older = signal<string | null>(null);
  readonly chatLoading = signal(false);
  readonly olderLoading = signal(false);
  readonly addingContact = signal(false);
  readonly opening = signal(false);
  readonly sending = signal(false);
  readonly leaving = signal(false);
  readonly copied = signal(false);

  email = '';
  firstName = '';
  lastName = '';
  recipient = '';
  draft = '';

  async ngOnInit(): Promise<void> {
    const auth = new URLSearchParams(location.search).get('auth');
    if (auth) {
      this.authError.set(auth === 'expired'
        ? 'Your sign-in attempt expired. Please try again.'
        : 'Sign-in could not be completed. Please try again.');
      history.replaceState(null, '', '/');
    }
    try {
      this.profile.set(await this.api.request<Profile>('/api/session'));
      await this.sync();
      this.poll = window.setInterval(() => void this.sync(), 4000);
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) {
        this.authError.set('Your session could not be loaded. Try again in a moment.');
      }
    } finally {
      this.loading.set(false);
    }
  }

  ngOnDestroy(): void {
    window.clearInterval(this.poll);
  }

  setAuthMode(mode: 'signin' | 'signup'): void {
    this.authError.set('');
    this.authMode.set(mode);
  }

  async register(): Promise<void> {
    if (this.registering()) return;
    const body = { email: this.email.trim().toLowerCase(), firstName: this.firstName.trim(), lastName: this.lastName.trim() };
    const payload = JSON.stringify(body);
    if (this.signupAttempt?.payload !== payload) {
      this.signupAttempt = { payload, key: crypto.randomUUID() };
    }
    this.registering.set(true);
    this.authError.set('');
    try {
      await this.api.request<void>('/api/register', body, this.signupAttempt.key);
      this.authMode.set('sent');
    } catch (error) {
      this.authError.set(error instanceof ApiError && error.status === 400
        ? 'Check the name and email address and try again.'
        : 'Signup could not finish. Retry with the same details in a moment.');
    } finally {
      this.registering.set(false);
    }
  }

  async openConversation(): Promise<void> {
    if (!this.recipient.trim() || this.opening() || this.leaving()) return;
    this.opening.set(true);
    this.notice.set('');
    try {
      const chat = await this.api.request<Conversation>('/api/conversations', { recipientSubject: this.recipient.trim() });
      this.conversations.update(chats => chats.some(existing => existing.id === chat.id) ? chats : [chat, ...chats]);
      this.recipient = '';
      this.addingContact.set(false);
      await this.select(chat);
    } catch (error) {
      this.report(error, error instanceof ApiError && error.status === 404
        ? 'That contact ID did not match a registered user.'
        : 'Could not open a chat. Check the contact ID and try again.');
    } finally {
      this.opening.set(false);
    }
  }

  async select(chat: Conversation): Promise<void> {
    if (this.active()?.id === chat.id || this.sending() || this.leaving()) return;
    this.active.set(chat);
    this.messages.set([]);
    this.older.set(null);
    this.notice.set('');
    this.draft = this.sendAttempt?.conversation === chat.id ? this.sendAttempt.body : '';
    this.chatLoading.set(true);
    try {
      const page = await this.historyPage(chat.id);
      if (this.active()?.id !== chat.id) return;
      this.merge(page.messages);
      this.older.set(page.nextBefore);
      this.scrollBottom();
    } catch (error) {
      if (this.active()?.id === chat.id) this.report(error, 'Could not load this conversation.');
    } finally {
      if (this.active()?.id === chat.id) this.chatLoading.set(false);
    }
  }

  back(): void {
    this.active.set(null);
    this.chatLoading.set(false);
    this.notice.set('');
  }

  async loadOlder(): Promise<void> {
    const chat = this.active();
    const cursor = this.older();
    if (!chat || !cursor || this.olderLoading() || this.leaving()) return;
    const element = this.timeline()?.nativeElement;
    const height = element?.scrollHeight ?? 0;
    this.olderLoading.set(true);
    try {
      const page = await this.historyPage(chat.id, cursor);
      if (this.active()?.id !== chat.id) return;
      this.merge(page.messages);
      this.older.set(page.nextBefore);
      requestAnimationFrame(() => {
        if (element?.isConnected) element.scrollTop += element.scrollHeight - height;
      });
    } catch (error) {
      if (this.active()?.id === chat.id) this.report(error, 'Could not load older messages.');
    } finally {
      this.olderLoading.set(false);
    }
  }

  async send(): Promise<void> {
    const chat = this.active();
    const body = this.draft.trim();
    if (!chat || !body || this.sending() || this.leaving()) return;
    if (this.sendAttempt?.conversation !== chat.id || this.sendAttempt.body !== body) {
      this.sendAttempt = { conversation: chat.id, body, key: crypto.randomUUID() };
    }
    this.sending.set(true);
    this.notice.set('');
    try {
      const sent = await this.api.request<Message>(this.messagePath(chat.id), { body, clientMessageId: this.sendAttempt.key });
      this.sendAttempt = undefined;
      if (this.active()?.id === chat.id) {
        this.merge([sent]);
        if (this.draft.trim() === body) this.draft = '';
        this.scrollBottom();
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) this.sendAttempt = undefined;
      if (this.active()?.id === chat.id) this.report(error, 'Message could not be sent. Try again.');
    } finally {
      this.sending.set(false);
    }
  }

  async copyId(): Promise<void> {
    const profile = this.profile();
    if (!profile) return;
    try {
      await navigator.clipboard.writeText(profile.subject);
      this.copied.set(true);
      window.setTimeout(() => this.copied.set(false), 2000);
    } catch {
      this.notice.set('Your contact ID is ' + profile.subject);
    }
  }

  async signOut(): Promise<void> {
    if (this.leaving()) return;
    this.leaving.set(true);
    try {
      await this.api.idle();
      await this.api.request<void>('/api/auth/logout', {});
      location.assign('/');
    } catch {
      this.notice.set('Could not sign out. Please try again.');
      this.leaving.set(false);
    }
  }

  contact(subject: string): string {
    return 'Contact ' + subject.slice(0, 8).toUpperCase();
  }

  initials(subject: string): string {
    return subject.slice(0, 2).toUpperCase();
  }

  mine(message: Message): boolean {
    return message.senderSubject === this.profile()?.subject;
  }

  private async sync(): Promise<void> {
    if (this.syncing || !this.profile() || this.leaving()) return;
    this.syncing = true;
    try {
      const chat = this.active();
      const previous = this.messages().at(-1)?.id;
      this.conversations.set(await this.api.request<Conversation[]>('/api/conversations'));
      if (!chat || this.chatLoading() || this.active()?.id !== chat.id || this.leaving()) return;
      let page = await this.historyPage(chat.id);
      const latest = [...page.messages];
      while (previous && !latest.some(message => message.id === previous) && page.nextBefore
          && this.active()?.id === chat.id && !this.leaving()) {
        page = await this.historyPage(chat.id, page.nextBefore);
        latest.push(...page.messages);
      }
      if (this.active()?.id !== chat.id || this.leaving()) return;
      const element = this.timeline()?.nativeElement;
      const nearBottom = !element || element.scrollHeight - element.scrollTop - element.clientHeight < 120;
      this.merge(latest);
      if (!previous) this.older.set(page.nextBefore);
      if (nearBottom && this.messages().at(-1)?.id !== previous) this.scrollBottom();
    } catch (error) {
      this.report(error, 'Could not refresh chats. Retrying shortly.');
    } finally {
      this.syncing = false;
    }
  }

  private report(error: unknown, message: string): void {
    if (error instanceof ApiError && error.status === 401) {
      this.profile.set(null);
      window.clearInterval(this.poll);
      this.authMode.set('signin');
      this.authError.set('Your session expired. Sign in to continue.');
    } else {
      this.notice.set(message);
    }
  }

  private historyPage(id: string, before?: string): Promise<MessagePage> {
    return this.api.request<MessagePage>(this.messagePath(id) + (before ? '?before=' + encodeURIComponent(before) : ''));
  }

  private messagePath(id: string): string {
    return '/api/conversations/' + encodeURIComponent(id) + '/messages';
  }

  private merge(incoming: Message[]): void {
    const byId = new Map(this.messages().map(message => [message.id, message]));
    for (const message of incoming) byId.set(message.id, message);
    const sortableTime = (value: string) => {
      const [seconds, fraction = ''] = value.replace(/Z$/, '').split('.');
      return seconds + '.' + fraction.padEnd(9, '0');
    };
    this.messages.set([...byId.values()].sort((a, b) =>
      sortableTime(a.createdAt).localeCompare(sortableTime(b.createdAt)) || a.id.localeCompare(b.id)));
  }

  private scrollBottom(): void {
    requestAnimationFrame(() => {
      const element = this.timeline()?.nativeElement;
      if (element) element.scrollTop = element.scrollHeight;
    });
  }
}
