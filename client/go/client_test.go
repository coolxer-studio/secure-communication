package securecommunication

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
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
