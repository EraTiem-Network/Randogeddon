package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// gatewayTunnel is a stand-in for the gateway's end of a tunnel: it remembers the path and the tunnel key header and
// echoes every binary message.
func gatewayTunnel(seen chan<- [2]string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen <- [2]string{r.URL.EscapedPath(), r.Header.Get("X-Randogeddon-Tunnel-Key")}
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer c.CloseNow()
		for {
			typ, data, err := c.Read(r.Context())
			if err != nil {
				return
			}
			if typ == websocket.MessageBinary {
				_ = c.Write(r.Context(), websocket.MessageBinary, data)
			}
		}
	}))
}

func TestTunnelPassesMinecraftsBytesWithTheSlotsKey(t *testing.T) {
	seen := make(chan [2]string, 1)
	server := gatewayTunnel(seen)
	defer server.Close()
	relay, err := startRelay("1", "brave-otter", "Brave Otter", Slot{Name: "Erin Dig", Game: "Minecraft Dig", Tunnel: "k3y"}, wsURL(server)+"/brave-otter", nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()

	game, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", relay.Port), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer game.Close()
	_ = game.SetDeadline(time.Now().Add(5 * time.Second))
	hello := []byte{0x10, 0x00, 0xfa, 0x05, 0x09}
	if _, err := game.Write(hello); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(hello))
	if _, err := io.ReadFull(game, got); err != nil || string(got) != string(hello) {
		t.Fatalf("echo %v (%v)", got, err)
	}
	if s := <-seen; s[0] != "/brave-otter/minecraft/Erin%20Dig" || s[1] != "k3y" {
		t.Fatalf("the gateway got %v", s)
	}
	waitFor(t, relay, StateConnected)

	// Closing the relay ends the Minecraft connection too
	relay.Close()
	if _, err := game.Read(got); err == nil {
		t.Fatal("still open after the relay closed")
	}
}

func TestTunnelWhenTheGatewayIsUnreachable(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	address := ln.Addr().String()
	_ = ln.Close()
	relay, err := startRelay("1", "brave-otter", "Brave Otter", Slot{Name: "Erin-Dig", Game: "Minecraft Dig", Tunnel: "x"}, "ws://"+address, nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	game, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", relay.Port))
	if err != nil {
		t.Fatal(err)
	}
	defer game.Close()
	_ = game.SetDeadline(time.Now().Add(25 * time.Second))
	if _, err := game.Read(make([]byte, 1)); err == nil {
		t.Fatal("not closed")
	}
	waitFor(t, relay, StateError)
	_ = context.Background()
}

func TestTunnelURL(t *testing.T) {
	for upstream, want := range map[string]string{
		"wss://friday.example.org":     "wss://friday.example.org/minecraft/Erin-Dig",
		"ws://localhost:38281/friday/": "ws://localhost:38281/friday/minecraft/Erin-Dig",
	} {
		if got := tunnelURL(upstream, "Erin-Dig"); got != want {
			t.Errorf("%s: %s", upstream, got)
		}
	}
}
