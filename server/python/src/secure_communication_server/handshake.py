import hashlib, re, time, uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Optional
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature, encode_dss_signature
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from .core import Config, ENROLLMENT_REQUIRED, HANDSHAKE_FAILED, KEY_PROVIDER_UNAVAILABLE, ProtocolError, SUITE, b64d, b64e
from .types import HandshakeContext, PendingSession, SessionKeys

_id=re.compile(r"^[A-Za-z0-9._:@/-]{1,128}$");_devices={"H5","HOST","ANDROID","IOS","EMULATOR"}
class P256Identity:
    def __init__(self,key_id:str,private_key:ec.EllipticCurvePrivateKey):self._key_id=key_id;self.private_key=private_key
    @property
    def key_id(self):return self._key_id
    @property
    def encoded_public_key(self):return self.private_key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
    def sign_transcript(self,value:bytes)->bytes:
        try:der=self.private_key.sign(value,ec.ECDSA(hashes.SHA256()));r,s=decode_dss_signature(der);return r.to_bytes(32,"big")+s.to_bytes(32,"big")
        except Exception as exc:raise ProtocolError(KEY_PROVIDER_UNAVAILABLE,exc)

def transcript_hash(request,client:bytes,installation:bytes,identity:bytes,ephemeral:bytes,kid:str,sid:str,created:int,expires:int)->bytes:
    parts=("SC1-HANDSHAKE","1",SUITE,request["appId"],request["deviceId"],request["deviceType"],b64e(client),b64e(installation),b64e(identity),b64e(ephemeral),kid,sid,str(created),str(expires))
    return hashlib.sha256("\n".join(parts).encode()).digest()
def _derive(kid,sid,private,peer,digest,expires):
    secret=private.exchange(ec.ECDH(),peer);material=HKDF(hashes.SHA256(),72,digest,("SC1/session/"+SUITE+"/"+sid).encode()).derive(secret)
    return SessionKeys(kid,sid,SUITE,material[:32],material[32:64],material[64:68],material[68:72],expires)
class HandshakeService:
    def __init__(self,identity,sessions,installations,tokens,authorizer,config:Config):self.identity=identity;self.sessions=sessions;self.installations=installations;self.tokens=tokens;self.authorizer=authorizer;self.config=config
    async def start(self,request,origin:Optional[str],remote_address:str):
        now=self.config.clock()
        try:
            if type(request["v"]) is not int or request["v"]!=1 or request["suite"]!=SUITE or not all(isinstance(request[x],str) for x in ("suite","appId","deviceId","deviceType","clientEphemeralPublicKey","installationPublicKey")) or not _id.fullmatch(request["appId"]) or not _id.fullmatch(request["deviceId"]) or request["deviceType"] not in _devices or type(request["timestamp"]) is not int or abs(now-request["timestamp"]/1000)>self.config.clock_skew_seconds:raise ProtocolError(HANDSHAKE_FAILED)
            try:client=b64d(request["clientEphemeralPublicKey"]);installation=b64d(request["installationPublicKey"])
            except ProtocolError as exc:raise ProtocolError(HANDSHAKE_FAILED,exc)
            peer=serialization.load_der_public_key(client);installation_key=serialization.load_der_public_key(installation)
            if not isinstance(peer,ec.EllipticCurvePublicKey) or not isinstance(peer.curve,ec.SECP256R1) or not isinstance(installation_key,ec.EllipticCurvePublicKey) or not isinstance(installation_key.curve,ec.SECP256R1):raise ProtocolError(HANDSHAKE_FAILED)
            registered=await self.installations.find(request["appId"],request["deviceId"])
            if registered is not None and registered!=installation:raise ProtocolError(HANDSHAKE_FAILED)
            await self.authorizer.authorize(HandshakeContext(request["appId"],request["deviceId"],request["deviceType"],origin,remote_address,registered is not None))
            is_new=registered is None
            if is_new and request["deviceType"]!="H5":
                token=request.get("enrollmentToken")
                if not isinstance(token,str) or not token.strip():raise ProtocolError(ENROLLMENT_REQUIRED)
                await self.tokens.consume(token,request["appId"],request["deviceId"],request["deviceType"])
            ephemeral_private=ec.generate_private_key(ec.SECP256R1());ephemeral=ephemeral_private.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo);identity=self.identity.encoded_public_key;kid=self.identity.key_id
            if not _id.fullmatch(kid):raise ProtocolError(HANDSHAKE_FAILED)
            sid=str(uuid.uuid4());created=int(now*1000);expires=datetime.fromtimestamp(now+self.config.session_ttl_seconds,tz=timezone.utc);digest=transcript_hash(request,client,installation,identity,ephemeral,kid,sid,created,int(expires.timestamp()*1000));signature=self.identity.sign_transcript(digest);keys=_derive(kid,sid,ephemeral_private,peer,digest,expires)
            await self.sessions.save_pending(PendingSession(keys,request["appId"],request["deviceId"],request["deviceType"],installation,digest,expires,is_new))
            return {"v":1,"suite":SUITE,"kid":kid,"sid":sid,"serverIdentityPublicKey":b64e(identity),"serverEphemeralPublicKey":b64e(ephemeral),"createdAt":created,"expiresAt":int(expires.timestamp()*1000),"signature":b64e(signature)}
        except ProtocolError:raise
        except Exception as exc:raise ProtocolError(HANDSHAKE_FAILED,exc)
    async def finish(self,request):
        try:
            if not _id.fullmatch(request["kid"]) or not _id.fullmatch(request["sid"]):raise ProtocolError(HANDSHAKE_FAILED)
            pending=await self.sessions.find_pending(request["kid"],request["sid"])
            if pending is None:raise ProtocolError(HANDSHAKE_FAILED)
            key=serialization.load_der_public_key(pending.installation_public_key)
            try:proof=b64d(request["proof"])
            except ProtocolError as exc:raise ProtocolError(HANDSHAKE_FAILED,exc)
            if len(proof)!=64:raise ValueError("invalid proof")
            der=encode_dss_signature(int.from_bytes(proof[:32],"big"),int.from_bytes(proof[32:],"big"))
            try:key.verify(der,pending.transcript_hash,ec.ECDSA(hashes.SHA256()))
            except Exception:
                await self.sessions.remove(request["kid"],request["sid"]);raise ProtocolError(HANDSHAKE_FAILED)
            if pending.register_installation:await self.installations.register(pending.app_id,pending.device_id,pending.device_type,pending.installation_public_key)
            await self.sessions.activate(request["kid"],request["sid"])
            return {"active":True,"expiresAt":int(pending.expires_at.timestamp()*1000)}
        except ProtocolError:raise
        except Exception as exc:raise ProtocolError(HANDSHAKE_FAILED,exc)
