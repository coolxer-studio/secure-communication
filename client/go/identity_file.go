package securecommunication

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

type FileIdentityStore struct{ Path string }

type identityFile struct {
	Version    int    `json:"version"`
	AppID      string `json:"appId"`
	DeviceID   string `json:"deviceId"`
	PrivateKey string `json:"privateKey"`
	PublicKey  string `json:"publicKey"`
}

type fileIdentity struct {
	deviceID string
	private  *ecdsa.PrivateKey
	public   []byte
}

func (i *fileIdentity) DeviceID() string { return i.deviceID }
func (i *fileIdentity) PublicKeySPKI() ([]byte, error) {
	return append([]byte(nil), i.public...), nil
}
func (i *fileIdentity) Sign(data []byte) ([]byte, error) {
	digest := sha256.Sum256(data)
	r, s, err := ecdsa.Sign(rand.Reader, i.private, digest[:])
	if err != nil {
		return nil, err
	}
	return p1363(r, s), nil
}

func (s FileIdentityStore) LoadOrCreate(appID string) (InstallationIdentity, error) {
	if s.Path == "" || appID == "" {
		return nil, errors.New("identity path and app ID are required")
	}
	if info, err := os.Lstat(s.Path); err == nil && info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("identity path must not be a symbolic link")
	}
	data, err := os.ReadFile(s.Path)
	if err == nil {
		if chmodErr := os.Chmod(s.Path, 0600); chmodErr != nil {
			return nil, chmodErr
		}
		var stored identityFile
		if json.Unmarshal(data, &stored) != nil || stored.Version != 2 {
			return nil, errors.New("identity file is not v2; configure a new v2 identity path")
		}
		if stored.AppID != appID || stored.DeviceID == "" {
			return nil, errors.New("identity file does not belong to this app")
		}
		privateBytes, decodeErr := base64.RawURLEncoding.DecodeString(stored.PrivateKey)
		if decodeErr != nil {
			return nil, decodeErr
		}
		parsed, parseErr := x509.ParsePKCS8PrivateKey(privateBytes)
		key, ok := parsed.(*ecdsa.PrivateKey)
		if parseErr != nil || !ok || key.Curve != elliptic.P256() {
			return nil, errors.New("identity private key is not P-256")
		}
		public, decodeErr := base64.RawURLEncoding.DecodeString(stored.PublicKey)
		if decodeErr != nil {
			return nil, decodeErr
		}
		parsedPublic, parseErr := x509.ParsePKIXPublicKey(public)
		publicKey, ok := parsedPublic.(*ecdsa.PublicKey)
		if parseErr != nil || !ok || !publicKey.Equal(&key.PublicKey) {
			return nil, errors.New("identity key pair does not match")
		}
		return &fileIdentity{deviceID: stored.DeviceID, private: key, public: public}, nil
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
	public, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, err
	}
	random := make([]byte, 16)
	if _, err = rand.Read(random); err != nil {
		return nil, err
	}
	stored := identityFile{
		Version: 2, AppID: appID,
		DeviceID:   base64.RawURLEncoding.EncodeToString(random),
		PrivateKey: base64.RawURLEncoding.EncodeToString(private),
		PublicKey:  base64.RawURLEncoding.EncodeToString(public),
	}
	encoded, err := json.Marshal(stored)
	if err != nil {
		return nil, err
	}
	directory := filepath.Dir(s.Path)
	if err = os.MkdirAll(directory, 0700); err != nil {
		return nil, err
	}
	if err = os.Chmod(directory, 0700); err != nil {
		return nil, err
	}
	temporary, err := os.CreateTemp(directory, filepath.Base(s.Path)+".*.tmp")
	if err != nil {
		return nil, err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err = temporary.Chmod(0600); err == nil {
		_, err = temporary.Write(encoded)
	}
	if err == nil {
		err = temporary.Sync()
	}
	if closeErr := temporary.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return nil, err
	}
	if err = os.Rename(temporaryPath, s.Path); err != nil {
		return nil, fmt.Errorf("atomically install identity: %w", err)
	}
	if err = os.Chmod(s.Path, 0600); err != nil {
		return nil, err
	}
	return &fileIdentity{deviceID: stored.DeviceID, private: key, public: public}, nil
}
