package redisstore

import (
	"context"
	"crypto/rand"
	"testing"
	"time"

	sc "github.com/coolxer/secure-communication-server-go"
	"github.com/redis/go-redis/v9"
)

func TestRedisStores(t *testing.T) {
	ctx := context.Background()
	client := redis.NewClient(&redis.Options{Addr: "127.0.0.1:6379"})
	if e := client.Ping(ctx).Err(); e != nil {
		t.Skip("Redis is not available")
	}
	prefix := "sc:test:" + time.Now().Format("150405.000000000")
	defer func() {
		for _, pattern := range []string{prefix + "*"} {
			keys, _ := client.Keys(ctx, pattern).Result()
			if len(keys) > 0 {
				_ = client.Del(ctx, keys...).Err()
			}
		}
	}()
	recordKey := make([]byte, 32)
	_, _ = rand.Read(recordKey)
	protector, _ := NewAESGCMRecordProtector(recordKey)
	repo := &SessionRepository{Redis: client, Prefix: prefix + ":session", Protector: protector}
	expires := time.Now().Add(time.Minute)
	keys := sc.SessionKeys{KeyID: "key", SessionID: "session", Suite: sc.InternationalSuite, RequestKey: make([]byte, 32), ResponseKey: make([]byte, 32), RequestNoncePrefix: [4]byte{1, 2, 3, 4}, ResponseNoncePrefix: [4]byte{5, 6, 7, 8}, ExpiresAt: expires}
	if e := repo.SavePending(ctx, sc.PendingSession{Keys: keys, ExpiresAt: expires, InstallationPublicKey: []byte("key"), TranscriptHash: []byte("hash")}); e != nil {
		t.Fatal(e)
	}
	if e := repo.Activate(ctx, "key", "session"); e != nil {
		t.Fatal(e)
	}
	if got, e := repo.FindSession(ctx, "key", "session"); e != nil || got == nil {
		t.Fatalf("session missing: %v", e)
	}
	replay := ReplayProtector{client, prefix + ":replay"}
	if ok, e := replay.Claim(ctx, "session", sc.Request, 1, time.Minute); e != nil || !ok {
		t.Fatal("first replay claim failed")
	}
	if ok, _ := replay.Claim(ctx, "session", sc.Request, 1, time.Minute); ok {
		t.Fatal("duplicate replay accepted")
	}
	tokens := EnrollmentTokens{client, prefix + ":token"}
	token, e := tokens.Issue(ctx, "app", "HOST", time.Minute)
	if e != nil {
		t.Fatal(e)
	}
	if e = tokens.Consume(ctx, token, "app", "device", "HOST"); e != nil {
		t.Fatal(e)
	}
	if e = tokens.Consume(ctx, token, "app", "device", "HOST"); e == nil {
		t.Fatal("token consumed twice")
	}
}
