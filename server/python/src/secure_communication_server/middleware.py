import json, logging, time, uuid
from typing import Optional
from urllib.parse import unquote
from fastapi import Request, Response
from .core import ENVELOPE_MEDIA_TYPE, HANDSHAKE_ENDPOINT, HANDSHAKE_FINISH_ENDPOINT, INVALID_ENVELOPE, INTERNAL_ERROR, PAYLOAD_TOO_LARGE, ROUTE_MISMATCH, TLS_REQUIRED, Config, ProtocolError, b64e, decode_protected, normalize_content_type, strict_object

log=logging.getLogger("secure_communication")
def _error(error):
    trace=str(uuid.uuid4());body=json.dumps({"code":error.code,"message":error.message,"traceId":trace},separators=(",",":")).encode()
    return error.http_status,[(b"content-type",b"application/json"),(b"x-trace-id",trace.encode()),(b"content-length",str(len(body)).encode())],body
def _media(value):return value.decode("latin1").split(";",1)[0].strip().lower() if value else ""
def _headers(scope):return {k.lower():v for k,v in scope.get("headers",[])}
async def _body(receive,maximum):
    result=bytearray()
    while True:
        message=await receive()
        if message["type"]!="http.request":continue
        result.extend(message.get("body",b""))
        if len(result)>maximum:raise ProtocolError(PAYLOAD_TOO_LARGE)
        if not message.get("more_body",False):return bytes(result)

class SecureCommunicationMiddleware:
    def __init__(self,app,config:Config,messages=None,routes=None):self.app=app;self.config=config;self.messages=messages;self.routes=routes
    async def __call__(self,scope,receive,send):
        if scope["type"]!="http" or not self.config.enabled or not self.config.v1_enabled or not (scope["path"]==self.config.prefix or scope["path"].startswith(self.config.prefix+"/")):await self.app(scope,receive,send);return
        if scope["method"].upper()=="OPTIONS":await self.app(scope,receive,send);return
        started=time.monotonic();opened=None
        try:
            if self.config.require_tls and scope.get("scheme")!="https":raise ProtocolError(TLS_REQUIRED)
            headers=_headers(scope)
            if scope["method"].upper()!="POST" or scope["path"]!=self.config.prefix:raise ProtocolError(ROUTE_MISMATCH)
            if _media(headers.get(b"content-type"))!=ENVELOPE_MEDIA_TYPE:raise ProtocolError(INVALID_ENVELOPE)
            if self.messages is None:raise ProtocolError(INTERNAL_ERROR)
            opened=await self.messages.open(await _body(receive,self.config.max_envelope_bytes))
            method,path,content_type,protected_headers,body=decode_protected(opened.plaintext,self.config.max_body_bytes);route_path,_,query=path.partition("?")
            if self.routes is None or not self.routes.is_allowed(method,route_path):raise ProtocolError(ROUTE_MISMATCH)
            new_scope=dict(scope);new_scope.update(method=method,path=unquote(route_path),raw_path=route_path.encode(),query_string=query.encode())
            replaced={k.lower().encode() for k in protected_headers}|{b"x-sc-request-id",b"content-type",b"content-length"};outer=[(k,v) for k,v in scope.get("headers",[]) if k.lower() not in replaced]
            inner=[(k.lower().encode(),v.encode()) for k,v in protected_headers.items()];inner.extend([(b"x-sc-request-id",opened.envelope.rid.encode()),(b"content-type",content_type.encode()),(b"content-length",str(len(body)).encode())]);new_scope["headers"]=outer+inner;new_scope["sc.transportTrust"]="sc1-authenticated/"+opened.envelope.suite;new_scope["sc.sessionId"]=opened.envelope.sid
            delivered=False
            async def inner_receive():
                nonlocal delivered
                if delivered:return {"type":"http.disconnect"}
                delivered=True;return {"type":"http.request","body":body,"more_body":False}
            status=200;response_headers=[];response_body=bytearray()
            async def inner_send(message):
                nonlocal status,response_headers
                if message["type"]=="http.response.start":status=message["status"];response_headers=message.get("headers",[])
                elif message["type"]=="http.response.body":response_body.extend(message.get("body",b""))
            await self.app(new_scope,inner_receive,inner_send)
            raw_content_type=next((v for k,v in response_headers if k.lower()==b"content-type"),b"application/octet-stream");logical_content_type=normalize_content_type(raw_content_type.decode("latin1"));protected=json.dumps({"contentType":logical_content_type,"body":b64e(bytes(response_body))},separators=(",",":")).encode();sealed=self.messages.seal(opened,protected,logical_content_type,status)
            await send({"type":"http.response.start","status":200,"headers":[(b"content-type",ENVELOPE_MEDIA_TYPE.encode()),(b"content-length",str(len(sealed)).encode())]});await send({"type":"http.response.body","body":sealed})
        except ProtocolError as exc:
            status,headers,body=_error(exc.error);await send({"type":"http.response.start","status":status,"headers":headers});await send({"type":"http.response.body","body":body});sid=opened.envelope.sid if opened else "-";rid=opened.envelope.rid if opened else "-";log.warning("secure_communication_failure version=1 requestId=%s session=%s error=%s durationMs=%d",rid,_summary(sid),exc.error.code,int((time.monotonic()-started)*1000))
        except Exception:
            status,headers,body=_error(INTERNAL_ERROR);await send({"type":"http.response.start","status":status,"headers":headers});await send({"type":"http.response.body","body":body});log.exception("secure_communication_failure version=1 error=%s",INTERNAL_ERROR.code)
def _summary(value):
    import hashlib
    return hashlib.sha256(value.encode()).hexdigest()[:12]

def install_fastapi_routes(app,handshakes,config:Config):
    async def execute(request:Request,finish:bool):
        try:
            if config.require_tls and request.url.scheme!="https":raise ProtocolError(TLS_REQUIRED)
            if _media(request.headers.get("content-type","").encode())!="application/json":raise ProtocolError(INVALID_ENVELOPE)
            data=await request.body()
            if len(data)>config.max_envelope_bytes:raise ProtocolError(PAYLOAD_TOO_LARGE)
            if finish:raw=strict_object(data,("kid","sid","proof"));result=await handshakes.finish(raw)
            else:raw=strict_object(data,("v","suite","appId","deviceId","deviceType","clientEphemeralPublicKey","installationPublicKey","timestamp"),("enrollmentToken",));client=request.client.host if request.client else "";result=await handshakes.start(raw,request.headers.get("origin"),client)
            return Response(json.dumps(result,separators=(",",":")),200,media_type="application/json")
        except ProtocolError as exc:
            status,headers,body=_error(exc.error);return Response(body,status,dict((k.decode(),v.decode()) for k,v in headers),media_type=None)
    async def start_endpoint(request:Request):return await execute(request,False)
    async def finish_endpoint(request:Request):return await execute(request,True)
    app.add_api_route(HANDSHAKE_ENDPOINT,start_endpoint,methods=["POST"],include_in_schema=False)
    app.add_api_route(HANDSHAKE_FINISH_ENDPOINT,finish_endpoint,methods=["POST"],include_in_schema=False)
