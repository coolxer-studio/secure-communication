import os, time
from datetime import datetime, timezone
import pytest
from redis.asyncio import Redis
from secure_communication_server import PendingSession, ProtocolError, SessionKeys, SUITE
from secure_communication_server.redis_store import *

@pytest.mark.asyncio
async def test_redis_stores():
    redis=Redis.from_url(os.getenv("REDIS_URL","redis://127.0.0.1:6379"))
    try:await redis.ping()
    except Exception:pytest.skip("Redis is not available")
    prefix="sc:test:%d"%time.time_ns();protector=AESGCMRecordProtector(os.urandom(32));repo=RedisSessionRepository(redis,prefix+":session",protector);expires=datetime.fromtimestamp(time.time()+60,tz=timezone.utc);keys=SessionKeys("key","session",SUITE,b"\0"*32,b"\1"*32,b"1234",b"5678",expires);await repo.save_pending(PendingSession(keys,"app","device","H5",b"key",b"hash",expires,False));await repo.activate("key","session");assert await repo.find_session("key","session");replay=RedisReplayProtector(redis,prefix+":replay");assert await replay.claim("session","request",1,60);assert not await replay.claim("session","request",1,60);tokens=RedisEnrollmentTokens(redis,prefix+":token");token=await tokens.issue("app","HOST",60);await tokens.consume(token,"app","device","HOST")
    with pytest.raises(ProtocolError):await tokens.consume(token,"app","device","HOST")
    async for key in redis.scan_iter(prefix+"*"):await redis.delete(key)
    await redis.aclose()
