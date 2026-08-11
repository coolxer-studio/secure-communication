package httpadapter

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	sc "github.com/coolxer/secure-communication-server-go"
)

type trustKey struct{}
type TransportTrust struct{ Value, SessionID string }

func TransportTrustFrom(ctx context.Context) (TransportTrust, bool) {
	v, ok := ctx.Value(trustKey{}).(TransportTrust)
	return v, ok
}
func contextWithTrust(ctx context.Context, e sc.Envelope) context.Context {
	return context.WithValue(ctx, trustKey{}, TransportTrust{"sc1-authenticated/" + e.Suite, e.SID})
}
func newTraceID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func sessionSummary(v string) string { d := sha256.Sum256([]byte(v)); return hex.EncodeToString(d[:6]) }
