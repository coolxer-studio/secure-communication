export {
  SecureCommunicationError,
  V1_ENVELOPE_MEDIA_TYPE,
  V1_INTERNATIONAL_SUITE,
  createV1Codec,
  deriveAesGcmSession,
  importAesGcmSession,
  normalizeV1Path,
  verifyP256Transcript
} from './v1.js';
export { createSecureFetch } from './transport.js';
export {
  SecureClient,
  createSecureClient,
  IndexedDbIdentityStore,
  MemoryIdentityStore
} from './client.js';
