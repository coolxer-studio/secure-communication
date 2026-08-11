import base64, hashlib, hmac, json, re, struct, time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Callable, Dict, Iterable, Mapping, Optional, Sequence
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from .types import Envelope, OpenedRequest, SessionKeys

VERSION=1; SUITE="P256_HKDF_SHA256_AES256_GCM"; ENVELOPE_MEDIA_TYPE="application/sc-envelope+json"; PROTECTED_MEDIA_TYPE="application/sc-protected+json"; HANDSHAKE_ENDPOINT="/sc/v1/handshake"; HANDSHAKE_FINISH_ENDPOINT="/sc/v1/handshake/finish"; MESSAGE_ENDPOINT="/sc/v1/message"
@dataclass(frozen=True)
class ErrorCode: http_status:int; code:str; message:str
INVALID_ENVELOPE=ErrorCode(400,"SC_INVALID_ENVELOPE","Secure envelope is invalid"); ROUTE_MISMATCH=ErrorCode(400,"SC_ROUTE_MISMATCH","Secure route binding does not match"); TLS_REQUIRED=ErrorCode(400,"SC_TLS_REQUIRED","Secure communication requires TLS"); UNKNOWN_SESSION=ErrorCode(401,"SC_UNKNOWN_SESSION","Secure session is unavailable"); AUTHENTICATION_FAILED=ErrorCode(401,"SC_AUTHENTICATION_FAILED","Secure message authentication failed"); HANDSHAKE_FAILED=ErrorCode(401,"SC_HANDSHAKE_FAILED","Secure handshake failed"); ENROLLMENT_REQUIRED=ErrorCode(401,"SC_ENROLLMENT_REQUIRED","Installation enrollment is required"); REQUEST_EXPIRED=ErrorCode(408,"SC_REQUEST_EXPIRED","Secure request is outside the accepted time window"); REPLAY_DETECTED=ErrorCode(409,"SC_REPLAY_DETECTED","Secure request was already accepted"); PAYLOAD_TOO_LARGE=ErrorCode(413,"SC_PAYLOAD_TOO_LARGE","Secure payload is too large"); UNSUPPORTED_VERSION=ErrorCode(426,"SC_UNSUPPORTED_VERSION","Secure protocol version is unsupported"); UNSUPPORTED_SUITE=ErrorCode(426,"SC_UNSUPPORTED_SUITE","Secure algorithm suite is unsupported"); KEY_PROVIDER_UNAVAILABLE=ErrorCode(503,"SC_KEY_PROVIDER_UNAVAILABLE","Secure key provider is unavailable"); REPLAY_STORE_UNAVAILABLE=ErrorCode(503,"SC_REPLAY_STORE_UNAVAILABLE","Secure replay store is unavailable"); INTERNAL_ERROR=ErrorCode(500,"SC_INTERNAL_ERROR","Secure communication failed")
class ProtocolError(Exception):
    def __init__(self,error:ErrorCode,cause:Optional[BaseException]=None): super().__init__(error.code);self.error=error;self.__cause__=cause

@dataclass
class Config:
    enabled:bool=False; v1_enabled:bool=True; prefix:str=MESSAGE_ENDPOINT; require_tls:bool=True; allowed_suites:Sequence[str]=(SUITE,); clock_skew_seconds:float=300; replay_ttl_seconds:float=600; session_ttl_seconds:float=600; max_envelope_bytes:int=1_400_000; max_plaintext_bytes:int=1_048_576; max_body_bytes:int=1_048_576; clock:Callable[[],float]=time.time
    def validate(self):
        if not self.prefix.startswith("/") or self.clock_skew_seconds<0 or self.replay_ttl_seconds<=0 or self.session_ttl_seconds<=0 or self.max_envelope_bytes<256 or not 0<=self.max_body_bytes<=self.max_plaintext_bytes: raise ValueError("invalid secure communication config")

def b64e(value:bytes)->str:return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")
_b64=re.compile(r"^[A-Za-z0-9_-]+$")
def b64d(value:str,allow_empty:bool=False)->bytes:
    if value=="" and allow_empty:return b""
    if not isinstance(value,str) or not _b64.fullmatch(value):raise ProtocolError(INVALID_ENVELOPE)
    try:return base64.urlsafe_b64decode(value+"="*((4-len(value)%4)%4))
    except Exception as exc:raise ProtocolError(INVALID_ENVELOPE,exc)
def strict_object(data:bytes,required:Iterable[str],optional:Iterable[str]=())->Dict[str,object]:
    try:value=json.loads(data.decode("utf-8"),object_pairs_hook=_unique_pairs)
    except ProtocolError:raise
    except Exception as exc:raise ProtocolError(INVALID_ENVELOPE,exc)
    if not isinstance(value,dict) or not set(required)<=set(value) or not set(value)<=set(required)|set(optional):raise ProtocolError(INVALID_ENVELOPE)
    return value
def _unique_pairs(pairs):
    out={}
    for key,value in pairs:
        if key in out:raise ProtocolError(INVALID_ENVELOPE)
        out[key]=value
    return out
def nonce(prefix:bytes,sequence:int)->bytes:
    if len(prefix)!=4 or not 1<=sequence<=9007199254740991:raise ProtocolError(INVALID_ENVELOPE)
    return prefix+struct.pack(">Q",sequence)
def aad(direction:str,e:Envelope)->bytes:
    parts=["SC1",direction,e.suite,e.kid,e.sid,str(e.ts),str(e.seq),e.rid,e.m,e.p,e.cty]
    if direction=="response":parts.append(str(e.st))
    return "\n".join(parts).encode()
def normalize_content_type(value:Optional[str])->str:
    result=(value or "application/octet-stream").split(";",1)[0].strip().lower()
    if not re.fullmatch(r"[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+",result):raise ProtocolError(INVALID_ENVELOPE)
    return result
def normalize_path(value:str)->str:
    if not isinstance(value,str) or not value.startswith("/") or value.count("?")>1 or any(x in value for x in ("\n","\r","#","://"," ")):raise ProtocolError(INVALID_ENVELOPE)
    parts=value.split("?",1);path=_upper_percent(parts[0]);pairs=[] if len(parts)==1 else [_upper_percent(x) for x in parts[1].split("&") if x]
    def key(pair):
        name,sep,item=pair.partition("=");return name,item if sep else ""
    pairs.sort(key=key);return path+("?"+"&".join(pairs) if pairs else "")
def _upper_percent(value:str)->str:
    if re.search(r"%(?![0-9A-Fa-f]{2})",value):raise ProtocolError(INVALID_ENVELOPE)
    return re.sub(r"%([0-9A-Fa-f]{2})",lambda m:"%"+m.group(1).upper(),value)
class AESGCMAlgorithm:
    suite=SUITE
    def seal(self,key,nonce_value,aad_value,plaintext):return AESGCM(key).encrypt(nonce_value,plaintext,aad_value)
    def open(self,key,nonce_value,aad_value,ciphertext):
        try:return AESGCM(key).decrypt(nonce_value,ciphertext,aad_value)
        except InvalidTag as exc:raise ProtocolError(AUTHENTICATION_FAILED,exc)

class MessageService:
    def __init__(self,keys,replay,config:Config,algorithms:Iterable[object]=(AESGCMAlgorithm(),)):
        config.validate();self.keys=keys;self.replay=replay;self.config=config;self.algorithms={a.suite:a for a in algorithms}
    async def open(self,data:bytes)->OpenedRequest:
        if len(data)>self.config.max_envelope_bytes:raise ProtocolError(PAYLOAD_TOO_LARGE)
        raw=strict_object(data,("v","suite","kid","sid","ts","seq","rid","m","p","cty","st","nonce","ct"))
        try:e=Envelope(**raw)
        except Exception as exc:raise ProtocolError(INVALID_ENVELOPE,exc)
        if type(e.v) is not int or e.v!=1:raise ProtocolError(UNSUPPORTED_VERSION)
        if e.suite not in self.config.allowed_suites:raise ProtocolError(UNSUPPORTED_SUITE)
        ident=re.compile(r"^[\x21-\x7e]{1,128}$")
        if not all(isinstance(x,str) and ident.fullmatch(x) for x in (e.kid,e.sid,e.rid)) or type(e.ts)is not int or type(e.seq)is not int or type(e.st)is not int or not 1<=e.seq<=9007199254740991 or e.st!=0:raise ProtocolError(INVALID_ENVELOPE)
        if (e.m,e.p,e.cty)!=("POST",MESSAGE_ENDPOINT,PROTECTED_MEDIA_TYPE):raise ProtocolError(ROUTE_MISMATCH)
        now=self.config.clock();
        if abs(now-e.ts/1000)>self.config.clock_skew_seconds:raise ProtocolError(REQUEST_EXPIRED)
        session=await self.keys.find_session(e.kid,e.sid)
        if session is None or session.revoked or session.expires_at.timestamp()<=now or session.suite!=e.suite:raise ProtocolError(UNKNOWN_SESSION)
        received=b64d(e.nonce)
        if not hmac.compare_digest(received,nonce(session.request_nonce_prefix,e.seq)):raise ProtocolError(INVALID_ENVELOPE)
        ciphertext=b64d(e.ct)
        if not 16<=len(ciphertext)<=self.config.max_plaintext_bytes+16:raise ProtocolError(PAYLOAD_TOO_LARGE)
        algorithm=self.algorithms.get(e.suite)
        if algorithm is None:raise ProtocolError(UNSUPPORTED_SUITE)
        plaintext=algorithm.open(session.request_key,received,aad("request",e),ciphertext)
        if len(plaintext)>self.config.max_plaintext_bytes:raise ProtocolError(PAYLOAD_TOO_LARGE)
        if not await self.replay.claim(e.sid,"request",e.seq,self.config.replay_ttl_seconds):raise ProtocolError(REPLAY_DETECTED)
        return OpenedRequest(e,session,plaintext)
    def seal(self,opened:OpenedRequest,plaintext:bytes,content_type:str,status:int)->bytes:
        if len(plaintext)>self.config.max_plaintext_bytes:raise ProtocolError(PAYLOAD_TOO_LARGE)
        if not 100<=status<=599:raise ProtocolError(INTERNAL_ERROR)
        normalize_content_type(content_type);s=opened.session
        e=Envelope(1,s.suite,s.key_id,s.session_id,int(self.config.clock()*1000),opened.envelope.seq,opened.envelope.rid,"POST",MESSAGE_ENDPOINT,PROTECTED_MEDIA_TYPE,status,"","")
        n=nonce(s.response_nonce_prefix,e.seq);e.nonce=b64e(n);e.ct=b64e(self.algorithms[s.suite].seal(s.response_key,n,aad("response",e),plaintext))
        return json.dumps(asdict(e),separators=(",",":")).encode()
def decode_protected(data:bytes,max_body:int):
    raw=strict_object(data,("method","path","contentType","headers","body"));method=raw["method"];path=raw["path"];ct=raw["contentType"];headers=raw["headers"]
    if not isinstance(method,str) or not re.fullmatch(r"[A-Z]{3,16}",method) or normalize_path(path)!=path or normalize_content_type(ct)!=ct or not isinstance(headers,dict) or len(headers)>32:raise ProtocolError(INVALID_ENVELOPE)
    for k,v in headers.items():
        if not isinstance(k,str) or not re.fullmatch(r"[a-z0-9-]{1,64}",k) or not isinstance(v,str) or "\r" in v or "\n" in v or len(v.encode())>8192:raise ProtocolError(INVALID_ENVELOPE)
    body=b64d(raw["body"],True)
    if len(body)>max_body:raise ProtocolError(PAYLOAD_TOO_LARGE)
    return method,path,ct,headers,body
