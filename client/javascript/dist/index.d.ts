export type DeviceType = 'H5' | 'HOST' | 'SERVER' | 'ANDROID' | 'IOS' | 'EMULATOR';

export interface InstallationIdentity {
  readonly deviceId: string;
  publicKeySPKI(): Promise<Uint8Array>;
  sign(data: Uint8Array): Promise<Uint8Array>;
}

export interface IdentityStore {
  loadOrCreate(appId: string): Promise<InstallationIdentity>;
}

export interface SecureClientConfigOptions {
  baseUrl: string;
  appId: string;
  deviceType?: DeviceType;
  serverTrustAnchors: Record<string, string>;
  identityStore?: IdentityStore;
  requestTimeoutMillis?: number;
  allowedClockSkewMillis?: number;
  fetch?: typeof fetch;
  credentials?: RequestCredentials;
  allowInsecureLoopbackForTesting?: boolean;
}

export class SecureClientConfig {
  constructor(options: SecureClientConfigOptions);
  readonly baseUrl: string;
  readonly appId: string;
  readonly deviceType: DeviceType;
  readonly serverTrustAnchors: Record<string, string>;
  readonly identityStore: IdentityStore;
  readonly requestTimeoutMillis: number;
  readonly allowedClockSkewMillis: number;
}

export interface SecureRequestOptions {
  logicalPath: string;
  method?: string;
  contentType?: string;
  protectedHeaders?: Record<string, string>;
  body?: string | Uint8Array | ArrayBuffer;
  requestId?: string;
}

export class SecureRequest {
  constructor(options: SecureRequestOptions);
  readonly method: string;
  readonly logicalPath: string;
  readonly contentType: string;
  readonly protectedHeaders: Record<string, string>;
  readonly body: Uint8Array;
  readonly requestId: string | null;
}

export class SecureResponse {
  readonly status: number;
  readonly contentType: string;
  readonly body: Uint8Array;
  readonly ok: boolean;
  text(): string;
  json(): unknown;
}

export class SecureError extends Error {
  readonly code: string;
  readonly httpStatus?: number;
  readonly traceId?: string;
  readonly cause?: unknown;
}

export class MemoryIdentityStore implements IdentityStore {
  loadOrCreate(appId: string): Promise<InstallationIdentity>;
}

export class IndexedDbIdentityStore implements IdentityStore {
  constructor(options?: { databaseName?: string });
  loadOrCreate(appId: string): Promise<InstallationIdentity>;
}

export class SecureClient {
  constructor(config: SecureClientConfig | SecureClientConfigOptions);
  enroll(token: string): void;
  initialize(options?: { signal?: AbortSignal }): Promise<this>;
  request(
    request: SecureRequest | SecureRequestOptions,
    options?: { signal?: AbortSignal }
  ): Promise<SecureResponse>;
  closeSession(): void;
}

export function createSecureClient(
  config: SecureClientConfig | SecureClientConfigOptions
): SecureClient;
