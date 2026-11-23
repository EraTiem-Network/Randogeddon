package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func testApp(t *testing.T) (*App, http.Handler) {
	a := newApp(t.TempDir(), "tok123", Config{Server: "https://randogeddon.example"})
	a.port = 4242
	return a, a.Handler()
}

func request(h http.Handler, method, target, host string, prepare func(*http.Request)) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, target, nil)
	r.Host = host
	if prepare != nil {
		prepare(r)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

// post sends [body] to the page's API as the page does.
func post(h http.Handler, target, body string, prepare func(*http.Request)) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodPost, target, strings.NewReader(body))
	r.Host = "127.0.0.1:4242"
	r.Header.Set("Content-Type", "application/json")
	if prepare != nil {
		prepare(r)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func withToken(value string) func(*http.Request) {
	return func(r *http.Request) { r.Header.Set("X-Bridge", value) }
}

// alive opens the page's WebSocket, with [protocols] (the page's: aliveProtocol and its token).
func alive(ctx context.Context, app *App, header http.Header, protocols ...string) (*websocket.Conn, error) {
	c, _, err := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d/api/alive", app.port), &websocket.DialOptions{
		HTTPHeader: header, Subprotocols: protocols,
	})
	return c, err
}

// keyGoneAnswer is the dashboard's answer to a key it no longer takes.
func keyGoneAnswer(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	fmt.Fprint(w, `{"message":"This key has ended or expired","code":"BRIDGE_KEY_GONE"}`)
}

func TestThePageIsOnlyForItsOwnAddress(t *testing.T) {
	_, h := testApp(t)
	// Another name for 127.0.0.1 (DNS rebinding) or another port
	for _, host := range []string{"evil.example:4242", "127.0.0.1:80", "localhost"} {
		if w := request(h, "GET", "/api/state", host, withToken("tok123")); w.Code != http.StatusMisdirectedRequest {
			t.Errorf("%s: %d", host, w.Code)
		}
	}
	for _, host := range []string{"127.0.0.1:4242", "localhost:4242"} {
		if w := request(h, "GET", "/api/state", host, withToken("tok123")); w.Code != http.StatusOK {
			t.Errorf("%s: %d", host, w.Code)
		}
	}
}

func TestThePageNeedsItsToken(t *testing.T) {
	a, h := testApp(t)
	for name, prepare := range map[string]func(*http.Request){
		"without the token": nil,
		"a wrong token":     withToken("guess"),
		"the old header":    withToken("1"),
	} {
		if w := request(h, "GET", "/api/state", "127.0.0.1:4242", prepare); w.Code != http.StatusForbidden {
			t.Errorf("%s: %d", name, w.Code)
		}
	}
	// A cookie would reach every server on 127.0.0.1: it isn't taken
	cookie := func(r *http.Request) { r.AddCookie(&http.Cookie{Name: "randogeddon_bridge", Value: "tok123"}) }
	if w := request(h, "GET", "/api/state", "127.0.0.1:4242", cookie); w.Code != http.StatusForbidden {
		t.Errorf("the token as a cookie: %d", w.Code)
	}
	if w := request(h, "GET", "/api/state", "127.0.0.1:4242", withToken("tok123")); w.Code != http.StatusOK {
		t.Errorf("with the token: %d", w.Code)
	}
	// The page itself holds nothing to keep (it needs the token for everything else) and sets no cookie
	w := request(h, "GET", "/?c=whatever", "127.0.0.1:4242", nil)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "Randogeddon") || w.Header().Get("Set-Cookie") != "" {
		t.Errorf("the page: %d %s", w.Code, w.Header().Get("Set-Cookie"))
	}
	// Its link's code gets the token, once
	code := strings.TrimPrefix(a.newLink(), "http://127.0.0.1:4242/?c=")
	exchange := func(code string) *httptest.ResponseRecorder {
		return post(h, "/api/exchange", `{"code":"`+code+`"}`, withToken("1"))
	}
	if w := exchange(code); w.Code != http.StatusOK || !strings.Contains(w.Body.String(), `"token":"tok123"`) {
		t.Errorf("the code: %d %s", w.Code, w.Body.String())
	}
	if w := exchange(code); w.Code != http.StatusForbidden {
		t.Errorf("the code a second time: %d", w.Code)
	}
	if w := exchange("guess"); w.Code != http.StatusForbidden {
		t.Errorf("a wrong code: %d", w.Code)
	}
	// Not from another site's page
	other := a.newLink()
	if w := post(h, "/api/exchange", `{"code":"`+strings.TrimPrefix(other, "http://127.0.0.1:4242/?c=")+`"}`, nil); w.Code != http.StatusForbidden {
		t.Errorf("without the header: %d", w.Code)
	}
	// The ping is open: a second start asks whether this one runs
	if w := request(h, "GET", "/ping", "127.0.0.1:4242", nil); w.Body.String() != "randogeddon-bridge" {
		t.Errorf("ping: %s", w.Body.String())
	}
}

func TestChangesNeedThePagesHeader(t *testing.T) {
	_, h := testApp(t)
	quit := func(prepare func(*http.Request)) int {
		return request(h, "POST", "/api/quit", "127.0.0.1:4242", prepare).Code
	}
	if code := quit(func(*http.Request) {}); code != http.StatusForbidden {
		t.Errorf("without the header: %d", code)
	}
	if code := quit(func(r *http.Request) {
		r.Header.Set("X-Bridge", "tok123")
		r.Header.Set("Origin", "https://evil.example")
	}); code != http.StatusForbidden {
		t.Errorf("from another site: %d", code)
	}
	if code := quit(func(r *http.Request) {
		r.Header.Set("X-Bridge", "tok123")
		r.Header.Set("Origin", "http://127.0.0.1:4242")
	}); code != http.StatusNoContent {
		t.Errorf("from the page: %d", code)
	}
}

func TestTooLargeABodyIsRefused(t *testing.T) {
	_, h := testApp(t)
	body := `{"key":"` + strings.Repeat("a", 1<<20) + `"}`
	if w := post(h, "/api/login", body, withToken("tok123")); w.Code != http.StatusBadRequest {
		t.Errorf("a login of 1 MB: %d", w.Code)
	}
}

func TestTheDashboardOnlyOverHTTPS(t *testing.T) {
	for server, ok := range map[string]bool{
		"https://randogeddon.eratiem.net": true,
		"http://localhost:8000":           true,
		"http://127.0.0.1:8000":           true,
		"http://[::1]:8000":               true,
		"http://randogeddon.eratiem.net":  false,
		"http://192.168.1.20:8080":        false,
		"ftp://example":                   false,
		"randogeddon.eratiem.net":         false,
	} {
		if err := checkServer(server); (err == nil) != ok {
			t.Errorf("%s: %v", server, err)
		}
	}
}

func TestNewer(t *testing.T) {
	cases := []struct {
		a, b string
		want bool
	}{
		{"0.12.3", "0.12.2", true}, {"0.12.2", "0.12.2", false}, {"0.12.2.1", "0.12.2", true}, {"0.12.2", "0.12.2.1", false},
		{"1.0.0", "0.13.4", true}, {"0.12.3", "dev", false}, {"0.12.3", "0.12.3-SNAPSHOT", false},
	}
	for _, c := range cases {
		if got := newer(c.a, c.b); got != c.want {
			t.Errorf("newer(%s, %s) = %v", c.a, c.b, got)
		}
	}
}

func TestTheUpdateLinksTheBuildTheDashboardOffersForThisSystem(t *testing.T) {
	defer func(v string) { version = v }(version)
	version = "0.23.2"
	a, h := testApp(t)
	offered := "0.23.3"
	system := runtime.GOOS
	if system == "darwin" {
		system = "macos"
	}
	file := "randogeddon-bridge-0.23.3-" + system + "-" + runtime.GOARCH
	state := func() State {
		var s State
		_ = json.Unmarshal(request(h, "GET", "/api/state", "127.0.0.1:4242", withToken("tok123")).Body.Bytes(), &s)
		return s
	}
	a.info = &Info{Version: &offered, Downloads: []Download{{OS: "plan9", Arch: "mips", File: "other"}, {OS: system, Arch: runtime.GOARCH, File: file}}}
	if s := state(); s.Update != offered || s.UpdateURL != "https://randogeddon.example/downloads/bridge/"+file {
		t.Errorf("update %s at %s", s.Update, s.UpdateURL)
	}
	// None for this system: the news without a link
	a.info = &Info{Version: &offered, Downloads: []Download{{OS: "plan9", Arch: "mips", File: "other"}}}
	if s := state(); s.Update != offered || s.UpdateURL != "" {
		t.Errorf("update %s at %s", s.Update, s.UpdateURL)
	}
}

func TestOnlyTheDashboardsOwnAnswerEndsTheKey(t *testing.T) {
	var answer, calls atomic.Int32
	dashboard := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		switch answer.Load() {
		case 0:
			_ = json.NewEncoder(w).Encode(Info{Name: "Alice", Mode: "dark", Dark: map[string]string{"text": "#eeeeee"}})
		case 1: // something in front of the dashboard
			w.WriteHeader(http.StatusUnauthorized)
		case 2: // an outage
			w.WriteHeader(http.StatusBadGateway)
		default:
			keyGoneAnswer(w)
		}
	}))
	defer dashboard.Close()
	a := newApp(t.TempDir(), "tok123", Config{Server: dashboard.URL, Key: "rgb_k"})
	// One call per check: who it is and how they see Randogeddon
	a.check()
	if calls.Load() != 1 || a.me == nil || a.me.Name != "Alice" {
		t.Fatalf("%d calls, %+v", calls.Load(), a.me)
	}
	// A 401 that isn't the dashboard's, an outage: the key stays, and what the page shows
	for _, step := range []int32{1, 2} {
		answer.Store(step)
		a.check()
		if a.cfg.Key != "rgb_k" || a.me == nil || a.info == nil || a.info.Mode != "dark" {
			t.Errorf("after answer %d: key %q, %+v, %+v", step, a.cfg.Key, a.me, a.info)
		}
	}
	// The dashboard says the key is gone
	answer.Store(3)
	a.check()
	if a.cfg.Key != "" || a.me != nil || a.problem == "" {
		t.Errorf("still logged in: %q %+v", a.cfg.Key, a.me)
	}
}

func TestALateAnswerDoesntLogOutANewLogin(t *testing.T) {
	release := make(chan struct{})
	dashboard := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") == "Bearer new" {
			_ = json.NewEncoder(w).Encode(Info{Name: "Alice"})
			return
		}
		// The old key's answer comes after the new login
		<-release
		keyGoneAnswer(w)
	}))
	defer dashboard.Close()
	released := false
	defer func() {
		if !released {
			close(release)
		}
	}()
	dir := t.TempDir()
	a := newApp(dir, "tok123", Config{Server: dashboard.URL, Key: "old"})
	a.port = 4242
	h := a.Handler()
	checked := make(chan struct{})
	go func() { a.check(); close(checked) }()
	time.Sleep(50 * time.Millisecond)
	if w := post(h, "/api/login", `{"key":"new"}`, withToken("tok123")); w.Code != http.StatusNoContent {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	close(release)
	released = true
	<-checked
	if cfg := loadConfig(dir); cfg.Key != "new" {
		t.Errorf("kept %+v", cfg)
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.me == nil || a.cfg.Key != "new" {
		t.Errorf("logged out: %q %+v", a.cfg.Key, a.me)
	}
}

func TestTheBridgeQuitsOnceItsPageIsClosed(t *testing.T) {
	app, _ := testApp(t)
	app.pageGrace = 100 * time.Millisecond
	server := httptest.NewServer(nil)
	defer server.Close()
	app.port = server.Listener.Addr().(*net.TCPAddr).Port
	server.Config.Handler = app.Handler()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	open := func() *websocket.Conn {
		c, err := alive(ctx, app, nil, aliveProtocol, app.token)
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	// A reload: closed and back within the grace
	first := open()
	_ = first.Close(websocket.StatusNormalClosure, "")
	time.Sleep(20 * time.Millisecond)
	second := open()
	time.Sleep(250 * time.Millisecond)
	select {
	case <-app.quit:
		t.Fatal("quit while a page was open")
	default:
	}
	// The tab closed
	_ = second.Close(websocket.StatusNormalClosure, "")
	select {
	case <-app.quit:
	case <-time.After(2 * time.Second):
		t.Fatal("still running without a page")
	}
}

func TestTheBridgeStaysWhileAGameIsConnected(t *testing.T) {
	seen := make(chan map[string]any, 1)
	game := gameServer(t, "pw", seen)
	defer game.Close()
	app, _ := testApp(t)
	app.pageGrace = 100 * time.Millisecond
	page := httptest.NewServer(nil)
	defer page.Close()
	app.port = page.Listener.Addr().(*net.TCPAddr).Port
	page.Config.Handler = app.Handler()
	relay, err := startRelay("1", "s", "S", Slot{Name: "Alice", Game: "Game", Password: "pw"}, wsURL(game), nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	app.mu.Lock()
	app.relays = []*Relay{relay}
	app.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, _, err := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d", relay.Port), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer client.CloseNow()
	read(t, ctx, client)
	waitFor(t, relay, StateConnecting)

	// The page closed (or the browser discarded it): the game plays on
	tab, err := alive(ctx, app, nil, aliveProtocol, app.token)
	if err != nil {
		t.Fatal(err)
	}
	_ = tab.Close(websocket.StatusNormalClosure, "")
	time.Sleep(350 * time.Millisecond)
	select {
	case <-app.quit:
		t.Fatal("quit while a game was connected")
	default:
	}
	// The last game left: the bridge quits
	_ = client.Close(websocket.StatusNormalClosure, "")
	select {
	case <-app.quit:
	case <-time.After(3 * time.Second):
		t.Fatal("still running after the game left")
	}
}

func TestAnotherSitesPageCantKeepItAlive(t *testing.T) {
	app, _ := testApp(t)
	server := httptest.NewServer(nil)
	defer server.Close()
	app.port = server.Listener.Addr().(*net.TCPAddr).Port
	server.Config.Handler = app.Handler()
	header := http.Header{}
	header.Set("Origin", "http://evil.example")
	if _, err := alive(context.Background(), app, header, aliveProtocol, app.token); err == nil {
		t.Fatal("another site's page got in")
	}
	// Nor anything without the page's token
	if _, err := alive(context.Background(), app, nil, aliveProtocol); err == nil {
		t.Fatal("got in without the token")
	}
}

func TestThePageGetsTheAccountsFontFromTheBridge(t *testing.T) {
	fetched := 0
	dashboard := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fetched++
		if r.URL.Path != "/fonts/nes-regular.ttf" || r.Header.Get("Authorization") != "" {
			http.NotFound(w, r)
			return
		}
		fmt.Fprint(w, "the font")
	}))
	defer dashboard.Close()
	a := newApp(t.TempDir(), "tok123", Config{Server: dashboard.URL, Key: "rgb_secret"})
	a.port = 4242
	h := a.Handler()
	get := func(path string) *httptest.ResponseRecorder {
		return request(h, "GET", path, "127.0.0.1:4242", withToken("tok123"))
	}
	// No font with the account: nothing is asked of the dashboard
	if w := get("/font/regular"); w.Code != http.StatusNotFound || fetched != 0 {
		t.Errorf("without a font: %d, %d fetched", w.Code, fetched)
	}
	a.info = &Info{Font: &Font{Regular: "fonts/nes-regular.ttf", HeadingsOnly: true, Scale: 0.75}}
	// Fetched once (without the key: it is a public file), then from the bridge's own memory
	for i := 0; i < 2; i++ {
		if w := get("/font/regular?f=x"); w.Code != http.StatusOK || w.Body.String() != "the font" || w.Header().Get("Content-Type") != "font/ttf" {
			t.Errorf("the font: %d %q", w.Code, w.Body.String())
		}
	}
	if fetched != 1 {
		t.Errorf("fetched %d times", fetched)
	}
	// It has no bold; and only a font's file is ever fetched, whatever the dashboard names
	if w := get("/font/bold"); w.Code != http.StatusNotFound {
		t.Errorf("a bold that isn't there: %d", w.Code)
	}
	for _, path := range []string{"../.env", "api/me", "fonts/../api/me", "fonts/licences/x.txt", "https://evil.example/fonts/a-regular.ttf"} {
		a.info = &Info{Font: &Font{Regular: path}}
		if w := get("/font/regular"); w.Code != http.StatusNotFound {
			t.Errorf("%s: %d", path, w.Code)
		}
	}
	if fetched != 1 {
		t.Errorf("fetched %d times after the wrong paths", fetched)
	}
	// The page needs its token for it, like for everything
	if w := request(h, "GET", "/font/regular", "127.0.0.1:4242", nil); w.Code != http.StatusForbidden {
		t.Errorf("without the token: %d", w.Code)
	}
}

func TestTheBridgeKeepsOnlyTheCurrentFont(t *testing.T) {
	dashboard := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, r.URL.Path) }))
	defer dashboard.Close()
	a := newApp(t.TempDir(), "tok123", Config{Server: dashboard.URL})
	a.port = 4242
	h := a.Handler()
	for _, name := range []string{"one", "two", "three"} {
		a.info = &Info{Font: &Font{Regular: "fonts/" + name + "-regular.ttf"}}
		if w := request(h, "GET", "/font/regular", "127.0.0.1:4242", withToken("tok123")); w.Body.String() != "/fonts/"+name+"-regular.ttf" {
			t.Errorf("%s: %q", name, w.Body.String())
		}
	}
	if len(a.fonts) != 1 {
		t.Errorf("%d fonts kept", len(a.fonts))
	}
}

func TestOneBridgeRunsAtATime(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bridge.lock")
	// The first start takes the lock
	if _, ok := claimLock(path, "http://127.0.0.1:1/?t=a"); !ok {
		t.Fatal("no lock")
	}
	// Its bridge doesn't answer (it ended without removing the lock): taken over
	if _, ok := claimLock(path, "http://127.0.0.1:2/?t=b"); !ok {
		t.Fatal("a stale lock kept")
	}
	if data, _ := os.ReadFile(path); string(data) != "http://127.0.0.1:2/?t=b" {
		t.Fatalf("lock %s", data)
	}
	removeLock(path, "http://127.0.0.1:2/?t=b")

	// A bridge that runs: a second start opens its page, with a link of its own (a code, not the token)
	app, _ := testApp(t)
	page := httptest.NewServer(nil)
	defer page.Close()
	app.port = page.Listener.Addr().(*net.TCPAddr).Port
	page.Config.Handler = app.Handler()
	if _, ok := claimLock(path, app.openURL()); !ok {
		t.Fatal("no lock")
	}
	other, ok := claimLock(path, "http://127.0.0.1:3/?t=c")
	if ok || !strings.HasPrefix(other, fmt.Sprintf("http://127.0.0.1:%d/?c=", app.port)) {
		t.Fatalf("a second bridge: %v, %s", ok, other)
	}
	// Only the bridge that holds the lock removes it
	removeLock(path, "http://127.0.0.1:3/?t=c")
	if _, err := os.Stat(path); err != nil {
		t.Errorf("removed by another start: %v", err)
	}
	removeLock(path, app.openURL())
	if _, err := os.Stat(path); err == nil {
		t.Error("still there")
	}
}
