import asyncio, time
from typing import Dict, Optional
from .core import ENROLLMENT_REQUIRED, HANDSHAKE_FAILED, INTERNAL_ERROR, ProtocolError
from .types import PendingSession, SessionKeys

class MemoryKeyProvider:
    def __init__(self):self.values={};self.lock=asyncio.Lock()
    async def put(self,value):
        async with self.lock:self.values[(value.key_id,value.session_id)]=value
    async def revoke(self,k,s):
        async with self.lock:self.values.pop((k,s),None)
    async def find_session(self,k,s):
        async with self.lock:return self.values.get((k,s))

class MemorySessionRepository:
    def __init__(self,clock=time.time):self.clock=clock;self.pending={};self.active={};self.lock=asyncio.Lock()
    async def save_pending(self,value):
        async with self.lock:self.pending[(value.keys.key_id,value.keys.session_id)]=value
    async def find_pending(self,k,s):
        async with self.lock:
            value=self.pending.get((k,s))
            if value and value.expires_at.timestamp()<=self.clock():self.pending.pop((k,s),None);return None
            return value
    async def activate(self,k,s):
        async with self.lock:
            value=self.pending.pop((k,s),None)
            if value and value.expires_at.timestamp()>self.clock():self.active[(k,s)]=value.keys
    async def remove(self,k,s):
        async with self.lock:self.pending.pop((k,s),None);self.active.pop((k,s),None)
    async def find_session(self,k,s):
        async with self.lock:
            value=self.active.get((k,s))
            if value and value.expires_at.timestamp()<=self.clock():self.active.pop((k,s),None);return None
            return value
class MemoryReplayProtector:
    def __init__(self,clock=time.time):self.clock=clock;self.values={};self.lock=asyncio.Lock()
    async def claim(self,s,d,q,ttl):
        if not s or q<1 or ttl<=0:raise ProtocolError(INTERNAL_ERROR)
        async with self.lock:
            now=self.clock();key=(s,d,q)
            if self.values.get(key,0)>now:return False
            self.values[key]=now+ttl
            if len(self.values)&1023==0:self.values={k:v for k,v in self.values.items() if v>now}
            return True
class MemoryInstallationRegistry:
    def __init__(self):self.values={};self.lock=asyncio.Lock()
    async def find(self,a,d):
        async with self.lock:return self.values.get((a,d))
    async def register(self,a,d,device_type,key):
        async with self.lock:
            old=self.values.get((a,d))
            if old is not None and old!=key:raise ProtocolError(HANDSHAKE_FAILED)
            self.values[(a,d)]=bytes(key)
class RejectingEnrollmentTokens:
    async def issue(self,*args):raise ProtocolError(ENROLLMENT_REQUIRED)
    async def consume(self,*args):raise ProtocolError(ENROLLMENT_REQUIRED)
class RejectingHandshakeAuthorizer:
    async def authorize(self,context):raise ProtocolError(HANDSHAKE_FAILED)
class RejectingRoutes:
    def is_allowed(self,method,path):return False
