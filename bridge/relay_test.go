package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// gameServer is a stand-in for the session's server: it greets with RoomInfo, answers a Connect with the slot's
// password with Connected (else ConnectionRefused), and echoes everything else; seen gets each Connect.
func gameServer(t *testing.T, password string, seen chan<- map[string]any) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer c.CloseNow()
		ctx := r.Context()
		_ = c.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"RoomInfo"}]`))
		for {
			_, data, err := c.Read(ctx)
			if err != nil {
				return
			}
			var packets []map[string]any
			_ = json.Unmarshal(data, &packets)
			if len(packets) > 0 && packets[0]["cmd"] == "Connect" {
				seen <- packets[0]
				if packets[0]["password"] == password {
					_ = c.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"Connected","slot":1}]`))
				} else {
					_ = c.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"ConnectionRefused","errors":["InvalidPassword"]}]`))
				}
				continue
			}
			_ = c.Write(ctx, websocket.MessageText, data)
		}
	}))
}

func wsURL(s *httptest.Server) string { return "ws" + strings.TrimPrefix(s.URL, "http") }

func read(t *testing.T, ctx context.Context, c *websocket.Conn) string {
	t.Helper()
	_, data, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func waitFor(t *testing.T, r *Relay, state string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for r.View().State != state {
		if time.Now().After(deadline) {
			t.Fatalf("state %s, not %s", r.View().State, state)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestRelayFillsInTheSlotAndPassesEverything(t *testing.T) {
	seen := make(chan map[string]any, 1)
	server := gameServer(t, "s3cret", seen)
	defer server.Close()
	relay, err := startRelay("1", "brave-otter", "Brave Otter", Slot{Name: "Alice-LttP", Game: "A Link to the Past", Password: "s3cret"}, wsURL(server), nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	if relay.View().State != StateWaiting {
		t.Fatalf("state %s", relay.View().State)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	game, _, err := websocket.Dial(ctx, fmt.Sprintf("ws://localhost:%d", relay.Port), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer game.CloseNow()
	if got := read(t, ctx, game); got != `[{"cmd":"RoomInfo"}]` {
		t.Fatalf("greeting %s", got)
	}
	// Typed into the game: any name, no password
	_ = game.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"Connect","name":"me","password":"","game":"A Link to the Past"}]`))
	connect := <-seen
	if connect["name"] != "Alice-LttP" || connect["password"] != "s3cret" || connect["game"] != "A Link to the Past" {
		t.Fatalf("the server got %v", connect)
	}
	if got := read(t, ctx, game); !strings.Contains(got, "Connected") {
		t.Fatalf("reply %s", got)
	}
	waitFor(t, relay, StateConnected)
	_ = game.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"Say","text":"hello"}]`))
	if got := read(t, ctx, game); got != `[{"cmd":"Say","text":"hello"}]` {
		t.Fatalf("echo %s", got)
	}
	_ = game.Close(websocket.StatusNormalClosure, "")
	waitFor(t, relay, StateWaiting)
}

func TestRelayShowsARefusalAndFetchesThePasswordAgain(t *testing.T) {
	seen := make(chan map[string]any, 2)
	server := gameServer(t, "new", seen)
	defer server.Close()
	refreshed := make(chan bool, 1)
	refresh := func() (Slot, string, error) {
		refreshed <- true
		return Slot{Name: "Alice", Game: "Game", Password: "new"}, wsURL(server), nil
	}
	relay, _ := startRelay("1", "s", "S", Slot{Name: "Alice", Game: "Game", Password: "old"}, wsURL(server), refresh, 0)
	defer relay.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	game, _, _ := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d", relay.Port), nil)
	read(t, ctx, game)
	_ = game.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"Connect","name":"x"}]`))
	<-seen
	read(t, ctx, game)
	waitFor(t, relay, StateError)
	if relay.View().Detail != "InvalidPassword" {
		t.Fatalf("detail %q", relay.View().Detail)
	}
	<-refreshed
	game.CloseNow()

	// The next game connection gets in with the new password
	time.Sleep(50 * time.Millisecond)
	again, _, _ := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d", relay.Port), nil)
	defer again.CloseNow()
	read(t, ctx, again)
	_ = again.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"Connect","name":"x"}]`))
	if got := <-seen; got["password"] != "new" {
		t.Fatalf("password %v", got["password"])
	}
	waitFor(t, relay, StateConnected)
}

func TestRelayRefusesWebPages(t *testing.T) {
	relay, _ := startRelay("1", "s", "S", Slot{Name: "Alice", Game: "Game", Password: "pw"}, "ws://127.0.0.1:1", nil, 0)
	defer relay.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, resp, err := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d", relay.Port), &websocket.DialOptions{
		HTTPHeader: http.Header{"Origin": {"https://evil.example"}},
	})
	if err == nil {
		t.Fatal("a web page got in")
	}
	if resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("answer %v", resp)
	}
}

func TestRelayWhenTheServerIsUnreachable(t *testing.T) {
	relay, _ := startRelay("1", "s", "S", Slot{Name: "Alice", Game: "Game", Password: "pw"}, "ws://127.0.0.1:1", nil, 0)
	defer relay.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	game, _, err := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d", relay.Port), nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := game.Read(ctx); websocket.CloseStatus(err) != websocket.StatusTryAgainLater {
		t.Fatalf("closed with %v", err)
	}
	waitFor(t, relay, StateError)
}

func TestClosingARelayEndsItsGameConnections(t *testing.T) {
	ended := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer c.CloseNow()
		ctx := r.Context()
		_ = c.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"RoomInfo"}]`))
		for {
			_, data, err := c.Read(ctx)
			if err != nil {
				close(ended)
				return
			}
			if strings.Contains(string(data), `"Connect"`) {
				_ = c.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"Connected","slot":1}]`))
			}
		}
	}))
	defer server.Close()
	relay, err := startRelay("1", "s", "S", Slot{Name: "Alice", Game: "Game", Password: "pw"}, wsURL(server), nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	game, _, err := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d", relay.Port), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer game.CloseNow()
	read(t, ctx, game)
	_ = game.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"Connect","name":"x"}]`))
	read(t, ctx, game)
	waitFor(t, relay, StateConnected)

	// ✕, a logout or a key that's gone: the game is out of the slot at once
	relay.Close()
	readCtx, readCancel := context.WithTimeout(ctx, time.Second)
	defer readCancel()
	if _, _, err := game.Read(readCtx); websocket.CloseStatus(err) != websocket.StatusGoingAway {
		t.Fatalf("the game: %v", err)
	}
	select {
	case <-ended:
	case <-time.After(time.Second):
		t.Fatal("the server's connection stayed")
	}
}

func TestTheServersCloseReachesTheGame(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		_ = c.Write(r.Context(), websocket.MessageText, []byte(`[{"cmd":"RoomInfo"}]`))
		_ = c.Close(websocket.StatusTryAgainLater, "Session x isn't running")
	}))
	defer server.Close()
	relay, _ := startRelay("1", "s", "S", Slot{Name: "Alice", Game: "Game", Password: "pw"}, wsURL(server), nil, 0)
	defer relay.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	for try := 0; try < 20; try++ {
		game, _, err := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d", relay.Port), nil)
		if err != nil {
			t.Fatal(err)
		}
		read(t, ctx, game)
		_, _, err = game.Read(ctx)
		var closed websocket.CloseError
		if !errors.As(err, &closed) || closed.Code != websocket.StatusTryAgainLater || closed.Reason != "Session x isn't running" {
			t.Fatalf("try %d: %v", try, err)
		}
		game.CloseNow()
	}
}

func TestRelayFetchesThePasswordOnceForManyRefusals(t *testing.T) {
	seen := make(chan map[string]any, 20)
	server := gameServer(t, "right", seen)
	defer server.Close()
	var refreshes atomic.Int32
	refresh := func() (Slot, string, error) {
		refreshes.Add(1)
		return Slot{Name: "Alice", Game: "Game", Password: "still wrong"}, wsURL(server), nil
	}
	relay, _ := startRelay("1", "s", "S", Slot{Name: "Alice", Game: "Game", Password: "wrong"}, wsURL(server), refresh, 0)
	defer relay.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	// A game that keeps reconnecting while it is refused
	for try := 0; try < 20; try++ {
		game, _, err := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d", relay.Port), nil)
		if err != nil {
			t.Fatal(err)
		}
		read(t, ctx, game)
		_ = game.Write(ctx, websocket.MessageText, []byte(`[{"cmd":"Connect","name":"x"}]`))
		<-seen
		if got := read(t, ctx, game); !strings.Contains(got, "ConnectionRefused") {
			t.Fatalf("reply %s", got)
		}
		game.CloseNow()
	}
	time.Sleep(100 * time.Millisecond)
	if n := refreshes.Load(); n != 1 {
		t.Errorf("fetched %d times", n)
	}
}

func TestARelayCarriesAtMostItsLimit(t *testing.T) {
	seen := make(chan map[string]any, 1)
	server := gameServer(t, "pw", seen)
	defer server.Close()
	relay, _ := startRelay("1", "s", "S", Slot{Name: "Alice", Game: "Game", Password: "pw"}, wsURL(server), nil, 0)
	defer relay.Close()
	relay.note(func() { relay.limit = 1 })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	address := fmt.Sprintf("ws://127.0.0.1:%d", relay.Port)
	first, _, err := websocket.Dial(ctx, address, nil)
	if err != nil {
		t.Fatal(err)
	}
	read(t, ctx, first)
	if _, resp, err := websocket.Dial(ctx, address, nil); err == nil || resp == nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("one too many: %v %v", resp, err)
	}
	// Once it is gone, the next gets in
	first.CloseNow()
	waitFor(t, relay, StateWaiting)
	again, _, err := websocket.Dial(ctx, address, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer again.CloseNow()
	read(t, ctx, again)
}

func TestOnlyThisComputerUnencrypted(t *testing.T) {
	for address, ok := range map[string]bool{
		"wss://friday.randogeddon.eratiem.net":      true,
		"ws://localhost:38281/friday":               true,
		"ws://127.0.0.1:38281/friday":               true,
		"ws://[::1]:38281/friday":                   true,
		"ws://randogeddon.eratiem.net:38281/friday": false,
		"ws://192.168.1.20:38281/friday":            false,
		"https://randogeddon.eratiem.net":           false,
	} {
		if err := checkUpstream(address); (err == nil) != ok {
			t.Errorf("%s: %v", address, err)
		}
	}
	// Nor does a relay dial one
	if _, err := dial(context.Background(), "ws://randogeddon.eratiem.net:38281/friday", websocket.DialOptions{}); err == nil {
		t.Error("dialled unencrypted")
	}
}

func TestUpstreamURL(t *testing.T) {
	for in, want := range map[string]string{
		"friday.randogeddon.eratiem.net:443": "wss://friday.randogeddon.eratiem.net",
		"friday.example:8443":                "wss://friday.example:8443",
		"ws://localhost:38281/friday":        "ws://localhost:38281/friday",
	} {
		if got := upstreamURL(in); got != want {
			t.Errorf("%s: %s", in, got)
		}
	}
}
