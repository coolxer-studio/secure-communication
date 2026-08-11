import base64, hashlib, json, os, time
from datetime import datetime, timezone
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from redis.asyncio import Redis
from .core import ENROLLMENT_REQUIRED, HANDSHAKE_FAILED, KEY_PROVIDER_UNAVAILABLE, REPLAY_STORE_UNAVAILABLE, UNKNOWN_SESSION, ProtocolError, b64d, b64e
from .types import PendingSession, SessionKeys

class AESGCMRecordProtector:
    def __init__(self,key:bytes):
        if len(key)!=32:raise ValueError("session record key must contain 32 bytes")
        self.key=bytes(key)
    def protect(self,value:bytes)->bytes:
        nonce=os.urandom(12);return nonce+AESGCM(self.key).encrypt(nonce,value,b"SC1-REDIS-SESSION")
    def unprotect(self,value:bytes)->bytes:
        try:return AESGCM(self.key).decrypt(value[:12],value[12:],b"SC1-REDIS-SESSION")
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
class RedisReplayProtector:
    def __init__(self,redis:Redis,prefix:str):self.redis=redis;self.prefix=prefix
    async def claim(self,s,d,q,ttl):
        try:return bool(await self.redis.set(f"{self.prefix}:{s}:{d}:{q}","1",nx=True,px=max(1,int(ttl*1000))))
        except Exception as exc:raise ProtocolError(REPLAY_STORE_UNAVAILABLE,exc)
class RedisInstallationRegistry:
    def __init__(self,redis:Redis,prefix:str):self.redis=redis;self.prefix=prefix
    def key(self,a,d):return self.prefix+":"+hashlib.sha256((a+"\n"+d).encode()).hexdigest()
    async def find(self,a,d):
        try:
            value=await self.redis.get(self.key(a,d));return None if value is None else b64d(value.decode() if isinstance(value,bytes) else value)
        except ProtocolError:raise
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
    async def register(self,a,d,device_type,key):
        try:
            encoded=b64e(key);redis_key=self.key(a,d)
            if not await self.redis.set(redis_key,encoded,nx=True):
                old=await self.redis.get(redis_key);old=old.decode() if isinstance(old,bytes) else old
                if old!=encoded:raise ProtocolError(HANDSHAKE_FAILED)
        except ProtocolError:raise
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
class RedisEnrollmentTokens:
    script="local v=redis.call('GET',KEYS[1]); if v==ARGV[1] then redis.call('DEL',KEYS[1]); return 1 else return 0 end"
    def __init__(self,redis:Redis,prefix:str):self.redis=redis;self.prefix=prefix
    def key(self,t):return self.prefix+":"+hashlib.sha256(t.encode()).hexdigest()
    async def issue(self,a,d,ttl):
        if ttl<=0 or ttl>3600:raise ValueError("enrollment token TTL must be within one hour")
        try:
            token=b64e(os.urandom(32));await self.redis.set(self.key(token),a+"\n"+d,px=max(1,int(ttl*1000)));return token
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
    async def consume(self,t,a,device_id,d):
        try:
            if int(await self.redis.eval(self.script,1,self.key(t),a+"\n"+d))!=1:raise ProtocolError(ENROLLMENT_REQUIRED)
        except ProtocolError:raise
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
class RedisSessionRepository:
    def __init__(self,redis:Redis,prefix:str,protector,clock=time.time):self.redis=redis;self.prefix=prefix;self.protector=protector;self.clock=clock
    def key(self,state,k,s):return f"{self.prefix}:{state}:{k}:{s}"
    def record(self,p,active=False):
        k=p.keys;return {"keyId":k.key_id,"sessionId":k.session_id,"suite":k.suite,"requestKey":b64e(k.request_key),"responseKey":b64e(k.response_key),"requestPrefix":b64e(k.request_nonce_prefix),"responsePrefix":b64e(k.response_nonce_prefix),"expiresAt":int(p.expires_at.timestamp()*1000),"revoked":k.revoked,"active":active,"appId":p.app_id,"deviceId":p.device_id,"deviceType":p.device_type,"installationPublicKey":b64e(p.installation_public_key),"transcriptHash":b64e(p.transcript_hash),"registerInstallation":p.register_installation}
    def pending(self,x):
        expires=datetime.fromtimestamp(x["expiresAt"]/1000,tz=timezone.utc);keys=SessionKeys(x["keyId"],x["sessionId"],x["suite"],b64d(x["requestKey"]),b64d(x["responseKey"]),b64d(x["requestPrefix"]),b64d(x["responsePrefix"]),expires,x["revoked"]);return PendingSession(keys,x["appId"],x["deviceId"],x["deviceType"],b64d(x["installationPublicKey"]),b64d(x["transcriptHash"]),expires,x["registerInstallation"])
    async def write(self,key,x):
        ttl=x["expiresAt"]-int(self.clock()*1000)
        if ttl<=0:raise ProtocolError(UNKNOWN_SESSION)
        try:await self.redis.set(key,b64e(self.protector.protect(json.dumps(x,separators=(",",":")).encode())),px=ttl)
        except ProtocolError:raise
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
    async def read(self,key):
        try:
            value=await self.redis.get(key)
            if value is None:return None
            value=value.decode() if isinstance(value,bytes) else value;x=json.loads(self.protector.unprotect(b64d(value)))
            if x["expiresAt"]<=int(self.clock()*1000):await self.redis.delete(key);return None
            return x
        except ProtocolError:raise
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
    async def save_pending(self,p):await self.write(self.key("pending",p.keys.key_id,p.keys.session_id),self.record(p))
    async def find_pending(self,k,s):
        x=await self.read(self.key("pending",k,s));return None if x is None else self.pending(x)
    async def activate(self,k,s):
        pending_key=self.key("pending",k,s);x=await self.read(pending_key)
        if x is None:raise ProtocolError(HANDSHAKE_FAILED)
        x["active"]=True;await self.write(self.key("active",k,s),x)
        try:await self.redis.delete(pending_key)
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
    async def remove(self,k,s):
        try:await self.redis.delete(self.key("pending",k,s),self.key("active",k,s))
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)
    async def find_session(self,k,s):
        x=await self.read(self.key("active",k,s));return None if x is None else self.pending(x).keys
