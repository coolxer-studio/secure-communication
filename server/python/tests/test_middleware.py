import json
from datetime import datetime, timezone
import httpx
import pytest
from fastapi import FastAPI, Request
from fastapi.responses import Response
from secure_communication_server import *
from secure_communication_server.middleware import install_fastapi_routes

class Routes:
    def is_allowed(self,method,path):return method=="POST" and path=="/v1/ping"
@pytest.mark.asyncio
async def test_asgi_rewrite_and_encrypted_response():
    fixed=1785283200.0;config=Config(enabled=True,clock=lambda:fixed);repo=MemorySessionRepository(lambda:fixed);keys=SessionKeys("key","session",SUITE,b"\x01"*32,b"\x02"*32,b"\x01\x02\x03\x04",b"\x05\x06\x07\x08",datetime.fromtimestamp(fixed+60,tz=timezone.utc));await repo.save_pending(PendingSession(keys,"app","device","H5",b"key",b"hash",keys.expires_at,False));await repo.activate("key","session");messages=MessageService(repo,MemoryReplayProtector(lambda:fixed),config);app=FastAPI()
    @app.post("/v1/ping")
    async def ping(request:Request):
        assert request.url.query=="a=1";assert request.headers["x-sc-request-id"]=="request-1";assert await request.body()==b"hello";return Response(b"ok",201,media_type="text/plain")
    app.add_middleware(SecureCommunicationMiddleware,config=config,messages=messages,routes=Routes());payload=json.dumps({"method":"POST","path":"/v1/ping?a=1","contentType":"application/json","headers":{},"body":b64e(b"hello")},separators=(",",":")).encode();e=Envelope(1,SUITE,"key","session",int(fixed*1000),1,"request-1","POST",MESSAGE_ENDPOINT,PROTECTED_MEDIA_TYPE,0,"","");n=nonce(keys.request_nonce_prefix,1);e.nonce=b64e(n);e.ct=b64e(AESGCMAlgorithm().seal(keys.request_key,n,aad("request",e),payload));transport=httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport,base_url="https://example.test") as client:response=await client.post(MESSAGE_ENDPOINT,content=json.dumps(e.__dict__,separators=(",",":")),headers={"content-type":ENVELOPE_MEDIA_TYPE})
    assert response.status_code==200;sealed=Envelope(**response.json());rn=nonce(keys.response_nonce_prefix,1);plain=AESGCMAlgorithm().open(keys.response_key,rn,aad("response",sealed),b64d(sealed.ct));result=json.loads(plain);assert sealed.st==201;assert result=={"contentType":"text/plain","body":b64e(b"ok")}

@pytest.mark.asyncio
async def test_fastapi_handshake_route_is_strict_and_fail_closed():
    class Reject:
        async def start(self,*args):raise ProtocolError(HANDSHAKE_FAILED)
        async def finish(self,*args):raise ProtocolError(HANDSHAKE_FAILED)
    app=FastAPI();install_fastapi_routes(app,Reject(),Config(require_tls=False));transport=httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport,base_url="http://example.test") as client:
        response=await client.post(HANDSHAKE_ENDPOINT,json={"unexpected":True})
    assert response.status_code==400 and response.json()["code"]==INVALID_ENVELOPE.code
