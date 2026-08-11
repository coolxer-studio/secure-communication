package securecommunication

import (
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"fmt"
	"regexp"
	"strings"
)

type HandshakeRequest struct {
	V                        int    `json:"v"`
	Suite                    string `json:"suite"`
	AppID                    string `json:"appId"`
	DeviceID                 string `json:"deviceId"`
	DeviceType               string `json:"deviceType"`
	ClientEphemeralPublicKey string `json:"clientEphemeralPublicKey"`
	InstallationPublicKey    string `json:"installationPublicKey"`
	EnrollmentToken          string `json:"enrollmentToken,omitempty"`
	Timestamp                int64  `json:"timestamp"`
}
type HandshakeResponse struct {
	V                        int    `json:"v"`
	Suite                    string `json:"suite"`
	KID                      string `json:"kid"`
	SID                      string `json:"sid"`
	ServerIdentityPublicKey  string `json:"serverIdentityPublicKey"`
	ServerEphemeralPublicKey string `json:"serverEphemeralPublicKey"`
	CreatedAt                int64  `json:"createdAt"`
	ExpiresAt                int64  `json:"expiresAt"`
	Signature                string `json:"signature"`
}
type HandshakeFinishRequest struct {
	KID   string `json:"kid"`
	SID   string `json:"sid"`
	Proof string `json:"proof"`
}
type HandshakeFinishResponse struct {
	Active    bool  `json:"active"`
	ExpiresAt int64 `json:"expiresAt"`
}
type HandshakeService struct {
	Identity      ServerIdentityProvider
	Sessions      SessionRepository
	Installations InstallationRegistry
	Tokens        EnrollmentTokenService
	Authorizer    HandshakeAuthorizer
	Config        Config
}

var handshakeID = regexp.MustCompile(`^[A-Za-z0-9._:@/-]{1,128}$`)
var deviceTypes = map[string]bool{"H5": true, "HOST": true, "ANDROID": true, "IOS": true, "EMULATOR": true}

func (s *HandshakeService) Start(ctx context.Context, r HandshakeRequest, origin, remote string) (*HandshakeResponse, error) {
	now := s.Config.Clock()
	if r.V != 1 || r.Suite != InternationalSuite || !handshakeID.MatchString(r.AppID) || !handshakeID.MatchString(r.DeviceID) || !deviceTypes[r.DeviceType] || absMillis(now.UnixMilli()-r.Timestamp) > s.Config.ClockSkew.Milliseconds() {
		return nil, Fail(ErrHandshakeFailed)
	}
	clientEncoded, e := Decode(r.ClientEphemeralPublicKey, false)
	if e != nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	installation, e := Decode(r.InstallationPublicKey, false)
	if e != nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	peer, e := ParseP256ECDH(clientEncoded)
	if e != nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	ipk, e := x509.ParsePKIXPublicKey(installation)
	if e != nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	if k, ok := ipk.(*ecdsa.PublicKey); !ok || k.Curve.Params().Name != "P-256" {
		return nil, Fail(ErrHandshakeFailed)
	}
	registered, e := s.Installations.Find(ctx, r.AppID, r.DeviceID)
	if e != nil {
		return nil, e
	}
	if len(registered) > 0 && !hmac.Equal(registered, installation) {
		return nil, Fail(ErrHandshakeFailed)
	}
	if e = s.Authorizer.Authorize(ctx, HandshakeContext{r.AppID, r.DeviceID, r.DeviceType, origin, remote, len(registered) > 0}); e != nil {
		return nil, e
	}
	isNew := len(registered) == 0
	if isNew && r.DeviceType != "H5" {
		if strings.TrimSpace(r.EnrollmentToken) == "" {
			return nil, Fail(ErrEnrollmentRequired)
		}
		if e = s.Tokens.Consume(ctx, r.EnrollmentToken, r.AppID, r.DeviceID, r.DeviceType); e != nil {
			return nil, e
		}
	}
	ephemeral, e := ecdh.P256().GenerateKey(rand.Reader)
	if e != nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	serverEphemeral, _ := x509.MarshalPKIXPublicKey(ephemeral.PublicKey())
	serverIdentity := s.Identity.EncodedPublicKey()
	kid := s.Identity.KeyID()
	if !handshakeID.MatchString(kid) {
		return nil, Fail(ErrHandshakeFailed)
	}
	sid, e := randomID()
	if e != nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	created := now.UnixMilli()
	expires := now.Add(s.Config.SessionTTL)
	hash := TranscriptHash(r, clientEncoded, installation, serverIdentity, serverEphemeral, kid, sid, created, expires.UnixMilli())
	signature, e := s.Identity.SignTranscript(hash)
	if e != nil {
		return nil, e
	}
	keys, e := DeriveSession(kid, sid, ephemeral, peer, hash, expires)
	if e != nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	p := PendingSession{keys, r.AppID, r.DeviceID, r.DeviceType, installation, hash, expires, isNew}
	if e = s.Sessions.SavePending(ctx, p); e != nil {
		return nil, e
	}
	return &HandshakeResponse{1, InternationalSuite, kid, sid, Encode(serverIdentity), Encode(serverEphemeral), created, expires.UnixMilli(), Encode(signature)}, nil
}
func (s *HandshakeService) Finish(ctx context.Context, r HandshakeFinishRequest) (*HandshakeFinishResponse, error) {
	if !handshakeID.MatchString(r.KID) || !handshakeID.MatchString(r.SID) {
		return nil, Fail(ErrHandshakeFailed)
	}
	p, e := s.Sessions.FindPending(ctx, r.KID, r.SID)
	if e != nil {
		return nil, e
	}
	if p == nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	v, e := x509.ParsePKIXPublicKey(p.InstallationPublicKey)
	if e != nil {
		return nil, Fail(ErrHandshakeFailed)
	}
	key, ok := v.(*ecdsa.PublicKey)
	proof, e := Decode(r.Proof, false)
	if !ok || e != nil || !VerifyP1363(key, p.TranscriptHash, proof) {
		_ = s.Sessions.Remove(ctx, r.KID, r.SID)
		return nil, Fail(ErrHandshakeFailed)
	}
	if p.RegisterInstallation {
		if e = s.Installations.Register(ctx, p.AppID, p.DeviceID, p.DeviceType, p.InstallationPublicKey); e != nil {
			return nil, e
		}
	}
	if e = s.Sessions.Activate(ctx, r.KID, r.SID); e != nil {
		return nil, e
	}
	return &HandshakeFinishResponse{true, p.ExpiresAt.UnixMilli()}, nil
}
func TranscriptHash(r HandshakeRequest, client, installation, identity, ephemeral []byte, kid, sid string, created, expires int64) []byte {
	v := strings.Join([]string{"SC1-HANDSHAKE", "1", InternationalSuite, r.AppID, r.DeviceID, r.DeviceType, Encode(client), Encode(installation), Encode(identity), Encode(ephemeral), kid, sid, fmt.Sprint(created), fmt.Sprint(expires)}, "\n")
	x := sha256.Sum256([]byte(v))
	return x[:]
}
func randomID() (string, error) {
	b := make([]byte, 16)
	if _, e := rand.Read(b); e != nil {
		return "", e
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", b[:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}
func absMillis(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}
