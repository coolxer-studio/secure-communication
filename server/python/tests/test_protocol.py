import hashlib, json
from datetime import datetime, timezone
from pathlib import Path
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from secure_communication_server import *

ROOT=Path(__file__).parents[3]
@pytest.mark.parametrize("name",["aes-256-gcm-request.json","aes-256-gcm-response.json"])
def test_vectors(name):
    v=json.loads((ROOT/"protocol/test-vectors"/name).read_text());key=bytes.fromhex(v["keyHex"]);prefix=bytes.fromhex(v["noncePrefixHex"]);n=nonce(prefix,v["sequence"]);assert b64e(n)==v["nonceBase64Url"]
    e=Envelope(1,v["suite"],v["kid"],v["sid"],v["timestamp"],v["sequence"],v["requestId"],v["method"],v["path"],v["contentType"],v["logicalStatus"],"","");associated=aad(v["direction"],e);assert associated.decode()==v["aadUtf8"];sealed=AESGCMAlgorithm().seal(key,n,associated,v["plaintextUtf8"].encode());assert b64e(sealed)==v["combinedCiphertextBase64Url"];assert AESGCMAlgorithm().open(key,n,associated,sealed).decode()==v["plaintextUtf8"]
def test_normalization_and_strict_json():
    assert normalize_path("/cross/info?x=1&lang=zh")=="/cross/info?lang=zh&x=1"
    with pytest.raises(ProtocolError):strict_object(b'{"v":1,"v":2}',("v",))
    with pytest.raises(ProtocolError):normalize_path("https://example.test/a")
class Allow:
    async def authorize(self,context):return None
class Tokens:
    async def issue(self,*args):return "token"
    async def consume(self,*args):return None
@pytest.mark.asyncio
async def test_handshake_message_and_replay():
    fixed=1785283200.0;config=Config(enabled=True,require_tls=False,clock=lambda:fixed);repo=MemorySessionRepository(lambda:fixed);installations=MemoryInstallationRegistry();server_key=ec.generate_private_key(ec.SECP256R1());service=HandshakeService(P256Identity("server-key",server_key),repo,installations,Tokens(),Allow(),config);ephemeral=ec.generate_private_key(ec.SECP256R1());client_spki=ephemeral.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo);installation=ec.generate_private_key(ec.SECP256R1());installation_spki=installation.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo);request={"v":1,"suite":SUITE,"appId":"demo","deviceId":"device","deviceType":"H5","clientEphemeralPublicKey":b64e(client_spki),"installationPublicKey":b64e(installation_spki),"timestamp":int(fixed*1000)};start=await service.start(request,"https://example.test","127.0.0.1");pending=await repo.find_pending(start["kid"],start["sid"]);der=installation.sign(pending.transcript_hash,ec.ECDSA(hashes.SHA256()));r,s=decode_dss_signature(der);proof=r.to_bytes(32,"big")+s.to_bytes(32,"big");finish=await service.finish({"kid":start["kid"],"sid":start["sid"],"proof":b64e(proof)});assert finish["active"]
    session=await repo.find_session(start["kid"],start["sid"]);messages=MessageService(repo,MemoryReplayProtector(lambda:fixed),config);payload=json.dumps({"method":"POST","path":"/v1/ping","contentType":"application/json","headers":{"scid":"abc"},"body":b64e(b"hello")},separators=(",",":")).encode();e=Envelope(1,SUITE,session.key_id,session.session_id,int(fixed*1000),1,"request-1","POST",MESSAGE_ENDPOINT,PROTECTED_MEDIA_TYPE,0,"","");n=nonce(session.request_nonce_prefix,1);e.nonce=b64e(n);e.ct=b64e(AESGCMAlgorithm().seal(session.request_key,n,aad("request",e),payload));wire=json.dumps(e.__dict__,separators=(",",":")).encode();opened=await messages.open(wire);assert decode_protected(opened.plaintext,config.max_body_bytes)[-1]==b"hello"
    with pytest.raises(ProtocolError) as replay:await messages.open(wire)
    assert replay.value.error==REPLAY_DETECTED
    protected=json.dumps({"contentType":"text/plain","body":b64e(b"ok")},separators=(",",":")).encode();assert messages.seal(opened,protected,"text/plain",201)
