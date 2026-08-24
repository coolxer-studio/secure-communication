import os
from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse
from secure_communication_server import Config, RejectingRoutes, SecureCommunicationMiddleware

config=Config(enabled=os.getenv("SC_ENABLED","false").lower()=="true")
app=FastAPI(title="Secure Communication Python Demo")
@app.post("/v1/ping")
async def ping(request:Request):
    body=(await request.body()).decode(errors="replace");return PlainTextResponse("server received: scid->%s,strBody->%s"%(request.headers.get("scid",""),body))
app.add_middleware(SecureCommunicationMiddleware,config=config,messages=None,routes=RejectingRoutes())
