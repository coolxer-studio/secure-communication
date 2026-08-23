package securecommunication

import (
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

type vector struct {
	Direction                   string `json:"direction"`
	KeyHex                      string `json:"keyHex"`
	NoncePrefixHex              string `json:"noncePrefixHex"`
	Method                      string `json:"method"`
	Path                        string `json:"path"`
	ContentType                 string `json:"contentType"`
	KID                         string `json:"kid"`
	SID                         string `json:"sid"`
	Timestamp                   int64  `json:"timestamp"`
	Sequence                    uint64 `json:"sequence"`
	RequestID                   string `json:"requestId"`
	LogicalStatus               int    `json:"logicalStatus"`
	NonceBase64URL              string `json:"nonceBase64Url"`
	AADUTF8                     string `json:"aadUtf8"`
	PlaintextUTF8               string `json:"plaintextUtf8"`
	CombinedCiphertextBase64URL string `json:"combinedCiphertextBase64Url"`
}

type blockingIdentityStore struct {
	mu      sync.Mutex
	calls   int
	release <-chan struct{}
}

func (s *blockingIdentityStore) LoadOrCreate(string) (InstallationIdentity, error) {
	s.mu.Lock()
	s.calls++
	s.mu.Unlock()
	<-s.release
	return nil, os.ErrPermission
}

func TestConcurrentInitializationIsSharedAndOneWaiterMayCancel(t *testing.T) {
	release := make(chan struct{})
	store := &blockingIdentityStore{release: release}
	client, err := New(Config{
		BaseURL: "https://example.test", AppID: "agent", DeviceType: "SERVER",
		ServerTrustAnchors: map[string]string{"kid": "spki"}, IdentityStore: store,
		RequestTimeout: time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	cancelledContext, cancel := context.WithCancel(context.Background())
	first := make(chan error, 1)
	second := make(chan error, 1)
	go func() { first <- client.Initialize(cancelledContext) }()
	go func() { second <- client.Initialize(context.Background()) }()
	time.Sleep(10 * time.Millisecond)
	cancel()
	if error := <-first; error == nil || error.(*Error).Code != "SC_REQUEST_CANCELLED" {
		t.Fatalf("unexpected cancelled waiter result: %v", error)
	}
	close(release)
	if error := <-second; error == nil || error.(*Error).Code != "SC_IDENTITY_FAILED" {
		t.Fatalf("unexpected shared handshake result: %v", error)
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.calls != 1 {
		t.Fatalf("expected one identity load, got %d", store.calls)
	}
}

func TestUnifiedConfigAndRequestDefaults(t *testing.T) {
	directory := t.TempDir()
	client, err := New(Config{
		BaseURL: "http://127.0.0.1:8080", AppID: "agent", DeviceType: "server",
		ServerTrustAnchors: map[string]string{"kid": "spki"},
		IdentityStore:      FileIdentityStore{Path: filepath.Join(directory, "identity-v2.json")},
	})
	if err != nil {
		t.Fatal(err)
	}
	if client.config.DeviceType != "SERVER" || client.config.RequestTimeout != 15*time.Second ||
		client.config.AllowedClockSkew != 2*time.Minute {
		t.Fatal("unified configuration defaults were not applied")
	}
	if _, err := New(Config{
		BaseURL: "http://192.0.2.10:8080", AppID: "agent",
		ServerTrustAnchors: map[string]string{"kid": "spki"},
		IdentityStore:      FileIdentityStore{Path: filepath.Join(directory, "other.json")},
	}); err != nil {
		t.Fatalf("public HTTP URL must be accepted: %v", err)
	}
	for _, baseURL := range []string{
		"ftp://example.test", "https://user:secret@example.test",
		"https://example.test?x=1", "https://example.test#fragment",
	} {
		if _, err := New(Config{
			BaseURL: baseURL, AppID: "agent",
			ServerTrustAnchors: map[string]string{"kid": "spki"},
			IdentityStore:      FileIdentityStore{Path: filepath.Join(directory, "invalid.json")},
		}); err == nil {
			t.Fatalf("invalid base URL must be rejected: %s", baseURL)
		}
	}
}

func TestFileIdentityStoreUsesV2SchemaAndAppBinding(t *testing.T) {
	path := filepath.Join(t.TempDir(), "identity-v2.json")
	store := FileIdentityStore{Path: path}
	first, err := store.LoadOrCreate("agent")
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.LoadOrCreate("agent")
	if err != nil {
		t.Fatal(err)
	}
	if first.DeviceID() != second.DeviceID() {
		t.Fatal("device ID was not stable")
	}
	if _, err := store.LoadOrCreate("other-agent"); err == nil {
		t.Fatal("identity must be bound to app ID")
	}
	legacy := filepath.Join(t.TempDir(), "legacy.json")
	if err := os.WriteFile(legacy, []byte(`{"deviceId":"old","privateKey":"old"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := (FileIdentityStore{Path: legacy}).LoadOrCreate("agent"); err == nil {
		t.Fatal("legacy identity must not be migrated or overwritten")
	}
}

func TestContextErrorsUseStableCodes(t *testing.T) {
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if got := contextError(cancelled.Err()).Code; got != "SC_REQUEST_CANCELLED" {
		t.Fatalf("unexpected cancellation code: %s", got)
	}
	if got := contextError(context.DeadlineExceeded).Code; got != "SC_REQUEST_TIMEOUT" {
		t.Fatalf("unexpected timeout code: %s", got)
	}
}

func loadVector(t *testing.T, name string) vector {
	t.Helper()
	data, err := os.ReadFile("../../protocol/test-vectors/" + name)
	if err != nil {
		t.Fatal(err)
	}
	var value vector
	if err := json.Unmarshal(data, &value); err != nil {
		t.Fatal(err)
	}
	return value
}

func TestAESGCMVectors(t *testing.T) {
	for _, name := range []string{"aes-256-gcm-request.json", "aes-256-gcm-response.json"} {
		t.Run(name, func(t *testing.T) {
			v := loadVector(t, name)
			key, err := hex.DecodeString(v.KeyHex)
			if err != nil {
				t.Fatal(err)
			}
			prefixBytes, err := hex.DecodeString(v.NoncePrefixHex)
			if err != nil {
				t.Fatal(err)
			}
			var prefix [4]byte
			copy(prefix[:], prefixBytes)
			nonce := makeNonce(prefix, v.Sequence)
			if enc(nonce) != v.NonceBase64URL {
				t.Fatalf("nonce mismatch: %s", enc(nonce))
			}
			env := envelope{
				V: 1, Suite: InternationalSuite, KID: v.KID, SID: v.SID,
				TS: v.Timestamp, Seq: v.Sequence, Method: v.Method, Path: v.Path,
				RequestID: v.RequestID, ContentType: v.ContentType, Status: v.LogicalStatus,
			}
			associated := aad(v.Direction, env)
			if string(associated) != v.AADUTF8 {
				t.Fatalf("AAD mismatch:\n%s", associated)
			}
			sealed, err := seal(key, nonce, associated, []byte(v.PlaintextUTF8))
			if err != nil {
				t.Fatal(err)
			}
			if enc(sealed) != v.CombinedCiphertextBase64URL {
				t.Fatalf("ciphertext mismatch: %s", enc(sealed))
			}
			opened, err := open(key, nonce, associated, sealed)
			if err != nil || string(opened) != v.PlaintextUTF8 {
				t.Fatalf("round trip failed: %q, %v", opened, err)
			}
		})
	}
}

func TestNormalizePathCanonicalizesQuery(t *testing.T) {
	actual, err := normalizePath("/cross/info?x=1&lang=zh")
	if err != nil {
		t.Fatal(err)
	}
	if actual != "/cross/info?lang=zh&x=1" {
		t.Fatalf("unexpected canonical path: %s", actual)
	}
	if _, err := normalizePath("https://example.test/cross/info"); err == nil {
		t.Fatal("absolute URL must be rejected")
	}
}

func TestParsesJavaP256SPKIForECDH(t *testing.T) {
	serverSigningKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serverSPKI, err := x509.MarshalPKIXPublicKey(&serverSigningKey.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParsePKIXPublicKey(serverSPKI)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := parsed.(*ecdsa.PublicKey); !ok {
		t.Fatalf("P-256 SPKI unexpectedly parsed as %T", parsed)
	}
	serverPeer, err := parseP256ECDHPublicKey(serverSPKI)
	if err != nil {
		t.Fatal(err)
	}
	clientPrivate, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serverPrivate, err := serverSigningKey.ECDH()
	if err != nil {
		t.Fatal(err)
	}
	clientSecret, err := clientPrivate.ECDH(serverPeer)
	if err != nil {
		t.Fatal(err)
	}
	serverSecret, err := serverPrivate.ECDH(clientPrivate.PublicKey())
	if err != nil {
		t.Fatal(err)
	}
	if !hmac.Equal(clientSecret, serverSecret) {
		t.Fatal("P-256 ECDH secrets do not match")
	}
}
