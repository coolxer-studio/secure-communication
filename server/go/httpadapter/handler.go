package httpadapter

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	sc "github.com/coolxer/secure-communication-server-go"
)

type Handler struct {
	Config     sc.Config
	Handshakes *sc.HandshakeService
	Messages   *sc.MessageService
	Routes     sc.LogicalRouteAuthorizer
	Next       http.Handler
	Logger     *slog.Logger
}

func New(c sc.Config, h *sc.HandshakeService, m *sc.MessageService, r sc.LogicalRouteAuthorizer, next http.Handler) *Handler {
	if r == nil {
		r = sc.RejectingRoutes{}
	}
	if next == nil {
		next = http.NotFoundHandler()
	}
	return &Handler{c, h, m, r, next, slog.Default()}
}
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !h.Config.Enabled {
		h.Next.ServeHTTP(w, r)
		return
	}
	switch r.URL.Path {
	case sc.HandshakeEndpoint:
		h.handshake(w, r, false)
	case sc.HandshakeFinishEndpoint:
		h.handshake(w, r, true)
	default:
		if h.Config.V1Enabled && (r.URL.Path == h.Config.Prefix || strings.HasPrefix(r.URL.Path, h.Config.Prefix+"/")) {
			h.message(w, r)
			return
		}
		h.Next.ServeHTTP(w, r)
	}
}
func (h *Handler) handshake(w http.ResponseWriter, r *http.Request, finish bool) {
	if r.Method != http.MethodPost || mediaType(r.Header.Get("Content-Type")) != "application/json" {
		writeError(w, sc.ErrRouteMismatch)
		return
	}
	if h.Config.RequireTLS && r.TLS == nil {
		writeError(w, sc.ErrTLSRequired)
		return
	}
	if h.Handshakes == nil {
		writeError(w, sc.ErrHandshakeFailed)
		return
	}
	data, e := readBounded(r.Body, h.Config.MaxEnvelopeBytes)
	if e != nil {
		writeProtocol(w, e)
		return
	}
	var out any
	if finish {
		var v sc.HandshakeFinishRequest
		if e = sc.StrictObjectJSON(data, &v, []string{"kid", "sid", "proof"}, nil); e == nil {
			out, e = h.Handshakes.Finish(r.Context(), v)
		}
	} else {
		var v sc.HandshakeRequest
		if e = sc.StrictObjectJSON(data, &v, []string{"v", "suite", "appId", "deviceId", "deviceType", "clientEphemeralPublicKey", "installationPublicKey", "timestamp"}, []string{"enrollmentToken"}); e == nil {
			out, e = h.Handshakes.Start(r.Context(), v, r.Header.Get("Origin"), r.RemoteAddr)
		}
	}
	if e != nil {
		writeProtocol(w, e)
		return
	}
	writeJSON(w, http.StatusOK, out)
}
func (h *Handler) message(w http.ResponseWriter, r *http.Request) {
	started := time.Now()
	if r.Method == http.MethodOptions {
		h.Next.ServeHTTP(w, r)
		return
	}
	if h.Config.RequireTLS && r.TLS == nil {
		writeError(w, sc.ErrTLSRequired)
		return
	}
	if r.Method != http.MethodPost || r.URL.Path != h.Config.Prefix {
		writeError(w, sc.ErrRouteMismatch)
		return
	}
	if mediaType(r.Header.Get("Content-Type")) != sc.EnvelopeMediaType {
		writeError(w, sc.ErrInvalidEnvelope)
		return
	}
	if h.Messages == nil {
		writeError(w, sc.ErrInternal)
		return
	}
	data, e := readBounded(r.Body, h.Config.MaxEnvelopeBytes)
	if e != nil {
		writeProtocol(w, e)
		return
	}
	opened, e := h.Messages.Open(r.Context(), data)
	if e != nil {
		h.failure(opened, e, started)
		writeProtocol(w, e)
		return
	}
	var payload sc.ProtectedPayload
	if e = sc.StrictObjectJSON(opened.Plaintext, &payload, []string{"method", "path", "contentType", "headers", "body"}, nil); e != nil {
		writeProtocol(w, e)
		return
	}
	body, e := sc.ValidateProtected(payload, h.Config.MaxBodyBytes)
	if e != nil {
		writeProtocol(w, e)
		return
	}
	parts := strings.SplitN(payload.Path, "?", 2)
	if !h.Routes.IsAllowed(payload.Method, parts[0]) {
		writeError(w, sc.ErrRouteMismatch)
		return
	}
	u, parseErr := url.ParseRequestURI(payload.Path)
	if parseErr != nil {
		writeError(w, sc.ErrInvalidEnvelope)
		return
	}
	plain := r.Clone(r.Context())
	plain.Method = payload.Method
	plain.URL = u
	plain.RequestURI = payload.Path
	plain.Body = io.NopCloser(bytes.NewReader(body))
	plain.ContentLength = int64(len(body))
	plain.Header = r.Header.Clone()
	for k, v := range payload.Headers {
		plain.Header.Set(k, v)
	}
	plain.Header.Set("x-sc-request-id", opened.Envelope.RID)
	plain.Header.Set("Content-Type", payload.ContentType)
	plain = plain.WithContext(contextWithTrust(plain.Context(), opened.Envelope))
	rec := newRecorder()
	h.Next.ServeHTTP(rec, plain)
	ct := mediaType(rec.Header().Get("Content-Type"))
	if ct == "" {
		ct = "application/octet-stream"
	}
	protected, _ := json.Marshal(sc.ProtectedResponse{ContentType: ct, Body: sc.Encode(rec.body.Bytes())})
	sealed, e := h.Messages.Seal(opened, protected, ct, rec.statusCode())
	if e != nil {
		writeProtocol(w, e)
		return
	}
	w.Header().Set("Content-Type", sc.EnvelopeMediaType)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(sealed)
}
func (h *Handler) failure(o *sc.OpenedRequest, e error, started time.Time) {
	var p *sc.ProtocolError
	code := sc.ErrInternal.Code
	if errors.As(e, &p) {
		code = p.Code
	}
	sid := "-"
	rid := "-"
	if o != nil {
		rid = o.Envelope.RID
		sid = o.Envelope.SID
	}
	h.Logger.Warn("secure_communication_failure", "version", 1, "requestId", rid, "session", sessionSummary(sid), "error", code, "durationMs", time.Since(started).Milliseconds())
}
func mediaType(v string) string {
	return strings.ToLower(strings.TrimSpace(strings.SplitN(v, ";", 2)[0]))
}
func readBounded(r io.Reader, n int) ([]byte, error) {
	b, e := io.ReadAll(io.LimitReader(r, int64(n)+1))
	if e != nil {
		return nil, sc.Wrap(sc.ErrInternal, e)
	}
	if len(b) > n {
		return nil, sc.Fail(sc.ErrPayloadTooLarge)
	}
	return b, nil
}

type recorder struct {
	header http.Header
	body   bytes.Buffer
	status int
}

func newRecorder() *recorder            { return &recorder{header: make(http.Header)} }
func (r *recorder) Header() http.Header { return r.header }
func (r *recorder) WriteHeader(s int) {
	if r.status == 0 {
		r.status = s
	}
}
func (r *recorder) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = 200
	}
	return r.body.Write(b)
}
func (r *recorder) statusCode() int {
	if r.status == 0 {
		return 200
	}
	return r.status
}
func writeJSON(w http.ResponseWriter, s int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(s)
	_ = json.NewEncoder(w).Encode(v)
}
func writeProtocol(w http.ResponseWriter, e error) {
	var p *sc.ProtocolError
	if errors.As(e, &p) {
		writeError(w, p.ErrorCode)
		return
	}
	writeError(w, sc.ErrInternal)
}
func writeError(w http.ResponseWriter, c sc.ErrorCode) {
	trace := newTraceID()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Trace-Id", trace)
	w.WriteHeader(c.HTTPStatus)
	_ = json.NewEncoder(w).Encode(map[string]string{"code": c.Code, "message": c.Message, "traceId": trace})
}
