package securecommunication

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"regexp"
	"sort"
	"strings"
	"time"
)

const (
	ProtocolVersion         = 1
	InternationalSuite      = "P256_HKDF_SHA256_AES256_GCM"
	EnvelopeMediaType       = "application/sc-envelope+json"
	ProtectedMediaType      = "application/sc-protected+json"
	HandshakeEndpoint       = "/sc/v1/handshake"
	HandshakeFinishEndpoint = "/sc/v1/handshake/finish"
	MessageEndpoint         = "/sc/v1/message"
	maxSequence             = uint64(9007199254740991)
)

type ErrorCode struct {
	HTTPStatus    int
	Code, Message string
}

var (
	ErrInvalidEnvelope        = ErrorCode{400, "SC_INVALID_ENVELOPE", "Secure envelope is invalid"}
	ErrRouteMismatch          = ErrorCode{400, "SC_ROUTE_MISMATCH", "Secure route binding does not match"}
	ErrTLSRequired            = ErrorCode{400, "SC_TLS_REQUIRED", "Secure communication requires TLS"}
	ErrUnknownSession         = ErrorCode{401, "SC_UNKNOWN_SESSION", "Secure session is unavailable"}
	ErrAuthenticationFailed   = ErrorCode{401, "SC_AUTHENTICATION_FAILED", "Secure message authentication failed"}
	ErrHandshakeFailed        = ErrorCode{401, "SC_HANDSHAKE_FAILED", "Secure handshake failed"}
	ErrEnrollmentRequired     = ErrorCode{401, "SC_ENROLLMENT_REQUIRED", "Installation enrollment is required"}
	ErrRequestExpired         = ErrorCode{408, "SC_REQUEST_EXPIRED", "Secure request is outside the accepted time window"}
	ErrReplayDetected         = ErrorCode{409, "SC_REPLAY_DETECTED", "Secure request was already accepted"}
	ErrPayloadTooLarge        = ErrorCode{413, "SC_PAYLOAD_TOO_LARGE", "Secure payload is too large"}
	ErrUnsupportedVersion     = ErrorCode{426, "SC_UNSUPPORTED_VERSION", "Secure protocol version is unsupported"}
	ErrUnsupportedSuite       = ErrorCode{426, "SC_UNSUPPORTED_SUITE", "Secure algorithm suite is unsupported"}
	ErrKeyProviderUnavailable = ErrorCode{503, "SC_KEY_PROVIDER_UNAVAILABLE", "Secure key provider is unavailable"}
	ErrReplayStoreUnavailable = ErrorCode{503, "SC_REPLAY_STORE_UNAVAILABLE", "Secure replay store is unavailable"}
	ErrInternal               = ErrorCode{500, "SC_INTERNAL_ERROR", "Secure communication failed"}
)

type ProtocolError struct {
	ErrorCode
	Cause error
}

func (e *ProtocolError) Error() string   { return e.Code }
func (e *ProtocolError) Unwrap() error   { return e.Cause }
func Fail(code ErrorCode) *ProtocolError { return &ProtocolError{ErrorCode: code} }
func Wrap(code ErrorCode, err error) *ProtocolError {
	return &ProtocolError{ErrorCode: code, Cause: err}
}

type Direction string

const (
	Request  Direction = "request"
	Response Direction = "response"
)

type Envelope struct {
	V           int    `json:"v"`
	Suite       string `json:"suite"`
	KID         string `json:"kid"`
	SID         string `json:"sid"`
	TS          int64  `json:"ts"`
	Seq         uint64 `json:"seq"`
	RID         string `json:"rid"`
	Method      string `json:"m"`
	Path        string `json:"p"`
	ContentType string `json:"cty"`
	Status      int    `json:"st"`
	Nonce       string `json:"nonce"`
	Ciphertext  string `json:"ct"`
}
type ProtectedPayload struct {
	Method      string            `json:"method"`
	Path        string            `json:"path"`
	ContentType string            `json:"contentType"`
	Headers     map[string]string `json:"headers"`
	Body        string            `json:"body"`
}
type ProtectedResponse struct {
	ContentType string `json:"contentType"`
	Body        string `json:"body"`
}
type SessionKeys struct {
	KeyID, SessionID, Suite                 string
	RequestKey, ResponseKey                 []byte
	RequestNoncePrefix, ResponseNoncePrefix [4]byte
	ExpiresAt                               time.Time
	Revoked                                 bool
}
type PendingSession struct {
	Keys                                  SessionKeys
	AppID, DeviceID, DeviceType           string
	InstallationPublicKey, TranscriptHash []byte
	ExpiresAt                             time.Time
	RegisterInstallation                  bool
}

type AlgorithmProvider interface {
	Suite() string
	Seal(key, nonce, aad, plaintext []byte) ([]byte, error)
	Open(key, nonce, aad, ciphertext []byte) ([]byte, error)
}
type KeyProvider interface {
	FindSession(context.Context, string, string) (*SessionKeys, error)
}
type SessionRepository interface {
	KeyProvider
	SavePending(context.Context, PendingSession) error
	FindPending(context.Context, string, string) (*PendingSession, error)
	Activate(context.Context, string, string) error
	Remove(context.Context, string, string) error
}
type ReplayProtector interface {
	Claim(context.Context, string, Direction, uint64, time.Duration) (bool, error)
}
type InstallationRegistry interface {
	Find(context.Context, string, string) ([]byte, error)
	Register(context.Context, string, string, string, []byte) error
}
type EnrollmentTokenService interface {
	Issue(context.Context, string, string, time.Duration) (string, error)
	Consume(context.Context, string, string, string, string) error
}
type HandshakeContext struct {
	AppID, DeviceID, DeviceType, Origin, RemoteAddress string
	RegisteredInstallation                             bool
}
type HandshakeAuthorizer interface {
	Authorize(context.Context, HandshakeContext) error
}
type HandshakeAuthorizerFunc func(context.Context, HandshakeContext) error

func (f HandshakeAuthorizerFunc) Authorize(c context.Context, h HandshakeContext) error {
	return f(c, h)
}

type LogicalRouteAuthorizer interface {
	IsAllowed(method, path string) bool
}
type LogicalRouteAuthorizerFunc func(string, string) bool

func (f LogicalRouteAuthorizerFunc) IsAllowed(m, p string) bool { return f(m, p) }

type ServerIdentityProvider interface {
	KeyID() string
	SignTranscript([]byte) ([]byte, error)
	EncodedPublicKey() []byte
}
type SessionRecordProtector interface {
	Protect([]byte) ([]byte, error)
	Unprotect([]byte) ([]byte, error)
}
type SecurityPolicy interface {
	AllowsSuite(string) bool
	RequiresTLS() bool
	ClockSkewDuration() time.Duration
	ReplayTTLDuration() time.Duration
	EnvelopeLimit() int
	PlaintextLimit() int
	BodyLimit() int
}

type Config struct {
	Enabled, V1Enabled, RequireTLS                    bool
	Prefix                                            string
	AllowedSuites                                     map[string]bool
	ClockSkew, ReplayTTL, SessionTTL                  time.Duration
	MaxEnvelopeBytes, MaxPlaintextBytes, MaxBodyBytes int
	Clock                                             func() time.Time
}

func DefaultConfig() Config {
	return Config{V1Enabled: true, RequireTLS: true, Prefix: MessageEndpoint, AllowedSuites: map[string]bool{InternationalSuite: true}, ClockSkew: 5 * time.Minute, ReplayTTL: 10 * time.Minute, SessionTTL: 10 * time.Minute, MaxEnvelopeBytes: 1_400_000, MaxPlaintextBytes: 1_048_576, MaxBodyBytes: 1_048_576, Clock: time.Now}
}
func (c Config) Validate() error {
	if c.Prefix == "" || c.Prefix[0] != '/' || c.Clock == nil || c.ClockSkew < 0 || c.ReplayTTL <= 0 || c.SessionTTL <= 0 || c.MaxEnvelopeBytes < 256 || c.MaxPlaintextBytes < 0 || c.MaxBodyBytes < 0 || c.MaxBodyBytes > c.MaxPlaintextBytes {
		return errors.New("invalid secure communication config")
	}
	return nil
}
func (c Config) AllowsSuite(s string) bool        { return c.AllowedSuites[s] }
func (c Config) RequiresTLS() bool                { return c.RequireTLS }
func (c Config) ClockSkewDuration() time.Duration { return c.ClockSkew }
func (c Config) ReplayTTLDuration() time.Duration { return c.ReplayTTL }
func (c Config) EnvelopeLimit() int               { return c.MaxEnvelopeBytes }
func (c Config) PlaintextLimit() int              { return c.MaxPlaintextBytes }
func (c Config) BodyLimit() int                   { return c.MaxBodyBytes }

type AESGCMAlgorithm struct{}

func (AESGCMAlgorithm) Suite() string { return InternationalSuite }
func (AESGCMAlgorithm) Seal(k, n, a, p []byte) ([]byte, error) {
	b, e := aes.NewCipher(k)
	if e != nil {
		return nil, e
	}
	g, e := cipher.NewGCM(b)
	if e != nil {
		return nil, e
	}
	return g.Seal(nil, n, p, a), nil
}
func (AESGCMAlgorithm) Open(k, n, a, c []byte) ([]byte, error) {
	b, e := aes.NewCipher(k)
	if e != nil {
		return nil, e
	}
	g, e := cipher.NewGCM(b)
	if e != nil {
		return nil, e
	}
	p, e := g.Open(nil, n, c, a)
	if e != nil {
		return nil, Wrap(ErrAuthenticationFailed, e)
	}
	return p, nil
}

type P256Identity struct {
	ID         string
	PrivateKey *ecdsa.PrivateKey
}

func (p *P256Identity) KeyID() string { return p.ID }
func (p *P256Identity) EncodedPublicKey() []byte {
	b, _ := x509.MarshalPKIXPublicKey(&p.PrivateKey.PublicKey)
	return b
}
func (p *P256Identity) SignTranscript(h []byte) ([]byte, error) {
	d := sha256.Sum256(h)
	r, s, e := ecdsa.Sign(rand.Reader, p.PrivateKey, d[:])
	if e != nil {
		return nil, Wrap(ErrKeyProviderUnavailable, e)
	}
	out := make([]byte, 64)
	r.FillBytes(out[:32])
	s.FillBytes(out[32:])
	return out, nil
}

func Encode(v []byte) string { return base64.RawURLEncoding.EncodeToString(v) }

var b64Pattern = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)

func Decode(v string, allowEmpty bool) ([]byte, error) {
	if v == "" && allowEmpty {
		return []byte{}, nil
	}
	if !b64Pattern.MatchString(v) {
		return nil, Fail(ErrInvalidEnvelope)
	}
	b, e := base64.RawURLEncoding.DecodeString(v)
	if e != nil {
		return nil, Wrap(ErrInvalidEnvelope, e)
	}
	return b, nil
}
func StrictJSON(data []byte, dst any) error {
	if err := rejectDuplicateKeys(data); err != nil {
		return err
	}
	d := json.NewDecoder(strings.NewReader(string(data)))
	d.DisallowUnknownFields()
	if e := d.Decode(dst); e != nil {
		return Wrap(ErrInvalidEnvelope, e)
	}
	if e := d.Decode(&struct{}{}); e != io.EOF {
		return Fail(ErrInvalidEnvelope)
	}
	return nil
}
func StrictObjectJSON(data []byte, dst any, required, optional []string) error {
	if err := rejectDuplicateKeys(data); err != nil {
		return err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil || raw == nil {
		return Fail(ErrInvalidEnvelope)
	}
	allowed := make(map[string]bool, len(required)+len(optional))
	for _, name := range required {
		allowed[name] = true
		if _, ok := raw[name]; !ok {
			return Fail(ErrInvalidEnvelope)
		}
	}
	for _, name := range optional {
		allowed[name] = true
	}
	for name := range raw {
		if !allowed[name] {
			return Fail(ErrInvalidEnvelope)
		}
	}
	return StrictJSON(data, dst)
}
func rejectDuplicateKeys(data []byte) error {
	d := json.NewDecoder(bytes.NewReader(data))
	var walk func() error
	walk = func() error {
		token, err := d.Token()
		if err != nil {
			return Wrap(ErrInvalidEnvelope, err)
		}
		delim, ok := token.(json.Delim)
		if !ok {
			return nil
		}
		switch delim {
		case '{':
			seen := map[string]bool{}
			for d.More() {
				keyToken, err := d.Token()
				if err != nil {
					return Wrap(ErrInvalidEnvelope, err)
				}
				key, ok := keyToken.(string)
				if !ok || seen[key] {
					return Fail(ErrInvalidEnvelope)
				}
				seen[key] = true
				if err := walk(); err != nil {
					return err
				}
			}
			end, err := d.Token()
			if err != nil || end != json.Delim('}') {
				return Fail(ErrInvalidEnvelope)
			}
		case '[':
			for d.More() {
				if err := walk(); err != nil {
					return err
				}
			}
			end, err := d.Token()
			if err != nil || end != json.Delim(']') {
				return Fail(ErrInvalidEnvelope)
			}
		default:
			return Fail(ErrInvalidEnvelope)
		}
		return nil
	}
	if err := walk(); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return Fail(ErrInvalidEnvelope)
	}
	return nil
}
func Nonce(prefix [4]byte, seq uint64) []byte {
	b := make([]byte, 12)
	copy(b, prefix[:])
	binary.BigEndian.PutUint64(b[4:], seq)
	return b
}
func AAD(dir Direction, e Envelope) []byte {
	p := []string{"SC1", string(dir), e.Suite, e.KID, e.SID, fmt.Sprint(e.TS), fmt.Sprint(e.Seq), e.RID, e.Method, e.Path, e.ContentType}
	if dir == Response {
		p = append(p, fmt.Sprint(e.Status))
	}
	return []byte(strings.Join(p, "\n"))
}
func DeriveSession(keyID, sid string, private *ecdh.PrivateKey, peer *ecdh.PublicKey, hash []byte, expiry time.Time) (SessionKeys, error) {
	secret, e := private.ECDH(peer)
	if e != nil {
		return SessionKeys{}, e
	}
	m := hkdf(secret, hash, []byte("SC1/session/"+InternationalSuite+"/"+sid), 72)
	defer func() {
		for i := range secret {
			secret[i] = 0
		}
		for i := range m {
			m[i] = 0
		}
	}()
	s := SessionKeys{KeyID: keyID, SessionID: sid, Suite: InternationalSuite, RequestKey: append([]byte{}, m[:32]...), ResponseKey: append([]byte{}, m[32:64]...), ExpiresAt: expiry}
	copy(s.RequestNoncePrefix[:], m[64:68])
	copy(s.ResponseNoncePrefix[:], m[68:72])
	return s, nil
}
func hkdf(input, salt, info []byte, n int) []byte {
	x := hmac.New(sha256.New, salt)
	x.Write(input)
	prk := x.Sum(nil)
	var result, prev []byte
	for i := byte(1); len(result) < n; i++ {
		h := hmac.New(sha256.New, prk)
		h.Write(prev)
		h.Write(info)
		h.Write([]byte{i})
		prev = h.Sum(nil)
		result = append(result, prev...)
	}
	return result[:n]
}
func ParseP256ECDH(encoded []byte) (*ecdh.PublicKey, error) {
	v, e := x509.ParsePKIXPublicKey(encoded)
	if e != nil {
		return nil, e
	}
	switch k := v.(type) {
	case *ecdsa.PublicKey:
		return k.ECDH()
	case *ecdh.PublicKey:
		if k.Curve() == ecdh.P256() {
			return k, nil
		}
	}
	return nil, errors.New("not P-256")
}
func VerifyP1363(key *ecdsa.PublicKey, hash, sig []byte) bool {
	if len(sig) != 64 {
		return false
	}
	d := sha256.Sum256(hash)
	return ecdsa.Verify(key, d[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:]))
}
func NormalizeContentType(v string) (string, error) {
	if v == "" {
		return "application/octet-stream", nil
	}
	v = strings.ToLower(strings.TrimSpace(strings.SplitN(v, ";", 2)[0]))
	p := strings.Split(v, "/")
	if len(p) != 2 || p[0] == "" || p[1] == "" || strings.ContainsAny(v, " \r\n") {
		return "", Fail(ErrInvalidEnvelope)
	}
	return v, nil
}
func NormalizePath(v string) (string, error) {
	if !strings.HasPrefix(v, "/") || strings.ContainsAny(v, "\r\n# ") || strings.Contains(v, "://") || strings.Count(v, "?") > 1 {
		return "", Fail(ErrInvalidEnvelope)
	}
	parts := strings.SplitN(v, "?", 2)
	path, err := uppercasePercentHex(parts[0])
	if err != nil {
		return "", err
	}
	var pairs []string
	if len(parts) == 2 {
		for _, pair := range strings.Split(parts[1], "&") {
			if pair == "" {
				continue
			}
			normalized, err := uppercasePercentHex(pair)
			if err != nil {
				return "", err
			}
			pairs = append(pairs, normalized)
		}
	}
	sort.Slice(pairs, func(i, j int) bool {
		in, iv := queryParts(pairs[i])
		jn, jv := queryParts(pairs[j])
		if in == jn {
			return iv < jv
		}
		return in < jn
	})
	if len(pairs) > 0 {
		path += "?" + strings.Join(pairs, "&")
	}
	return path, nil
}
func uppercasePercentHex(v string) (string, error) {
	b := []byte(v)
	for i := 0; i < len(b); i++ {
		if b[i] == '%' {
			if i+2 >= len(b) || !isHex(b[i+1]) || !isHex(b[i+2]) {
				return "", Fail(ErrInvalidEnvelope)
			}
			b[i+1] = byte(strings.ToUpper(string(b[i+1]))[0])
			b[i+2] = byte(strings.ToUpper(string(b[i+2]))[0])
			i += 2
		}
	}
	return string(b), nil
}
func isHex(v byte) bool { return v >= '0' && v <= '9' || v >= 'a' && v <= 'f' || v >= 'A' && v <= 'F' }
func queryParts(v string) (string, string) {
	if i := strings.IndexByte(v, '='); i >= 0 {
		return v[:i], v[i+1:]
	}
	return v, ""
}
func ValidateProtected(p ProtectedPayload, maxBody int) ([]byte, error) {
	if !regexp.MustCompile(`^[A-Z]{3,16}$`).MatchString(p.Method) {
		return nil, Fail(ErrInvalidEnvelope)
	}
	n, e := NormalizePath(p.Path)
	if e != nil || n != p.Path {
		return nil, Fail(ErrInvalidEnvelope)
	}
	ct, e := NormalizeContentType(p.ContentType)
	if e != nil || ct != p.ContentType {
		return nil, Fail(ErrInvalidEnvelope)
	}
	if len(p.Headers) > 32 {
		return nil, Fail(ErrInvalidEnvelope)
	}
	hn := regexp.MustCompile(`^[a-z0-9-]{1,64}$`)
	for k, v := range p.Headers {
		if !hn.MatchString(k) || strings.ContainsAny(v, "\r\n") || len([]byte(v)) > 8192 {
			return nil, Fail(ErrInvalidEnvelope)
		}
	}
	body, e := Decode(p.Body, true)
	if e != nil {
		return nil, e
	}
	if len(body) > maxBody {
		return nil, Fail(ErrPayloadTooLarge)
	}
	return body, nil
}
