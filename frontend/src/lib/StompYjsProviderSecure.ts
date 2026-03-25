import { Client } from '@stomp/stompjs';
import {
  Awareness,
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
  removeAwarenessStates,
} from 'y-protocols/awareness';
import * as Y from 'yjs';

type ProviderStatus = 'connecting' | 'live' | 'offline';

type CollaborationMessage = {
  type: 'join' | 'leave' | 'sync-request' | 'sync-response' | 'doc-update' | 'awareness-update';
  clientId: string;
  awarenessClientId?: number;
  user?: string;
  color?: string;
  colorLight?: string;
  payload?: string;
  targetClientId?: string;
  peerCount?: number;
};

type ProviderOptions = {
  noteId: number;
  doc: Y.Doc;
  clientId: string;
  user: string;
  color: string;
  colorLight: string;
  shareToken?: string;
  onStatusChange?: (status: ProviderStatus) => void;
  onAwarenessChange?: () => void;
};

const AWARENESS_HEARTBEAT_MS = 15000;

const encodeBase64 = (data: Uint8Array) => {
  let binary = '';
  const chunkSize = 0x8000;

  for (let index = 0; index < data.length; index += chunkSize) {
    const chunk = data.subarray(index, index + chunkSize);
    binary += String.fromCharCode(...chunk);
  }

  return btoa(binary);
};

const decodeBase64 = (value: string) => {
  const binary = atob(value);
  const result = new Uint8Array(binary.length);

  for (let index = 0; index < binary.length; index += 1) {
    result[index] = binary.charCodeAt(index);
  }

  return result;
};

export class StompYjsProvider {
  public readonly awareness: Awareness;

  private readonly doc: Y.Doc;
  private readonly clientId: string;
  private readonly noteId: number;
  private readonly user: string;
  private readonly color: string;
  private readonly colorLight: string;
  private readonly shareToken?: string;
  private readonly onStatusChange?: (status: ProviderStatus) => void;
  private readonly onAwarenessChange?: () => void;
  private readonly stompClient: Client;
  private readonly awarenessClientId: number;
  private readonly heartbeatTimer: ReturnType<typeof setInterval>;

  private hasResolvedSeed = false;
  private resolveSeedDecision!: (shouldSeed: boolean) => void;
  private currentStatus: ProviderStatus = 'connecting';

  public readonly seedDecision: Promise<boolean>;

  constructor(options: ProviderOptions) {
    this.doc = options.doc;
    this.clientId = options.clientId;
    this.noteId = options.noteId;
    this.user = options.user;
    this.color = options.color;
    this.colorLight = options.colorLight;
    this.shareToken = options.shareToken;
    this.onStatusChange = options.onStatusChange;
    this.onAwarenessChange = options.onAwarenessChange;

    this.awareness = new Awareness(this.doc);
    this.awarenessClientId = this.doc.clientID;
    this.seedDecision = new Promise<boolean>((resolve) => {
      this.resolveSeedDecision = resolve;
    });

    this.awareness.setLocalStateField('user', {
      name: this.user,
      color: this.color,
      colorLight: this.colorLight,
    });

    this.doc.on('update', this.handleLocalDocumentUpdate);
    this.awareness.on('update', this.handleAwarenessUpdate);
    this.awareness.on('change', this.handleAwarenessChange);

    this.heartbeatTimer = setInterval(() => {
      const localState = this.awareness.getLocalState();
      if (!localState) return;

      this.awareness.setLocalState({
        ...localState,
      });
    }, AWARENESS_HEARTBEAT_MS);

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${protocol}//${window.location.host}/ws-collab`;
    const token = localStorage.getItem('token');

    this.stompClient = new Client({
      brokerURL,
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 3000,
      onConnect: () => {
        this.setStatus('live');

        this.stompClient.subscribe(
          `/topic/collab/${this.noteId}`,
          (message) => {
            const payload = JSON.parse(message.body) as CollaborationMessage;
            this.handleIncomingMessage(payload);
          },
          this.getCollabHeaders(),
        );

        this.publish({
          type: 'join',
          clientId: this.clientId,
          awarenessClientId: this.awarenessClientId,
          user: this.user,
          color: this.color,
          colorLight: this.colorLight,
        });
      },
      onStompError: () => {
        this.setStatus('offline');
        this.finishSeedDecision(true);
      },
      onWebSocketError: () => {
        this.setStatus('offline');
        this.finishSeedDecision(true);
      },
      onWebSocketClose: () => {
        this.setStatus('offline');
      },
    });

    this.setStatus('connecting');
    this.stompClient.activate();
  }

  public destroy() {
    this.awareness.setLocalState(null);

    if (this.stompClient.connected) {
      this.publish({
        type: 'leave',
        clientId: this.clientId,
        awarenessClientId: this.awarenessClientId,
      });
    }

    clearInterval(this.heartbeatTimer);
    this.doc.off('update', this.handleLocalDocumentUpdate);
    this.awareness.off('update', this.handleAwarenessUpdate);
    this.awareness.off('change', this.handleAwarenessChange);
    this.finishSeedDecision(true);
    this.stompClient.deactivate();
  }

  private handleIncomingMessage = (message: CollaborationMessage) => {
    if (!message?.type || message.clientId === this.clientId) {
      if (message?.type === 'join' && message.clientId === this.clientId) {
        const peerCount = message.peerCount ?? 0;
        this.finishSeedDecision(peerCount === 0);

        if (peerCount > 0) {
          this.publish({
            type: 'sync-request',
            clientId: this.clientId,
            payload: encodeBase64(Y.encodeStateVector(this.doc)),
          });
        }
      }
      return;
    }

    switch (message.type) {
      case 'leave':
        if (typeof message.awarenessClientId === 'number') {
          removeAwarenessStates(this.awareness, [message.awarenessClientId], this);
        }
        break;
      case 'sync-request':
        if (!message.payload) return;
        this.publish({
          type: 'sync-response',
          clientId: this.clientId,
          targetClientId: message.clientId,
          payload: encodeBase64(Y.encodeStateAsUpdate(this.doc, decodeBase64(message.payload))),
        });
        break;
      case 'sync-response':
        if (message.targetClientId !== this.clientId || !message.payload) return;
        Y.applyUpdate(this.doc, decodeBase64(message.payload), this);
        break;
      case 'doc-update':
        if (!message.payload) return;
        Y.applyUpdate(this.doc, decodeBase64(message.payload), this);
        break;
      case 'awareness-update':
        if (!message.payload) return;
        applyAwarenessUpdate(this.awareness, decodeBase64(message.payload), this);
        break;
      default:
        break;
    }
  };

  private handleLocalDocumentUpdate = (update: Uint8Array, origin: unknown) => {
    if (origin === this) return;

    this.publish({
      type: 'doc-update',
      clientId: this.clientId,
      payload: encodeBase64(update),
    });
  };

  private handleAwarenessUpdate = (
    { added, updated, removed }: { added: number[]; updated: number[]; removed: number[] },
    origin: unknown,
  ) => {
    if (origin === this) return;

    const changedClients = added.concat(updated, removed);
    if (changedClients.length === 0) return;

    this.publish({
      type: 'awareness-update',
      clientId: this.clientId,
      payload: encodeBase64(encodeAwarenessUpdate(this.awareness, changedClients)),
    });
  };

  private handleAwarenessChange = () => {
    this.onAwarenessChange?.();
  };

  private publish(message: CollaborationMessage) {
    if (!this.stompClient.connected) return;

    this.stompClient.publish({
      destination: `/app/collab/${this.noteId}`,
      body: JSON.stringify(message),
      headers: this.getCollabHeaders(),
    });
  }

  private getCollabHeaders() {
    return this.shareToken ? { 'x-share-token': this.shareToken } : undefined;
  }

  private finishSeedDecision(shouldSeed: boolean) {
    if (this.hasResolvedSeed) return;

    this.hasResolvedSeed = true;
    this.resolveSeedDecision(shouldSeed);
  }

  private setStatus(status: ProviderStatus) {
    if (this.currentStatus === status) return;

    this.currentStatus = status;
    this.onStatusChange?.(status);
  }
}

