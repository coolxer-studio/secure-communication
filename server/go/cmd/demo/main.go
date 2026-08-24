package main

import (
	"io"
	"log"
	"net/http"
	"os"
	"strings"

	sc "github.com/coolxer/secure-communication-server-go"
	"github.com/coolxer/secure-communication-server-go/httpadapter"
)

func main() {
	c := sc.DefaultConfig()
	c.Enabled = strings.EqualFold(os.Getenv("SC_ENABLED"), "true")
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/ping", func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		_, _ = w.Write([]byte("server received: scid->" + r.Header.Get("scid") + ",strBody->" + string(b)))
	})
	handler := httpadapter.New(c, nil, nil, sc.RejectingRoutes{}, mux)
	log.Printf("Go demo listening on http://127.0.0.1:6789 (secure communication enabled=%v)", c.Enabled)
	log.Fatal(http.ListenAndServe("127.0.0.1:6789", handler))
}
