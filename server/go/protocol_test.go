package securecommunication

import (
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
	"time"
)

type testVector struct {
	Direction, KeyHex, NoncePrefixHex, Method, Path, ContentType, KID, SID, RequestID, NonceBase64URL, AADUTF8, PlaintextUTF8, CombinedCiphertextBase64URL string
	Timestamp                                                                                                                                              int64
	Sequence                                                                                                                                               uint64
	LogicalStatus                                                                                                                                          int
}

func TestDefaultConfigAllowsHTTPTransport(t *testing.T) {
	if DefaultConfig().RequireTLS {
		t.Fatal("default config must allow both HTTP and HTTPS")
	}
}

func TestAESGCMVectors(t *testing.T) {
	for _, name := range []string{"aes-256-gcm-request.json", "aes-256-gcm-response.json"} {
		data, e := os.ReadFile("../../protocol/test-vectors/" + name)
		if e != nil {
			t.Fatal(e)
		}
		var v testVector
		if e = json.Unmarshal(data, &v); e != nil {
			t.Fatal(e)
		}
		key, _ := hex.DecodeString(v.KeyHex)
		pb, _ := hex.DecodeString(v.NoncePrefixHex)
		var prefix [4]byte
		copy(prefix[:], pb)
		nonce := Nonce(prefix, v.Sequence)
		if Encode(nonce) != v.NonceBase64URL {
			t.Fatal("nonce mismatch")
		}
		env := Envelope{V: 1, Suite: InternationalSuite, KID: v.KID, SID: v.SID, TS: v.Timestamp, Seq: v.Sequence, RID: v.RequestID, Method: v.Method, Path: v.Path, ContentType: v.ContentType, Status: v.LogicalStatus}
		aad := AAD(Direction(v.Direction), env)
		if string(aad) != v.AADUTF8 {
			t.Fatalf("AAD mismatch: %s", aad)
		}
		sealed, e := (AESGCMAlgorithm{}).Seal(key, nonce, aad, []byte(v.PlaintextUTF8))
		if e != nil || Encode(sealed) != v.CombinedCiphertextBase64URL {
			t.Fatalf("cipher mismatch %v", e)
		}
		opened, e := (AESGCMAlgorithm{}).Open(key, nonce, aad, sealed)
		if e != nil || string(opened) != v.PlaintextUTF8 {
			t.Fatal("open mismatch")
		}
	}
}
func TestStrictObjectRejectsDuplicateAndMissingFields(t *testing.T) {
	var target map[string]any
	if err := StrictObjectJSON([]byte(`{"v":1,"v":2}`), &target, []string{"v"}, nil); err == nil {
		t.Fatal("duplicate field accepted")
	}
	if err := StrictObjectJSON([]byte(`{}`), &target, []string{"v"}, nil); err == nil {
		t.Fatal("missing field accepted")
	}
}

type testTokens struct{}

func (testTokens) Issue(context.Context, string, string, time.Duration) (string, error) {
	return "token", nil
}
func (testTokens) Consume(context.Context, string, string, string, string) error { return nil }
func TestHandshakeAndMessageRoundTrip(t *testing.T) {
	ctx := context.Background()
	now := time.UnixMilli(1785283200000)
	clock := func() time.Time { return now }
	serverKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	repo := NewMemorySessionRepository(clock)
	installations := NewMemoryInstallationRegistry()
	cfg := DefaultConfig()
	cfg.Enabled = true
	cfg.RequireTLS = false
	cfg.Clock = clock
	service := HandshakeService{Identity: &P256Identity{ID: "server-key", PrivateKey: serverKey}, Sessions: repo, Installations: installations, Tokens: testTokens{}, Authorizer: HandshakeAuthorizerFunc(func(context.Context, HandshakeContext) error { return nil }), Config: cfg}
	clientEphemeral, _ := ecdh.P256().GenerateKey(rand.Reader)
	clientSPKI, _ := x509.MarshalPKIXPublicKey(clientEphemeral.PublicKey())
	installationKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	installationSPKI, _ := x509.MarshalPKIXPublicKey(&installationKey.PublicKey)
	request := HandshakeRequest{V: 1, Suite: InternationalSuite, AppID: "demo", DeviceID: "device", DeviceType: "H5", ClientEphemeralPublicKey: Encode(clientSPKI), InstallationPublicKey: Encode(installationSPKI), Timestamp: now.UnixMilli()}
	start, e := service.Start(ctx, request, "https://example.test", "127.0.0.1")
	if e != nil {
		t.Fatal(e)
	}
	pending, _ := repo.FindPending(ctx, start.KID, start.SID)
	digest := sha256.Sum256(pending.TranscriptHash)
	r, s, e := ecdsa.Sign(rand.Reader, installationKey, digest[:])
	if e != nil {
		t.Fatal(e)
	}
	proof := make([]byte, 64)
	r.FillBytes(proof[:32])
	s.FillBytes(proof[32:])
	if _, e = service.Finish(ctx, HandshakeFinishRequest{start.KID, start.SID, Encode(proof)}); e != nil {
		t.Fatal(e)
	}
	session, _ := repo.FindSession(ctx, start.KID, start.SID)
	if session == nil {
		t.Fatal("session not active")
	}
	replay := NewMemoryReplayProtector(clock)
	messages, _ := NewMessageService(repo, replay, cfg, AESGCMAlgorithm{})
	payload, _ := json.Marshal(ProtectedPayload{"POST", "/v1/ping", "application/json", map[string]string{"scid": "abc"}, Encode([]byte("hello"))})
	env := Envelope{V: 1, Suite: InternationalSuite, KID: session.KeyID, SID: session.SessionID, TS: now.UnixMilli(), Seq: 1, RID: "request-1", Method: "POST", Path: MessageEndpoint, ContentType: ProtectedMediaType}
	nonce := Nonce(session.RequestNoncePrefix, 1)
	env.Nonce = Encode(nonce)
	ciphertext, _ := (AESGCMAlgorithm{}).Seal(session.RequestKey, nonce, AAD(Request, env), payload)
	env.Ciphertext = Encode(ciphertext)
	wire, _ := json.Marshal(env)
	opened, e := messages.Open(ctx, wire)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = messages.Open(ctx, wire); e == nil {
		t.Fatal("replay accepted")
	}
	response, _ := json.Marshal(ProtectedResponse{"text/plain", Encode([]byte("ok"))})
	if _, e = messages.Seal(opened, response, "text/plain", 201); e != nil {
		t.Fatal(e)
	}
}
