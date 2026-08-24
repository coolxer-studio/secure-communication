from dataclasses import dataclass, field
from datetime import datetime
from typing import Dict, Optional

@dataclass
class SessionKeys:
    key_id: str; session_id: str; suite: str; request_key: bytes; response_key: bytes
    request_nonce_prefix: bytes; response_nonce_prefix: bytes; expires_at: datetime; revoked: bool = False
    def __post_init__(self):
        if len(self.request_nonce_prefix) != 4 or len(self.response_nonce_prefix) != 4: raise ValueError("nonce prefix must contain four bytes")
@dataclass
class PendingSession:
    keys: SessionKeys; app_id: str; device_id: str; device_type: str; installation_public_key: bytes
    transcript_hash: bytes; expires_at: datetime; register_installation: bool
@dataclass(frozen=True)
class HandshakeContext:
    app_id: str; device_id: str; device_type: str; origin: Optional[str]; remote_address: str; registered_installation: bool
@dataclass
class Envelope:
    v: int; suite: str; kid: str; sid: str; ts: int; seq: int; rid: str; m: str; p: str; cty: str; st: int; nonce: str; ct: str
@dataclass
class OpenedRequest:
    envelope: Envelope; session: SessionKeys; plaintext: bytes
