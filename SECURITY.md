# Security Policy

## Supported protocol versions

| Protocol | Support | Security status |
| --- | --- | --- |
| `sc/v1` | Supported | Authenticated application encryption; HTTP and HTTPS supported |
| Historical standard/reserve/H5 channels | Unsupported | Removed; must not be exposed or used as a downgrade path |

All Spring Boot, Go, and Python server SDKs and all client SDKs implement the
same `sc/v1` wire contract. `sc/v1` uses a fixed outer transport and currently enables
`P256_HKDF_SHA256_AES256_GCM`. The `SM2_SM3_SM4_GCM` identifier is only a
provider boundary until an audited implementation is supplied. Historical
standard, reserve, and H5 channels must not be exposed as a production security
boundary. In particular, the former reserve channel was encoding rather than
encryption and the former H5 algorithm was not a general GB/T 32907
implementation.

## Reporting a vulnerability

Do not open a public issue containing an exploit, key material, plaintext,
production hostname, or customer data. Send a private report to the repository
maintainer with:

- affected package and version;
- protocol version and suite;
- reproduction steps or a minimal test vector;
- expected and observed impact;
- whether the issue is already being exploited.

The maintainer should acknowledge the report within 3 business days, provide a
triage decision within 7 business days, and coordinate a release before public
disclosure. Compromised key IDs must be revoked immediately rather than waiting
for a client release.

## Deployment requirements

- HTTPS with TLS 1.2 or newer is recommended for production; enterprises may
  select HTTP when their deployment and residual-risk controls require it.
- Server trust anchors and endpoint identity verification remain mandatory at
  the application protocol layer for both HTTP and HTTPS.
- `sc/v1` requires an authenticated-encryption suite and atomic replay protection.
- Long-term server keys belong in KMS/HSM-backed providers, not configuration
  files or source control.
- Client identity protection is platform-specific: WebCrypto and Android 23+
  use non-exportable keys; Android 21-22 wraps a software P-256 key; iOS stores
  raw P-256 key material in Keychain by default; Java and Go file stores contain
  exportable PKCS#8 keys protected by private-directory permissions.
- Request/response bodies, keys, nonces, complete ciphertext, and identity
  tokens must never be logged.
- Protocol downgrade and historical compatibility endpoints are forbidden in
  protocol v1 deployments.

See [the threat model](docs/security/威胁模型.md) and
[the v1 protocol specification](docs/protocol/协议v1.md). Operational setup and
troubleshooting are documented in
[the integration guide](docs/接入与调试指南.md).
