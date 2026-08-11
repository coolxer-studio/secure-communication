package securecommunication

import (
	"context"
	"crypto/hmac"
	"sync"
	"time"
)

type MemoryKeyProvider struct {
	mu       sync.RWMutex
	sessions map[string]SessionKeys
}

func NewMemoryKeyProvider() *MemoryKeyProvider {
	return &MemoryKeyProvider{sessions: map[string]SessionKeys{}}
}
func (p *MemoryKeyProvider) Put(s SessionKeys) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.sessions[sessionKey(s.KeyID, s.SessionID)] = cloneKeys(s)
}
func (p *MemoryKeyProvider) Revoke(k, s string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.sessions, sessionKey(k, s))
}
func (p *MemoryKeyProvider) FindSession(_ context.Context, k, s string) (*SessionKeys, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	v, ok := p.sessions[sessionKey(k, s)]
	if !ok {
		return nil, nil
	}
	x := cloneKeys(v)
	return &x, nil
}

type MemorySessionRepository struct {
	mu      sync.Mutex
	pending map[string]PendingSession
	active  map[string]SessionKeys
	clock   func() time.Time
}

func NewMemorySessionRepository(clock func() time.Time) *MemorySessionRepository {
	if clock == nil {
		clock = time.Now
	}
	return &MemorySessionRepository{pending: map[string]PendingSession{}, active: map[string]SessionKeys{}, clock: clock}
}
func sessionKey(k, s string) string { return k + "\x00" + s }
func (r *MemorySessionRepository) SavePending(_ context.Context, p PendingSession) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.pending[sessionKey(p.Keys.KeyID, p.Keys.SessionID)] = clonePending(p)
	return nil
}
func (r *MemorySessionRepository) FindPending(_ context.Context, k, s string) (*PendingSession, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	key := sessionKey(k, s)
	p, ok := r.pending[key]
	if ok && !p.ExpiresAt.After(r.clock()) {
		delete(r.pending, key)
		ok = false
	}
	if !ok {
		return nil, nil
	}
	x := clonePending(p)
	return &x, nil
}
func (r *MemorySessionRepository) Activate(_ context.Context, k, s string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	key := sessionKey(k, s)
	p, ok := r.pending[key]
	delete(r.pending, key)
	if ok && p.ExpiresAt.After(r.clock()) {
		r.active[key] = cloneKeys(p.Keys)
	}
	return nil
}
func (r *MemorySessionRepository) Remove(_ context.Context, k, s string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	key := sessionKey(k, s)
	delete(r.pending, key)
	delete(r.active, key)
	return nil
}
func (r *MemorySessionRepository) FindSession(_ context.Context, k, s string) (*SessionKeys, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	key := sessionKey(k, s)
	v, ok := r.active[key]
	if ok && !v.ExpiresAt.After(r.clock()) {
		delete(r.active, key)
		ok = false
	}
	if !ok {
		return nil, nil
	}
	x := cloneKeys(v)
	return &x, nil
}
func cloneKeys(s SessionKeys) SessionKeys {
	s.RequestKey = append([]byte{}, s.RequestKey...)
	s.ResponseKey = append([]byte{}, s.ResponseKey...)
	return s
}
func clonePending(p PendingSession) PendingSession {
	p.Keys = cloneKeys(p.Keys)
	p.InstallationPublicKey = append([]byte{}, p.InstallationPublicKey...)
	p.TranscriptHash = append([]byte{}, p.TranscriptHash...)
	return p
}

type MemoryReplayProtector struct {
	mu       sync.Mutex
	accepted map[string]time.Time
	clock    func() time.Time
}

func NewMemoryReplayProtector(clock func() time.Time) *MemoryReplayProtector {
	if clock == nil {
		clock = time.Now
	}
	return &MemoryReplayProtector{accepted: map[string]time.Time{}, clock: clock}
}
func (r *MemoryReplayProtector) Claim(_ context.Context, s string, d Direction, q uint64, ttl time.Duration) (bool, error) {
	if s == "" || q < 1 || ttl <= 0 {
		return false, Fail(ErrInternal)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.clock()
	k := s + "\x00" + string(d) + "\x00" + fmtUint(q)
	if x, ok := r.accepted[k]; ok && x.After(now) {
		return false, nil
	}
	r.accepted[k] = now.Add(ttl)
	if len(r.accepted)&0x3ff == 0 {
		for key, expiry := range r.accepted {
			if !expiry.After(now) {
				delete(r.accepted, key)
			}
		}
	}
	return true, nil
}
func fmtUint(v uint64) string {
	const digits = "0123456789"
	if v == 0 {
		return "0"
	}
	b := make([]byte, 0, 20)
	for v > 0 {
		b = append(b, digits[v%10])
		v /= 10
	}
	for i, j := 0, len(b)-1; i < j; i, j = i+1, j-1 {
		b[i], b[j] = b[j], b[i]
	}
	return string(b)
}

type MemoryInstallationRegistry struct {
	mu     sync.Mutex
	values map[string][]byte
}

func NewMemoryInstallationRegistry() *MemoryInstallationRegistry {
	return &MemoryInstallationRegistry{values: map[string][]byte{}}
}
func (r *MemoryInstallationRegistry) Find(_ context.Context, a, d string) ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]byte{}, r.values[a+"\x00"+d]...), nil
}
func (r *MemoryInstallationRegistry) Register(_ context.Context, a, d, _ string, k []byte) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	id := a + "\x00" + d
	if old := r.values[id]; old != nil && !hmac.Equal(old, k) {
		return Fail(ErrHandshakeFailed)
	}
	r.values[id] = append([]byte{}, k...)
	return nil
}

type RejectingEnrollmentTokens struct{}

func (RejectingEnrollmentTokens) Issue(context.Context, string, string, time.Duration) (string, error) {
	return "", Fail(ErrEnrollmentRequired)
}
func (RejectingEnrollmentTokens) Consume(context.Context, string, string, string, string) error {
	return Fail(ErrEnrollmentRequired)
}

type RejectingHandshakeAuthorizer struct{}

func (RejectingHandshakeAuthorizer) Authorize(context.Context, HandshakeContext) error {
	return Fail(ErrHandshakeFailed)
}

type RejectingRoutes struct{}

func (RejectingRoutes) IsAllowed(string, string) bool { return false }
