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
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	ProtocolVersion    = 1
	InternationalSuite = "P256_HKDF_SHA256_AES256_GCM"
	EnvelopeMediaType  = "application/sc-envelope+json"
	ProtectedMediaType = "application/sc-protected+json"
	MessageEndpoint    = "/sc/v1/message"
)

type Error struct {
	Code       string
	HTTPStatus int
	TraceID    string
	Cause      error
}

func (e *Error) Error() string { return e.Code }
func (e *Error) Unwrap() error { return e.Cause }

type InstallationIdentity interface {
	DeviceID() string
	PublicKeySPKI() ([]byte, error)
	Sign(data []byte) ([]byte, error)
}
type IdentityStore interface {
	LoadOrCreate(appID string) (InstallationIdentity, error)
}

type Config struct {
	BaseURL                         string
	AppID                           string
	DeviceType                      string
	ServerTrustAnchors              map[string]string
	IdentityStore                   IdentityStore
	HTTPClient                      *http.Client
	RequestTimeout                  time.Duration
	AllowedClockSkew                time.Duration
	AllowInsecureLoopbackForTesting bool
	Clock                           func() time.Time
}

type Client struct {
	config          Config
	baseURL         *url.URL
	mu              sync.Mutex
	enrollmentToken string
	session         *session
	nextSequence    uint64
	initializing    *initialization
	generation      uint64
}

type initialization struct {
	done chan struct{}
	err  error
}

type session struct {
	keyID, sessionID              string
	requestKey, responseKey       []byte
	requestPrefix, responsePrefix [4]byte
	expiresAt                     time.Time
}

type envelope struct {
	V           int    `json:"v"`
	Suite       string `json:"suite"`
	KID         string `json:"kid"`
	SID         string `json:"sid"`
	TS          int64  `json:"ts"`
	Seq         uint64 `json:"seq"`
	RequestID   string `json:"rid"`
	Method      string `json:"m"`
	Path        string `json:"p"`
	ContentType string `json:"cty"`
	Status      int    `json:"st"`
	Nonce       string `json:"nonce"`
	Ciphertext  string `json:"ct"`
}

type protectedPayload struct {
	Method      string            `json:"method"`
	Path        string            `json:"path"`
	ContentType string            `json:"contentType"`
	Headers     map[string]string `json:"headers"`
	Body        string            `json:"body"`
}
type protectedResponse struct {
	ContentType string `json:"contentType"`
	Body        string `json:"body"`
}
type Response struct {
	Status      int
	ContentType string
	Body        []byte
}

type Request struct {
	Method           string
	LogicalPath      string
	ContentType      string
	ProtectedHeaders map[string]string
	Body             []byte
	RequestID        string
}

func New(config Config) (*Client, error) {
	parsed, err := url.Parse(config.BaseURL)
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" ||
		(parsed.Scheme != "https" && !(config.AllowInsecureLoopbackForTesting &&
			parsed.Scheme == "http" && isLoopback(parsed.Hostname()))) {
		return nil, errors.New("secure communication base URL must use HTTPS")
	}
	if !validAppID(config.AppID) || config.IdentityStore == nil || len(config.ServerTrustAnchors) == 0 {
		return nil, errors.New("app ID, identity store, and trust anchors are required")
	}
	if config.DeviceType == "" {
		config.DeviceType = "HOST"
	}
	config.DeviceType = strings.ToUpper(config.DeviceType)
	if !validDeviceType(config.DeviceType) {
		return nil, errors.New("invalid device type")
	}
	if config.RequestTimeout == 0 {
		config.RequestTimeout = 15 * time.Second
	}
	if config.RequestTimeout < 0 {
		return nil, errors.New("request timeout must be positive")
	}
	if config.AllowedClockSkew == 0 {
		config.AllowedClockSkew = 2 * time.Minute
	}
	if config.AllowedClockSkew < 0 {
		return nil, errors.New("allowed clock skew must not be negative")
	}
	if config.HTTPClient == nil {
		config.HTTPClient = &http.Client{}
	}
	transport := *config.HTTPClient
	transport.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	transport.Timeout = 0
	config.HTTPClient = &transport
	if config.Clock == nil {
		config.Clock = time.Now
	}
	return &Client{config: config, baseURL: parsed, nextSequence: 1}, nil
}

func (c *Client) Enroll(token string) error {
	if c.config.DeviceType == "H5" {
		return &Error{Code: "SC_ENROLLMENT_NOT_SUPPORTED"}
	}
	if strings.TrimSpace(token) == "" {
		return errors.New("enrollment token is required")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.enrollmentToken = token
	return nil
}

func (c *Client) Initialize(ctx context.Context) error {
	waitCtx, cancel := c.executionContext(ctx)
	defer cancel()
	c.mu.Lock()
	if c.session != nil && c.config.Clock().Before(c.session.expiresAt) {
		c.mu.Unlock()
		return nil
	}
	if c.initializing == nil {
		state := &initialization{done: make(chan struct{})}
		c.initializing = state
		generation := c.generation
		go c.runInitialize(state, generation)
	}
	state := c.initializing
	c.mu.Unlock()
	select {
	case <-waitCtx.Done():
		return contextError(waitCtx.Err())
	case <-state.done:
		return state.err
	}
}

func (c *Client) runInitialize(state *initialization, generation uint64) {
	ctx, cancel := context.WithTimeout(context.Background(), c.config.RequestTimeout)
	defer cancel()
	err := c.performInitialize(ctx, generation)
	c.mu.Lock()
	state.err = err
	if c.initializing == state {
		c.initializing = nil
	}
	close(state.done)
	c.mu.Unlock()
}

func (c *Client) performInitialize(ctx context.Context, generation uint64) error {
	c.mu.Lock()
	token := c.enrollmentToken
	c.mu.Unlock()
	identity, err := c.config.IdentityStore.LoadOrCreate(c.config.AppID)
	if err != nil {
		return &Error{Code: "SC_IDENTITY_FAILED", Cause: err}
	}
	ephemeral, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	clientEphemeral, _ := x509.MarshalPKIXPublicKey(ephemeral.PublicKey())
	installation, err := identity.PublicKeySPKI()
	if err != nil {
		return &Error{Code: "SC_IDENTITY_FAILED", Cause: err}
	}
	startRequest := handshakeRequest{V: 1, Suite: InternationalSuite, AppID: c.config.AppID,
		DeviceID: identity.DeviceID(), DeviceType: c.config.DeviceType,
		ClientEphemeralPublicKey: enc(clientEphemeral), InstallationPublicKey: enc(installation),
		EnrollmentToken: token, Timestamp: c.config.Clock().UnixMilli()}
	var start handshakeResponse
	if err := c.postJSON(ctx, "/sc/v1/handshake", startRequest, &start); err != nil {
		return err
	}
	if start.V != 1 || start.Suite != InternationalSuite {
		return &Error{Code: "SC_HANDSHAKE_FAILED"}
	}
	pinnedValue, ok := c.config.ServerTrustAnchors[start.KID]
	if !ok {
		return &Error{Code: "SC_HANDSHAKE_FAILED"}
	}
	serverIdentity, err := dec(start.ServerIdentityPublicKey)
	if err != nil {
		return handshakeErr(err)
	}
	pinned, err := dec(pinnedValue)
	if err != nil || !hmac.Equal(serverIdentity, pinned) {
		return &Error{Code: "SC_HANDSHAKE_FAILED"}
	}
	serverEphemeral, err := dec(start.ServerEphemeralPublicKey)
	if err != nil {
		return handshakeErr(err)
	}
	hash := transcriptHash(startRequest, start, clientEphemeral, installation, serverIdentity, serverEphemeral)
	identityAny, err := x509.ParsePKIXPublicKey(serverIdentity)
	if err != nil {
		return handshakeErr(err)
	}
	serverSigning, ok := identityAny.(*ecdsa.PublicKey)
	if !ok {
		return &Error{Code: "SC_HANDSHAKE_FAILED"}
	}
	signature, err := dec(start.Signature)
	if err != nil || !verifyP1363(serverSigning, hash[:], signature) {
		return &Error{Code: "SC_HANDSHAKE_FAILED"}
	}
	peer, err := parseP256ECDHPublicKey(serverEphemeral)
	if err != nil {
		return handshakeErr(err)
	}
	secret, err := ephemeral.ECDH(peer)
	if err != nil {
		return handshakeErr(err)
	}
	material := hkdf(secret, hash[:], []byte("SC1/session/"+InternationalSuite+"/"+start.SID), 72)
	proof, err := identity.Sign(hash[:])
	if err != nil {
		return &Error{Code: "SC_IDENTITY_FAILED", Cause: err}
	}
	var finish struct {
		Active    bool  `json:"active"`
		ExpiresAt int64 `json:"expiresAt"`
	}
	if err := c.postJSON(ctx, "/sc/v1/handshake/finish", map[string]string{
		"kid": start.KID, "sid": start.SID, "proof": enc(proof)}, &finish); err != nil {
		return err
	}
	if !finish.Active {
		return &Error{Code: "SC_HANDSHAKE_FAILED"}
	}
	current := &session{keyID: start.KID, sessionID: start.SID,
		requestKey: append([]byte(nil), material[:32]...), responseKey: append([]byte(nil), material[32:64]...),
		expiresAt: time.UnixMilli(finish.ExpiresAt)}
	copy(current.requestPrefix[:], material[64:68])
	copy(current.responsePrefix[:], material[68:72])
	c.mu.Lock()
	if c.generation != generation {
		c.mu.Unlock()
		return &Error{Code: "SC_REQUEST_CANCELLED"}
	}
	c.session = current
	c.nextSequence = 1
	if c.enrollmentToken == token {
		c.enrollmentToken = ""
	}
	c.mu.Unlock()
	for i := range material {
		material[i] = 0
	}
	for i := range secret {
		secret[i] = 0
	}
	return nil
}

func (c *Client) Request(ctx context.Context, input Request) (*Response, error) {
	method := strings.ToUpper(input.Method)
	if method == "" {
		method = http.MethodGet
	}
	if !validMethod(method) {
		return nil, errors.New("invalid logical method")
	}
	path, err := normalizePath(input.LogicalPath)
	if err != nil {
		return nil, err
	}
	contentType := input.ContentType
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	if !validContentType(contentType) {
		return nil, errors.New("invalid content type")
	}
	requestID := input.RequestID
	if requestID != "" && !validIdentifier(requestID) {
		return nil, errors.New("invalid request ID")
	}
	executionCtx, cancel := c.executionContext(ctx)
	defer cancel()
	if err := c.Initialize(executionCtx); err != nil {
		return nil, err
	}
	c.mu.Lock()
	current := c.session
	if c.nextSequence == 0 || c.nextSequence > 9_007_199_254_740_991 {
		c.mu.Unlock()
		return nil, &Error{Code: "SC_SEQUENCE_EXHAUSTED"}
	}
	sequence := c.nextSequence
	c.nextSequence++
	c.mu.Unlock()
	headers := make(map[string]string, len(input.ProtectedHeaders))
	for name, value := range input.ProtectedHeaders {
		name = strings.ToLower(name)
		if !validHeader(name, value) {
			return nil, errors.New("invalid protected header")
		}
		headers[name] = value
	}
	if requestID == "" {
		requestBytes := make([]byte, 16)
		if _, err := rand.Read(requestBytes); err != nil {
			return nil, err
		}
		requestID = enc(requestBytes)
	}
	if !validIdentifier(requestID) {
		return nil, errors.New("invalid request ID")
	}
	payload, _ := json.Marshal(protectedPayload{Method: method, Path: path,
		ContentType: strings.ToLower(strings.Split(contentType, ";")[0]), Headers: headers, Body: enc(input.Body)})
	timestamp := c.config.Clock().UnixMilli()
	nonce := makeNonce(current.requestPrefix, sequence)
	env := envelope{V: 1, Suite: InternationalSuite, KID: current.keyID, SID: current.sessionID,
		TS: timestamp, Seq: sequence, RequestID: requestID, Method: http.MethodPost,
		Path: MessageEndpoint, ContentType: ProtectedMediaType, Nonce: enc(nonce)}
	sealed, err := seal(current.requestKey, nonce, aad("request", env), payload)
	if err != nil {
		return nil, err
	}
	env.Ciphertext = enc(sealed)
	encoded, _ := json.Marshal(env)
	request, _ := http.NewRequestWithContext(executionCtx, http.MethodPost, c.resolve(MessageEndpoint), bytes.NewReader(encoded))
	request.Header.Set("Content-Type", EnvelopeMediaType)
	request.Header.Set("Accept", EnvelopeMediaType)
	httpResponse, err := c.config.HTTPClient.Do(request)
	if err != nil {
		return nil, contextOrNetworkError(executionCtx, err)
	}
	defer httpResponse.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(httpResponse.Body, 2<<20))
	if err != nil {
		return nil, err
	}
	if !strings.HasPrefix(strings.ToLower(httpResponse.Header.Get("Content-Type")), EnvelopeMediaType) {
		var remote struct{ Code, TraceID string }
		_ = json.Unmarshal(responseBody, &remote)
		if remote.Code == "" {
			remote.Code = "SC_TRANSPORT_FAILED"
		}
		result := &Error{Code: remote.Code, HTTPStatus: httpResponse.StatusCode, TraceID: remote.TraceID}
		if remote.Code == "SC_UNKNOWN_SESSION" {
			c.CloseSession()
		}
		return nil, result
	}
	var responseEnvelope envelope
	if err := strictJSON(responseBody, &responseEnvelope); err != nil {
		return nil, &Error{Code: "SC_INVALID_ENVELOPE", Cause: err}
	}
	if responseEnvelope.KID != current.keyID || responseEnvelope.SID != current.sessionID {
		c.CloseSession()
		return nil, &Error{Code: "SC_UNKNOWN_SESSION"}
	}
	if responseEnvelope.V != 1 || responseEnvelope.Seq != sequence || responseEnvelope.RequestID != requestID || responseEnvelope.Method != http.MethodPost || responseEnvelope.Path != MessageEndpoint || responseEnvelope.ContentType != ProtectedMediaType {
		return nil, &Error{Code: "SC_ROUTE_MISMATCH"}
	}
	if responseEnvelope.Suite != InternationalSuite || responseEnvelope.Status < 100 || responseEnvelope.Status > 599 {
		return nil, &Error{Code: "SC_INVALID_ENVELOPE"}
	}
	if absDuration(c.config.Clock().Sub(time.UnixMilli(responseEnvelope.TS))) > c.config.AllowedClockSkew {
		return nil, &Error{Code: "SC_REQUEST_EXPIRED"}
	}
	responseNonce := makeNonce(current.responsePrefix, sequence)
	received, err := dec(responseEnvelope.Nonce)
	if err != nil || !hmac.Equal(responseNonce, received) {
		return nil, &Error{Code: "SC_INVALID_ENVELOPE"}
	}
	ciphertext, err := dec(responseEnvelope.Ciphertext)
	if err != nil {
		return nil, &Error{Code: "SC_INVALID_ENVELOPE"}
	}
	opened, err := open(current.responseKey, responseNonce, aad("response", responseEnvelope), ciphertext)
	if err != nil {
		return nil, &Error{Code: "SC_AUTHENTICATION_FAILED", Cause: err}
	}
	var protectedResult protectedResponse
	if err := strictJSON(opened, &protectedResult); err != nil {
		return nil, &Error{Code: "SC_INVALID_ENVELOPE", Cause: err}
	}
	responseBytes, err := decAllowEmpty(protectedResult.Body)
	if err != nil || !validContentType(protectedResult.ContentType) {
		return nil, &Error{Code: "SC_INVALID_ENVELOPE", Cause: err}
	}
	return &Response{Status: responseEnvelope.Status,
		ContentType: strings.ToLower(strings.Split(protectedResult.ContentType, ";")[0]),
		Body:        responseBytes}, nil
}

func (c *Client) CloseSession() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.session = nil
	c.nextSequence = 1
	c.generation++
}

type handshakeRequest struct {
	V                                                                                                    int `json:"v"`
	Suite, AppID, DeviceID, DeviceType, ClientEphemeralPublicKey, InstallationPublicKey, EnrollmentToken string
	Timestamp                                                                                            int64 `json:"timestamp"`
}

func (h handshakeRequest) MarshalJSON() ([]byte, error) {
	type wire struct {
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
	return json.Marshal(wire{h.V, h.Suite, h.AppID, h.DeviceID, h.DeviceType, h.ClientEphemeralPublicKey, h.InstallationPublicKey, h.EnrollmentToken, h.Timestamp})
}

type handshakeResponse struct {
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

func (c *Client) postJSON(ctx context.Context, path string, input, output any) error {
	body, _ := json.Marshal(input)
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, c.resolve(path), bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	response, err := c.config.HTTPClient.Do(request)
	if err != nil {
		return contextOrNetworkError(ctx, err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 256<<10))
	if err != nil {
		return err
	}
	if response.StatusCode/100 != 2 {
		var remote struct{ Code, TraceID string }
		_ = json.Unmarshal(data, &remote)
		if remote.Code == "" {
			remote.Code = "SC_HANDSHAKE_FAILED"
		}
		return &Error{Code: remote.Code, HTTPStatus: response.StatusCode, TraceID: remote.TraceID}
	}
	return strictJSON(data, output)
}
func (c *Client) resolve(path string) string {
	return c.baseURL.ResolveReference(&url.URL{Path: path}).String()
}
func strictJSON(data []byte, value any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return err
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return errors.New("trailing JSON")
	}
	return nil
}
func transcriptHash(request handshakeRequest, response handshakeResponse, clientEphemeral, installation, serverIdentity, serverEphemeral []byte) [32]byte {
	value := strings.Join([]string{"SC1-HANDSHAKE", "1", InternationalSuite, request.AppID, request.DeviceID, request.DeviceType, enc(clientEphemeral), enc(installation), enc(serverIdentity), enc(serverEphemeral), response.KID, response.SID, fmt.Sprint(response.CreatedAt), fmt.Sprint(response.ExpiresAt)}, "\n")
	return sha256.Sum256([]byte(value))
}
func verifyP1363(key *ecdsa.PublicKey, hash, signature []byte) bool {
	if len(signature) != 64 {
		return false
	}
	digest := sha256.Sum256(hash)
	return ecdsa.Verify(key, digest[:], new(big.Int).SetBytes(signature[:32]), new(big.Int).SetBytes(signature[32:]))
}

func parseP256ECDHPublicKey(encoded []byte) (*ecdh.PublicKey, error) {
	parsed, err := x509.ParsePKIXPublicKey(encoded)
	if err != nil {
		return nil, err
	}
	switch key := parsed.(type) {
	case *ecdsa.PublicKey:
		peer, conversionError := key.ECDH()
		if conversionError != nil || peer.Curve() != ecdh.P256() {
			return nil, errors.New("peer key is not P-256")
		}
		return peer, nil
	case *ecdh.PublicKey:
		if key.Curve() != ecdh.P256() {
			return nil, errors.New("peer key is not P-256")
		}
		return key, nil
	default:
		return nil, errors.New("peer key is not an EC public key")
	}
}

func p1363(r, s *big.Int) []byte {
	out := make([]byte, 64)
	r.FillBytes(out[:32])
	s.FillBytes(out[32:])
	return out
}
func hkdf(input, salt, info []byte, length int) []byte {
	extract := hmac.New(sha256.New, salt)
	extract.Write(input)
	prk := extract.Sum(nil)
	result := make([]byte, 0, length)
	var previous []byte
	for counter := byte(1); len(result) < length; counter++ {
		expand := hmac.New(sha256.New, prk)
		expand.Write(previous)
		expand.Write(info)
		expand.Write([]byte{counter})
		previous = expand.Sum(nil)
		result = append(result, previous...)
	}
	return result[:length]
}
func makeNonce(prefix [4]byte, sequence uint64) []byte {
	nonce := make([]byte, 12)
	copy(nonce, prefix[:])
	binary.BigEndian.PutUint64(nonce[4:], sequence)
	return nonce
}
func aad(direction string, e envelope) []byte {
	parts := []string{"SC1", direction, e.Suite, e.KID, e.SID, fmt.Sprint(e.TS), fmt.Sprint(e.Seq), e.RequestID, e.Method, e.Path, e.ContentType}
	if direction == "response" {
		parts = append(parts, fmt.Sprint(e.Status))
	}
	return []byte(strings.Join(parts, "\n"))
}
func seal(key, nonce, aad, plaintext []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Seal(nil, nonce, plaintext, aad), nil
}
func open(key, nonce, aad, ciphertext []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, nonce, ciphertext, aad)
}
func normalizePath(value string) (string, error) {
	parsed, err := url.ParseRequestURI(value)
	if err != nil || !strings.HasPrefix(value, "/") || parsed.IsAbs() || parsed.Fragment != "" {
		return "", errors.New("invalid logical path")
	}
	query := parsed.Query()
	keys := make([]string, 0, len(query))
	for key := range query {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	var pairs []string
	for _, key := range keys {
		values := query[key]
		sort.Strings(values)
		for _, v := range values {
			pairs = append(pairs, url.QueryEscape(key)+"="+url.QueryEscape(v))
		}
	}
	path := parsed.EscapedPath()
	if len(pairs) > 0 {
		path += "?" + strings.Join(pairs, "&")
	}
	return path, nil
}
func validHeader(name, value string) bool {
	if name == "" || len(name) > 64 || strings.ContainsAny(value, "\r\n") || len(value) > 8192 {
		return false
	}
	for _, r := range name {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-') {
			return false
		}
	}
	return true
}
func validMethod(value string) bool {
	if len(value) < 3 || len(value) > 16 {
		return false
	}
	for _, r := range value {
		if r < 'A' || r > 'Z' {
			return false
		}
	}
	return true
}
func validIdentifier(value string) bool {
	if len(value) < 1 || len(value) > 128 {
		return false
	}
	for _, r := range value {
		if r < 0x21 || r > 0x7e {
			return false
		}
	}
	return true
}
func validContentType(value string) bool {
	parts := strings.SplitN(value, ";", 2)
	value = strings.ToLower(strings.TrimSpace(parts[0]))
	pieces := strings.Split(value, "/")
	return len(pieces) == 2 && pieces[0] != "" && pieces[1] != ""
}
func enc(value []byte) string          { return base64.RawURLEncoding.EncodeToString(value) }
func dec(value string) ([]byte, error) { return base64.RawURLEncoding.DecodeString(value) }
func decAllowEmpty(value string) ([]byte, error) {
	if value == "" {
		return []byte{}, nil
	}
	return dec(value)
}
func handshakeErr(err error) *Error { return &Error{Code: "SC_HANDSHAKE_FAILED", Cause: err} }

func (c *Client) executionContext(parent context.Context) (context.Context, context.CancelFunc) {
	if parent == nil {
		parent = context.Background()
	}
	return context.WithTimeout(parent, c.config.RequestTimeout)
}

func contextError(err error) *Error {
	if errors.Is(err, context.DeadlineExceeded) {
		return &Error{Code: "SC_REQUEST_TIMEOUT", Cause: err}
	}
	return &Error{Code: "SC_REQUEST_CANCELLED", Cause: err}
}

func contextOrNetworkError(ctx context.Context, err error) *Error {
	if ctx.Err() != nil {
		return contextError(ctx.Err())
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return contextError(err)
	}
	return &Error{Code: "SC_NETWORK_FAILED", Cause: err}
}

func validDeviceType(value string) bool {
	switch value {
	case "H5", "HOST", "SERVER", "ANDROID", "IOS", "EMULATOR":
		return true
	default:
		return false
	}
}

func validAppID(value string) bool {
	if len(value) < 1 || len(value) > 128 {
		return false
	}
	for _, character := range value {
		if !(character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z' ||
			character >= '0' && character <= '9' || strings.ContainsRune("._:@/-", character)) {
			return false
		}
	}
	return true
}

func isLoopback(host string) bool {
	ip := net.ParseIP(host)
	return strings.EqualFold(host, "localhost") || (ip != nil && ip.IsLoopback())
}

func absDuration(value time.Duration) time.Duration {
	if value < 0 {
		return -value
	}
	return value
}
