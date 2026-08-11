package redisstore

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	sc "github.com/coolxer/secure-communication-server-go"
	"github.com/redis/go-redis/v9"
)

type AESGCMRecordProtector struct{ key []byte }

func NewAESGCMRecordProtector(key []byte) (*AESGCMRecordProtector, error) {
	if len(key) != 32 {
		return nil, errors.New("session record key must contain 32 bytes")
	}
	return &AESGCMRecordProtector{append([]byte{}, key...)}, nil
}
func (p *AESGCMRecordProtector) Protect(v []byte) ([]byte, error) {
	b, e := aes.NewCipher(p.key)
	if e != nil {
		return nil, e
	}
	g, e := cipher.NewGCM(b)
	if e != nil {
		return nil, e
	}
	n := make([]byte, g.NonceSize())
	if _, e = rand.Read(n); e != nil {
		return nil, e
	}
	return g.Seal(n, n, v, []byte("SC1-REDIS-SESSION")), nil
}
func (p *AESGCMRecordProtector) Unprotect(v []byte) ([]byte, error) {
	b, e := aes.NewCipher(p.key)
	if e != nil {
		return nil, e
	}
	g, e := cipher.NewGCM(b)
	if e != nil || len(v) < g.NonceSize()+g.Overhead() {
		return nil, sc.Fail(sc.ErrKeyProviderUnavailable)
	}
	plain, e := g.Open(nil, v[:g.NonceSize()], v[g.NonceSize():], []byte("SC1-REDIS-SESSION"))
	if e != nil {
		return nil, sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	return plain, nil
}

type ReplayProtector struct {
	Redis  redis.Cmdable
	Prefix string
}

func (r ReplayProtector) Claim(ctx context.Context, s string, d sc.Direction, q uint64, ttl time.Duration) (bool, error) {
	ok, e := r.Redis.SetNX(ctx, fmt.Sprintf("%s:%s:%s:%d", r.Prefix, s, d, q), "1", ttl).Result()
	if e != nil {
		return false, sc.Wrap(sc.ErrReplayStoreUnavailable, e)
	}
	return ok, nil
}

type InstallationRegistry struct {
	Redis  redis.Cmdable
	Prefix string
}

func (r InstallationRegistry) key(a, d string) string {
	x := sha256.Sum256([]byte(a + "\n" + d))
	return r.Prefix + ":" + hex.EncodeToString(x[:])
}
func (r InstallationRegistry) Find(ctx context.Context, a, d string) ([]byte, error) {
	v, e := r.Redis.Get(ctx, r.key(a, d)).Result()
	if e == redis.Nil {
		return nil, nil
	}
	if e != nil {
		return nil, sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	b, e := base64.RawURLEncoding.DecodeString(v)
	if e != nil {
		return nil, sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	return b, nil
}
func (r InstallationRegistry) Register(ctx context.Context, a, d, _ string, k []byte) error {
	v := base64.RawURLEncoding.EncodeToString(k)
	key := r.key(a, d)
	ok, e := r.Redis.SetNX(ctx, key, v, 0).Result()
	if e != nil {
		return sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	if !ok {
		old, e := r.Redis.Get(ctx, key).Result()
		if e != nil {
			return sc.Wrap(sc.ErrKeyProviderUnavailable, e)
		}
		if old != v {
			return sc.Fail(sc.ErrHandshakeFailed)
		}
	}
	return nil
}

type EnrollmentTokens struct {
	Redis  redis.Scripter
	Prefix string
}

var consumeScript = redis.NewScript("local v=redis.call('GET',KEYS[1]); if v==ARGV[1] then redis.call('DEL',KEYS[1]); return 1 else return 0 end")

func (r EnrollmentTokens) key(t string) string {
	x := sha256.Sum256([]byte(t))
	return r.Prefix + ":" + hex.EncodeToString(x[:])
}
func (r EnrollmentTokens) Issue(ctx context.Context, a, d string, ttl time.Duration) (string, error) {
	if ttl <= 0 || ttl > time.Hour {
		return "", errors.New("enrollment token TTL must be within one hour")
	}
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		return "", sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	t := base64.RawURLEncoding.EncodeToString(b)
	client, ok := r.Redis.(interface {
		Set(context.Context, string, interface{}, time.Duration) *redis.StatusCmd
	})
	if !ok {
		return "", sc.Fail(sc.ErrKeyProviderUnavailable)
	}
	if e := client.Set(ctx, r.key(t), a+"\n"+d, ttl).Err(); e != nil {
		return "", sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	return t, nil
}
func (r EnrollmentTokens) Consume(ctx context.Context, t, a, _, d string) error {
	v, e := consumeScript.Run(ctx, r.Redis, []string{r.key(t)}, a+"\n"+d).Int()
	if e != nil {
		return sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	if v != 1 {
		return sc.Fail(sc.ErrEnrollmentRequired)
	}
	return nil
}

type SessionRepository struct {
	Redis     redis.Cmdable
	Prefix    string
	Protector sc.SessionRecordProtector
	Clock     func() time.Time
}
type record struct {
	KeyID                 string `json:"keyId"`
	SessionID             string `json:"sessionId"`
	Suite                 string `json:"suite"`
	RequestKey            string `json:"requestKey"`
	ResponseKey           string `json:"responseKey"`
	RequestPrefix         string `json:"requestPrefix"`
	ResponsePrefix        string `json:"responsePrefix"`
	ExpiresAt             int64  `json:"expiresAt"`
	Revoked               bool   `json:"revoked"`
	Active                bool   `json:"active"`
	AppID                 string `json:"appId"`
	DeviceID              string `json:"deviceId"`
	DeviceType            string `json:"deviceType"`
	InstallationPublicKey string `json:"installationPublicKey"`
	TranscriptHash        string `json:"transcriptHash"`
	RegisterInstallation  bool   `json:"registerInstallation"`
}

func (r *SessionRepository) clock() time.Time {
	if r.Clock != nil {
		return r.Clock()
	}
	return time.Now()
}
func (r *SessionRepository) key(state, k, s string) string {
	return r.Prefix + ":" + state + ":" + k + ":" + s
}
func fromPending(p sc.PendingSession, active bool) record {
	return record{p.Keys.KeyID, p.Keys.SessionID, p.Keys.Suite, sc.Encode(p.Keys.RequestKey), sc.Encode(p.Keys.ResponseKey), sc.Encode(p.Keys.RequestNoncePrefix[:]), sc.Encode(p.Keys.ResponseNoncePrefix[:]), p.ExpiresAt.UnixMilli(), p.Keys.Revoked, active, p.AppID, p.DeviceID, p.DeviceType, sc.Encode(p.InstallationPublicKey), sc.Encode(p.TranscriptHash), p.RegisterInstallation}
}
func (x record) keys() (sc.SessionKeys, error) {
	rk, e := sc.Decode(x.RequestKey, false)
	if e != nil {
		return sc.SessionKeys{}, e
	}
	sk, e := sc.Decode(x.ResponseKey, false)
	if e != nil {
		return sc.SessionKeys{}, e
	}
	rp, e := sc.Decode(x.RequestPrefix, false)
	if e != nil || len(rp) != 4 {
		return sc.SessionKeys{}, sc.Fail(sc.ErrKeyProviderUnavailable)
	}
	sp, e := sc.Decode(x.ResponsePrefix, false)
	if e != nil || len(sp) != 4 {
		return sc.SessionKeys{}, sc.Fail(sc.ErrKeyProviderUnavailable)
	}
	v := sc.SessionKeys{KeyID: x.KeyID, SessionID: x.SessionID, Suite: x.Suite, RequestKey: rk, ResponseKey: sk, ExpiresAt: time.UnixMilli(x.ExpiresAt), Revoked: x.Revoked}
	copy(v.RequestNoncePrefix[:], rp)
	copy(v.ResponseNoncePrefix[:], sp)
	return v, nil
}
func (x record) pending() (*sc.PendingSession, error) {
	k, e := x.keys()
	if e != nil {
		return nil, e
	}
	i, e := sc.Decode(x.InstallationPublicKey, false)
	if e != nil {
		return nil, e
	}
	h, e := sc.Decode(x.TranscriptHash, false)
	if e != nil {
		return nil, e
	}
	return &sc.PendingSession{Keys: k, AppID: x.AppID, DeviceID: x.DeviceID, DeviceType: x.DeviceType, InstallationPublicKey: i, TranscriptHash: h, ExpiresAt: time.UnixMilli(x.ExpiresAt), RegisterInstallation: x.RegisterInstallation}, nil
}
func (r *SessionRepository) write(ctx context.Context, key string, x record) error {
	ttl := time.Until(time.UnixMilli(x.ExpiresAt))
	if r.Clock != nil {
		ttl = time.UnixMilli(x.ExpiresAt).Sub(r.clock())
	}
	if ttl <= 0 {
		return sc.Fail(sc.ErrUnknownSession)
	}
	raw, e := json.Marshal(x)
	if e != nil {
		return sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	protected, e := r.Protector.Protect(raw)
	if e != nil {
		return e
	}
	if e = r.Redis.Set(ctx, key, base64.RawURLEncoding.EncodeToString(protected), ttl).Err(); e != nil {
		return sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	return nil
}
func (r *SessionRepository) read(ctx context.Context, key string) (*record, error) {
	v, e := r.Redis.Get(ctx, key).Result()
	if e == redis.Nil {
		return nil, nil
	}
	if e != nil {
		return nil, sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	b, e := base64.RawURLEncoding.DecodeString(v)
	if e != nil {
		return nil, sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	b, e = r.Protector.Unprotect(b)
	if e != nil {
		return nil, e
	}
	var x record
	if e = json.Unmarshal(b, &x); e != nil {
		return nil, sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	if !time.UnixMilli(x.ExpiresAt).After(r.clock()) {
		_ = r.Redis.Del(ctx, key).Err()
		return nil, nil
	}
	return &x, nil
}
func (r *SessionRepository) SavePending(ctx context.Context, p sc.PendingSession) error {
	return r.write(ctx, r.key("pending", p.Keys.KeyID, p.Keys.SessionID), fromPending(p, false))
}
func (r *SessionRepository) FindPending(ctx context.Context, k, s string) (*sc.PendingSession, error) {
	x, e := r.read(ctx, r.key("pending", k, s))
	if e != nil || x == nil {
		return nil, e
	}
	return x.pending()
}
func (r *SessionRepository) Activate(ctx context.Context, k, s string) error {
	pk := r.key("pending", k, s)
	x, e := r.read(ctx, pk)
	if e != nil {
		return e
	}
	if x == nil {
		return sc.Fail(sc.ErrHandshakeFailed)
	}
	x.Active = true
	if e = r.write(ctx, r.key("active", k, s), *x); e != nil {
		return e
	}
	if e = r.Redis.Del(ctx, pk).Err(); e != nil {
		return sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	return nil
}
func (r *SessionRepository) Remove(ctx context.Context, k, s string) error {
	if e := r.Redis.Del(ctx, r.key("pending", k, s), r.key("active", k, s)).Err(); e != nil {
		return sc.Wrap(sc.ErrKeyProviderUnavailable, e)
	}
	return nil
}
func (r *SessionRepository) FindSession(ctx context.Context, k, s string) (*sc.SessionKeys, error) {
	x, e := r.read(ctx, r.key("active", k, s))
	if e != nil || x == nil {
		return nil, e
	}
	v, e := x.keys()
	return &v, e
}
