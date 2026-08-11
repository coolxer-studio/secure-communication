package securecommunication

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
)

type FileIdentityStore struct{ Path string }
type identityFile struct {
	DeviceID   string `json:"deviceId"`
	PrivateKey string `json:"privateKey"`
}

func (s FileIdentityStore) LoadOrCreate() (*Identity, error) {
	if s.Path == "" {
		return nil, errors.New("identity path is required")
	}
	data, err := os.ReadFile(s.Path)
	if err == nil {
		var stored identityFile
		if json.Unmarshal(data, &stored) != nil {
			return nil, errors.New("invalid identity file")
		}
		encoded, err := base64.RawURLEncoding.DecodeString(stored.PrivateKey)
		if err != nil {
			return nil, err
		}
		parsed, err := x509.ParsePKCS8PrivateKey(encoded)
		if err != nil {
			return nil, err
		}
		key, ok := parsed.(*ecdsa.PrivateKey)
		if !ok {
			return nil, errors.New("identity is not P-256")
		}
		return &Identity{DeviceID: stored.DeviceID, PrivateKey: key}, nil
	}
	if !os.IsNotExist(err) {
		return nil, err
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	private, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, err
	}
	random := make([]byte, 16)
	if _, err = rand.Read(random); err != nil {
		return nil, err
	}
	stored := identityFile{DeviceID: base64.RawURLEncoding.EncodeToString(random), PrivateKey: base64.RawURLEncoding.EncodeToString(private)}
	encoded, _ := json.Marshal(stored)
	if err = os.MkdirAll(filepath.Dir(s.Path), 0700); err != nil {
		return nil, err
	}
	temporary := s.Path + ".tmp"
	if err = os.WriteFile(temporary, encoded, 0600); err != nil {
		return nil, err
	}
	if err = os.Rename(temporary, s.Path); err != nil {
		return nil, err
	}
	return &Identity{DeviceID: stored.DeviceID, PrivateKey: key}, nil
}
