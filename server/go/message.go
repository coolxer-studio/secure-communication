package securecommunication

import (
	"context"
	"crypto/hmac"
	"encoding/json"
	"errors"
	"regexp"
	"time"
)

type OpenedRequest struct {
	Envelope  Envelope
	Session   SessionKeys
	Plaintext []byte
}
type MessageService struct {
	Keys       KeyProvider
	Replay     ReplayProtector
	Config     Config
	Algorithms map[string]AlgorithmProvider
}

func NewMessageService(keys KeyProvider, replay ReplayProtector, c Config, algorithms ...AlgorithmProvider) (*MessageService, error) {
	if err := c.Validate(); err != nil {
		return nil, err
	}
	m := &MessageService{Keys: keys, Replay: replay, Config: c, Algorithms: map[string]AlgorithmProvider{}}
	for _, a := range algorithms {
		if _, ok := m.Algorithms[a.Suite()]; ok {
			return nil, Fail(ErrInternal)
		}
		m.Algorithms[a.Suite()] = a
	}
	if len(m.Algorithms) == 0 {
		m.Algorithms[InternationalSuite] = AESGCMAlgorithm{}
	}
	return m, nil
}
func (m *MessageService) Open(ctx context.Context, data []byte) (*OpenedRequest, error) {
	if len(data) > m.Config.MaxEnvelopeBytes {
		return nil, Fail(ErrPayloadTooLarge)
	}
	var e Envelope
	if x := StrictObjectJSON(data, &e, []string{"v", "suite", "kid", "sid", "ts", "seq", "rid", "m", "p", "cty", "st", "nonce", "ct"}, nil); x != nil {
		return nil, x
	}
	if e.V != 1 {
		return nil, Fail(ErrUnsupportedVersion)
	}
	if !m.Config.AllowedSuites[e.Suite] {
		return nil, Fail(ErrUnsupportedSuite)
	}
	id := regexp.MustCompile(`^[\x21-\x7e]{1,128}$`)
	if !id.MatchString(e.KID) || !id.MatchString(e.SID) || !id.MatchString(e.RID) || e.Seq < 1 || e.Seq > maxSequence || e.Status != 0 {
		return nil, Fail(ErrInvalidEnvelope)
	}
	if e.Method != "POST" || e.Path != MessageEndpoint || e.ContentType != ProtectedMediaType {
		return nil, Fail(ErrRouteMismatch)
	}
	now := m.Config.Clock()
	ts := timeFromMillis(e.TS)
	if ts.Before(now.Add(-m.Config.ClockSkew)) || ts.After(now.Add(m.Config.ClockSkew)) {
		return nil, Fail(ErrRequestExpired)
	}
	s, err := m.Keys.FindSession(ctx, e.KID, e.SID)
	if err != nil {
		return nil, err
	}
	if s == nil || s.Revoked || !s.ExpiresAt.After(now) || s.Suite != e.Suite {
		return nil, Fail(ErrUnknownSession)
	}
	nonce, err := Decode(e.Nonce, false)
	if err != nil || len(nonce) != 12 || !hmac.Equal(nonce, Nonce(s.RequestNoncePrefix, e.Seq)) {
		return nil, Fail(ErrInvalidEnvelope)
	}
	ct, err := Decode(e.Ciphertext, false)
	if err != nil {
		return nil, err
	}
	if len(ct) < 16 || len(ct) > m.Config.MaxPlaintextBytes+16 {
		return nil, Fail(ErrPayloadTooLarge)
	}
	a := m.Algorithms[e.Suite]
	if a == nil {
		return nil, Fail(ErrUnsupportedSuite)
	}
	plain, err := a.Open(s.RequestKey, nonce, AAD(Request, e), ct)
	if err != nil {
		return nil, asProtocol(err, ErrAuthenticationFailed)
	}
	if len(plain) > m.Config.MaxPlaintextBytes {
		return nil, Fail(ErrPayloadTooLarge)
	}
	claimed, err := m.Replay.Claim(ctx, e.SID, Request, e.Seq, m.Config.ReplayTTL)
	if err != nil {
		return nil, err
	}
	if !claimed {
		return nil, Fail(ErrReplayDetected)
	}
	return &OpenedRequest{e, cloneKeys(*s), plain}, nil
}
func (m *MessageService) Seal(open *OpenedRequest, plain []byte, contentType string, status int) ([]byte, error) {
	if len(plain) > m.Config.MaxPlaintextBytes {
		return nil, Fail(ErrPayloadTooLarge)
	}
	if status < 100 || status > 599 {
		return nil, Fail(ErrInternal)
	}
	if _, e := NormalizeContentType(contentType); e != nil {
		return nil, e
	}
	s := open.Session
	e := Envelope{V: 1, Suite: s.Suite, KID: s.KeyID, SID: s.SessionID, TS: m.Config.Clock().UnixMilli(), Seq: open.Envelope.Seq, RID: open.Envelope.RID, Method: "POST", Path: MessageEndpoint, ContentType: ProtectedMediaType, Status: status}
	nonce := Nonce(s.ResponseNoncePrefix, e.Seq)
	e.Nonce = Encode(nonce)
	ct, err := m.Algorithms[s.Suite].Seal(s.ResponseKey, nonce, AAD(Response, e), plain)
	if err != nil {
		return nil, asProtocol(err, ErrInternal)
	}
	e.Ciphertext = Encode(ct)
	return json.Marshal(e)
}
func timeFromMillis(v int64) time.Time { return time.Unix(0, v*int64(time.Millisecond)) }
func asProtocol(err error, code ErrorCode) error {
	var p *ProtocolError
	if errors.As(err, &p) {
		return p
	}
	return Wrap(code, err)
}
