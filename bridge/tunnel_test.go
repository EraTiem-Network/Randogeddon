package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
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

func TestARefusedTunnelSaysWhyAndFetchesItsKeyAgain(t *testing.T) {
	seen := make(chan string, 4)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		key := r.Header.Get("X-Randogeddon-Tunnel-Key")
		seen <- key
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer c.CloseNow()
		// The gateway's refusal: accepted, then closed with its reason
		if key != "new" {
			_ = c.Close(websocket.StatusPolicyViolation, "Wrong key, a locked slot, or not hosted here")
			return
		}
		for {
			typ, data, err := c.Read(r.Context())
			if err != nil {
				return
			}
			_ = c.Write(r.Context(), typ, data)
		}
	}))
	defer server.Close()
	refreshed := make(chan bool, 4)
	refresh := func() (Slot, string, error) {
		refreshed <- true
		return Slot{Name: "Erin-Dig", Game: "Minecraft Dig", Tunnel: "new"}, wsURL(server), nil
	}
	relay, err := startRelay("1", "s", "S", Slot{Name: "Erin-Dig", Game: "Minecraft Dig", Tunnel: "old"}, wsURL(server), refresh, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()

	game, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", relay.Port))
	if err != nil {
		t.Fatal(err)
	}
	_ = game.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := game.Read(make([]byte, 1)); err == nil {
		t.Fatal("not closed")
	}
	game.Close()
	waitFor(t, relay, StateError)
	if detail := relay.View().Detail; detail != "Wrong key, a locked slot, or not hosted here" {
		t.Fatalf("detail %q", detail)
	}
	select {
	case <-refreshed:
	case <-time.After(3 * time.Second):
		t.Fatal("the key wasn't fetched again")
	}
	if key := <-seen; key != "old" {
		t.Fatalf("first key %s", key)
	}
	// The next Minecraft connection carries the renewed key and gets through
	deadline := time.Now().Add(3 * time.Second)
	for _, key := relay.target(); key != "new"; _, key = relay.target() {
		if time.Now().After(deadline) {
			t.Fatal("the new key isn't taken")
		}
		time.Sleep(10 * time.Millisecond)
	}
	again, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", relay.Port))
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	_ = again.SetDeadline(time.Now().Add(5 * time.Second))
	hello := []byte{0x10, 0x00}
	if _, err := again.Write(hello); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(again, make([]byte, len(hello))); err != nil {
		t.Fatal(err)
	}
	if key := <-seen; key != "new" {
		t.Fatalf("second key %s", key)
	}
	waitFor(t, relay, StateConnected)
	if len(refreshed) != 0 {
		t.Error("fetched more than once")
	}
}

func TestTunnelFollowsNoRedirect(t *testing.T) {
	var reached atomic.Int32
	elsewhere := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reached.Add(1)
		http.NotFound(w, r)
	}))
	defer elsewhere.Close()
	redirecting := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, elsewhere.URL+r.URL.Path, http.StatusFound)
	}))
	defer redirecting.Close()
	relay, err := startRelay("1", "s", "S", Slot{Name: "Erin-Dig", Game: "Minecraft Dig", Tunnel: "k3y"}, wsURL(redirecting), nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	game, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", relay.Port))
	if err != nil {
		t.Fatal(err)
	}
	defer game.Close()
	_ = game.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := game.Read(make([]byte, 1)); err == nil {
		t.Fatal("not closed")
	}
	waitFor(t, relay, StateError)
	if n := reached.Load(); n != 0 {
		t.Errorf("the tunnel key went elsewhere (%d)", n)
	}
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
