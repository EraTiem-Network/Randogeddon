package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// dashboard answers the bridge's routes: a session with a slot of the user's, a hosted one with its password (as for
// a Session-Manager), a hosted one without (as for its player), and a locked one.
func dashboard(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("GET /bridge/info", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer good" {
			keyGoneAnswer(w)
			return
		}
		_ = json.NewEncoder(w).Encode(Info{Name: "Alice"})
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
		// Only slots open to all or hosted ones: the connection's slot list says which
		{Session{MyRole: "player"}, true},
		{Session{MyRole: "manager", Status: "finishing"}, true},
		{Session{MyRole: "watcher"}, false},
		{Session{MyRole: "support"}, false},
		// Support or a watcher with slots of their own plays too
		{Session{MySlots: []string{"A"}, MyRole: "support"}, true},
		{Session{MySlots: []string{"A"}, MyRole: "watcher"}, true},
		{Session{MySlots: []string{"A"}, MyRole: "support", Status: "paused"}, false},
		{Session{}, false}, // a key never acts as an Administrator: no session without a part
		{Session{MyRole: "manager", Status: "paused"}, false},
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

func TestARelayIsRefusedAnUnencryptedAddressElsewhere(t *testing.T) {
	for address, ok := range map[string]bool{
		"ws://example.org:38281/friday":      false,
		"ws://127.0.0.1:38281/friday":        true,
		"friday.randogeddon.eratiem.net:443": true,
	} {
		stub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_ = json.NewEncoder(w).Encode(Connection{Server: address, Slots: []Slot{{Name: "Alice", Game: "Game", Password: "pw"}}})
		}))
		_, _, err := slotOf(newClient(stub.URL, "good", ""), "friday", "Alice")
		stub.Close()
		if (err == nil) != ok {
			t.Errorf("%s: %v", address, err)
		}
	}
}

func TestTheKeyGoesOnlyToItsDashboard(t *testing.T) {
	dir := t.TempDir()
	if err := (Config{Server: "https://randogeddon.example", Key: "good"}).save(dir); err != nil {
		t.Fatal(err)
	}
	// Without -server, or with the same address: the saved login
	for _, flagServer := range []string{"", "https://randogeddon.example/"} {
		if cfg, err := resolveConfig(dir, flagServer); err != nil || cfg.Key != "good" || cfg.Server != "https://randogeddon.example" {
			t.Errorf("-server %q: %+v %v", flagServer, cfg, err)
		}
	}
	// Another dashboard: logged out there; it never sees the key, and the saved one stays
	var keys atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.Header.Get("Authorization"), "good") {
			keys.Add(1)
		}
		keyGoneAnswer(w)
	}))
	defer other.Close()
	cfg, err := resolveConfig(dir, other.URL)
	if err != nil || cfg.Key != "" || cfg.Server != other.URL {
		t.Fatalf("another dashboard: %+v %v", cfg, err)
	}
	var out bytes.Buffer
	if code := runCLI(dir, cfg, []string{"slots"}, nil, &out); code == 0 {
		t.Error("slots without a login there")
	}
	if keys.Load() != 0 {
		t.Error("the key went to another dashboard")
	}
	if saved := loadConfig(dir); saved.Key != "good" {
		t.Errorf("the saved login went: %+v", saved)
	}
	// Unencrypted and not on this computer: refused, by -server or by the config itself
	if cfg, err := resolveConfig(dir, "http://randogeddon.example"); err == nil || cfg.Key != "" {
		t.Errorf("-server http://: %+v %v", cfg, err)
	}
	if err := (Config{Server: "http://192.0.2.1", Key: "good"}).save(dir); err != nil {
		t.Fatal(err)
	}
	if cfg, err := resolveConfig(dir, ""); err == nil || cfg.Key != "" {
		t.Errorf("a config with http://: %+v %v", cfg, err)
	}
	if err := newClient("http://192.0.2.1", "good", "").Logout(); err == nil {
		t.Error("a call over http:// to another computer")
	}
}

func TestTheKeyFollowsNoRedirect(t *testing.T) {
	var reached atomic.Int32
	elsewhere := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		reached.Add(1)
		_ = json.NewEncoder(w).Encode(Info{Name: "Mallory"})
	}))
	defer elsewhere.Close()
	redirecting := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, elsewhere.URL+r.URL.Path, http.StatusTemporaryRedirect)
	}))
	defer redirecting.Close()
	if _, err := newClient(redirecting.URL, "good", "").Info(); err == nil {
		t.Error("a redirect was taken")
	}
	if reached.Load() != 0 {
		t.Error("the key went elsewhere")
	}
}

func TestARelayOnAFixedPort(t *testing.T) {
	// A free port, found by a relay on any; between its close and the next start another test can take it: then
	// another one
	var relay *Relay
	var port int
	var err error
	for try := 0; try < 5 && relay == nil; try++ {
		probe, _ := startRelay("1", "s", "S", Slot{Name: "A", Password: "p"}, "ws://127.0.0.1:1", nil, 0)
		port = probe.Port
		probe.Close()
		relay, err = startRelay("2", "s", "S", Slot{Name: "A", Password: "p"}, "ws://127.0.0.1:1", nil, port)
	}
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	if relay.Port != port {
		t.Errorf("port %d, want %d", relay.Port, port)
	}
}
