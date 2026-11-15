package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// dashboard answers the bridge's routes: a session with a slot of the user's, a hosted one with its password (as for
// a Session-Manager), a hosted one without (as for its player), and a locked one.
func dashboard(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("GET /bridge/me", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer good" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_ = json.NewEncoder(w).Encode(Me{Name: "Alice"})
	})
	mux.HandleFunc("GET /bridge/sessions", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode([]Session{
			{ID: "friday", MyRole: "manager"},
			{ID: "old", Status: "finished", MySlots: []string{"Alice-HK"}},
			{ID: "watching", MyRole: "watcher"},
		})
	})
	mux.HandleFunc("GET /bridge/sessions/friday/connection", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(Connection{Server: "friday.example:443", Slots: []Slot{
			{Name: "Alice-HK", Game: "Hollow Knight", Password: "a"},
			{Name: "Stream-SM64", Game: "Super Mario 64", Password: "h", Hosted: true},
			{Name: "Crowd-Celeste", Game: "Celeste", Hosted: true},
			{Name: "Alice-ALBW", Game: "A Link Between Worlds", Locked: true},
		}})
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

func TestUsableSlots(t *testing.T) {
	cases := []struct {
		slot Slot
		want bool
	}{
		{Slot{Password: "a"}, true},
		{Slot{Password: "h", Hosted: true}, true},
		{Slot{Hosted: true}, false},
		{Slot{Locked: true}, false},
		{Slot{Password: "h", Hosted: true, Locked: true}, false},
	}
	for _, c := range cases {
		if got := c.slot.Usable(); got != c.want {
			t.Errorf("%+v: usable %v, want %v", c.slot, got, c.want)
		}
	}
}

func TestOfferedSessions(t *testing.T) {
	cases := []struct {
		session Session
		want    bool
	}{
		{Session{MySlots: []string{"A"}, MyRole: "player"}, true},
		{Session{MyRole: "manager"}, true},
		{Session{}, true}, // an Administrator without a part
		{Session{MyRole: "watcher"}, false},
		{Session{MyRole: "manager", Status: "finished"}, false},
	}
	for _, c := range cases {
		if got := c.session.Offered(); got != c.want {
			t.Errorf("%+v: offered %v, want %v", c.session, got, c.want)
		}
	}
}

func TestTheCommandLineListsTheSlotsItCanOpen(t *testing.T) {
	server := dashboard(t)
	var out bytes.Buffer
	if code := runCLI(t.TempDir(), Config{Server: server.URL, Key: "good"}, []string{"slots"}, nil, &out); code != 0 {
		t.Fatalf("exit %d: %s", code, out.String())
	}
	got := out.String()
	for _, want := range []string{"friday\tAlice-HK\tHollow Knight\n", "friday\tStream-SM64\tSuper Mario 64  (hosted)\n"} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %q in:\n%s", want, got)
		}
	}
	for _, unwanted := range []string{"Crowd-Celeste", "Alice-ALBW", "old", "watching"} {
		if strings.Contains(got, unwanted) {
			t.Errorf("%s listed:\n%s", unwanted, got)
		}
	}
}

func TestTheCommandLineLogsInWithTheKeyFromStandardInput(t *testing.T) {
	server := dashboard(t)
	dir := t.TempDir()
	var out bytes.Buffer
	if code := runCLI(dir, Config{Server: server.URL}, []string{"login"}, strings.NewReader("good\n"), &out); code != 0 {
		t.Fatalf("exit %d", code)
	}
	if cfg := loadConfig(dir); cfg.Key != "good" || cfg.Server != server.URL {
		t.Errorf("kept %+v", cfg)
	}
	if code := runCLI(t.TempDir(), Config{Server: server.URL}, []string{"login"}, strings.NewReader("bad\n"), &out); code == 0 {
		t.Error("a wrong key was taken")
	}
}

func TestARelayOpensAHostedSlotButNotALockedOne(t *testing.T) {
	server := dashboard(t)
	c := newClient(server.URL, "good", "")
	slot, upstream, err := slotOf(c, "friday", "Stream-SM64")
	if err != nil || slot.Password != "h" || upstream != "wss://friday.example" {
		t.Errorf("hosted: %+v %s %v", slot, upstream, err)
	}
	for _, name := range []string{"Alice-ALBW", "Crowd-Celeste"} {
		if _, _, err := slotOf(c, "friday", name); err == nil {
			t.Errorf("%s opened", name)
		}
	}
}

func TestARelayOnAFixedPort(t *testing.T) {
	probe, _ := startRelay("1", "s", "S", Slot{Name: "A", Password: "p"}, "ws://127.0.0.1:1", nil, 0)
	port := probe.Port
	probe.Close()
	relay, err := startRelay("2", "s", "S", Slot{Name: "A", Password: "p"}, "ws://127.0.0.1:1", nil, port)
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	if relay.Port != port {
		t.Errorf("port %d, want %d", relay.Port, port)
	}
}
