package httpadapter

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	sc "github.com/coolxer/secure-communication-server-go"
)

func TestMiddlewareRewritesAndSeals(t *testing.T) {
	ctx := context.Background()
	now := time.UnixMilli(1785283200000)
	cfg := sc.DefaultConfig()
	cfg.Enabled = true
	cfg.Clock = func() time.Time { return now }
	repo := sc.NewMemorySessionRepository(cfg.Clock)
	keys := sc.SessionKeys{KeyID: "key", SessionID: "session", Suite: sc.InternationalSuite, RequestKey: bytes.Repeat([]byte{1}, 32), ResponseKey: bytes.Repeat([]byte{2}, 32), RequestNoncePrefix: [4]byte{1, 2, 3, 4}, ResponseNoncePrefix: [4]byte{5, 6, 7, 8}, ExpiresAt: now.Add(time.Minute)}
	_ = repo.SavePending(ctx, sc.PendingSession{Keys: keys, ExpiresAt: keys.ExpiresAt})
	_ = repo.Activate(ctx, "key", "session")
	messages, _ := sc.NewMessageService(repo, sc.NewMemoryReplayProtector(cfg.Clock), cfg)
	business := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if r.Method != "POST" || r.URL.Path != "/v1/ping" || r.URL.RawQuery != "a=1" || r.Header.Get("x-sc-request-id") != "request-1" || string(body) != "hello" {
			t.Fatalf("bad logical request: %s %s?%s %q", r.Method, r.URL.Path, r.URL.RawQuery, body)
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(201)
		_, _ = w.Write([]byte("ok"))
	})
	handler := New(cfg, nil, messages, sc.LogicalRouteAuthorizerFunc(func(m, p string) bool { return m == "POST" && p == "/v1/ping" }), business)
	payload, _ := json.Marshal(sc.ProtectedPayload{Method: "POST", Path: "/v1/ping?a=1", ContentType: "application/json", Headers: map[string]string{}, Body: sc.Encode([]byte("hello"))})
	env := sc.Envelope{V: 1, Suite: sc.InternationalSuite, KID: "key", SID: "session", TS: now.UnixMilli(), Seq: 1, RID: "request-1", Method: "POST", Path: sc.MessageEndpoint, ContentType: sc.ProtectedMediaType}
	n := sc.Nonce(keys.RequestNoncePrefix, 1)
	env.Nonce = sc.Encode(n)
	env.Ciphertext = sc.Encode(mustSeal(t, keys.RequestKey, n, sc.AAD(sc.Request, env), payload))
	wire, _ := json.Marshal(env)
	request := httptest.NewRequest("POST", sc.MessageEndpoint, bytes.NewReader(wire))
	request.TLS = &tls.ConnectionState{}
	request.Header.Set("Content-Type", sc.EnvelopeMediaType)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != 200 || response.Header().Get("Content-Type") != sc.EnvelopeMediaType {
		t.Fatalf("unexpected response %d %s", response.Code, response.Body.String())
	}
	var sealed sc.Envelope
	if e := json.Unmarshal(response.Body.Bytes(), &sealed); e != nil {
		t.Fatal(e)
	}
	rn := sc.Nonce(keys.ResponseNoncePrefix, 1)
	plain, e := (sc.AESGCMAlgorithm{}).Open(keys.ResponseKey, rn, sc.AAD(sc.Response, sealed), mustDecode(t, sealed.Ciphertext))
	if e != nil {
		t.Fatal(e)
	}
	var result sc.ProtectedResponse
	_ = json.Unmarshal(plain, &result)
	if result.ContentType != "text/plain" || string(mustDecode(t, result.Body)) != "ok" || sealed.Status != 201 {
		t.Fatal("protected response mismatch")
	}
}

func TestExplicitTLSPolicyRejectsHTTPHandshakeAndMessage(t *testing.T) {
	cfg := sc.DefaultConfig()
	cfg.Enabled = true
	cfg.RequireTLS = true
	handler := New(cfg, nil, nil, nil, nil)

	handshake := httptest.NewRequest("POST", sc.HandshakeEndpoint, bytes.NewReader([]byte("{}")))
	handshake.Header.Set("Content-Type", "application/json")
	handshakeResponse := httptest.NewRecorder()
	handler.ServeHTTP(handshakeResponse, handshake)
	if handshakeResponse.Code != sc.ErrTLSRequired.HTTPStatus ||
		!bytes.Contains(handshakeResponse.Body.Bytes(), []byte(sc.ErrTLSRequired.Code)) {
		t.Fatalf("HTTP handshake was not rejected by TLS policy: %d %s",
			handshakeResponse.Code, handshakeResponse.Body.String())
	}

	message := httptest.NewRequest("POST", sc.MessageEndpoint, bytes.NewReader([]byte("{}")))
	message.Header.Set("Content-Type", sc.EnvelopeMediaType)
	messageResponse := httptest.NewRecorder()
	handler.ServeHTTP(messageResponse, message)
	if messageResponse.Code != sc.ErrTLSRequired.HTTPStatus ||
		!bytes.Contains(messageResponse.Body.Bytes(), []byte(sc.ErrTLSRequired.Code)) {
		t.Fatalf("HTTP message was not rejected by TLS policy: %d %s",
			messageResponse.Code, messageResponse.Body.String())
	}
}
func mustSeal(t *testing.T, k, n, a, p []byte) []byte {
	t.Helper()
	v, e := (sc.AESGCMAlgorithm{}).Seal(k, n, a, p)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func mustDecode(t *testing.T, v string) []byte {
	t.Helper()
	b, e := sc.Decode(v, true)
	if e != nil {
		t.Fatal(e)
	}
	return b
}
