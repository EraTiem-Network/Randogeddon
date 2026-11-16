package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
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

func withCookie(value string) func(*http.Request) {
	return func(r *http.Request) { r.AddCookie(&http.Cookie{Name: tokenCookie, Value: value}) }
}

func TestThePageIsOnlyForItsOwnAddress(t *testing.T) {
	_, h := testApp(t)
	// Another name for 127.0.0.1 (DNS rebinding) or another port
	for _, host := range []string{"evil.example:4242", "127.0.0.1:80", "localhost"} {
		if w := request(h, "GET", "/api/state", host, withCookie("tok123")); w.Code != http.StatusMisdirectedRequest {
			t.Errorf("%s: %d", host, w.Code)
		}
	}
	for _, host := range []string{"127.0.0.1:4242", "localhost:4242"} {
		if w := request(h, "GET", "/api/state", host, withCookie("tok123")); w.Code != http.StatusOK {
			t.Errorf("%s: %d", host, w.Code)
		}
	}
}

func TestThePageNeedsItsToken(t *testing.T) {
	_, h := testApp(t)
	if w := request(h, "GET", "/api/state", "127.0.0.1:4242", nil); w.Code != http.StatusForbidden {
		t.Errorf("without the token: %d", w.Code)
	}
	if w := request(h, "GET", "/api/state", "127.0.0.1:4242", withCookie("guess")); w.Code != http.StatusForbidden {
		t.Errorf("a wrong token: %d", w.Code)
	}
	if w := request(h, "GET", "/?t=guess", "127.0.0.1:4242", nil); w.Code != http.StatusForbidden {
		t.Errorf("a wrong link: %d", w.Code)
	}
	// The link sets the cookie (HttpOnly, SameSite=Strict) and leads to the page
	w := request(h, "GET", "/?t=tok123", "127.0.0.1:4242", nil)
	cookie := w.Header().Get("Set-Cookie")
	if w.Code != http.StatusSeeOther || !strings.Contains(cookie, "tok123") || !strings.Contains(cookie, "HttpOnly") ||
		!strings.Contains(cookie, "SameSite=Strict") {
		t.Errorf("the link: %d %s", w.Code, cookie)
	}
	if w := request(h, "GET", "/", "127.0.0.1:4242", withCookie("tok123")); w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "Randogeddon") {
		t.Errorf("the page: %d", w.Code)
	}
	// The ping is open: a second start asks whether this one runs
	if w := request(h, "GET", "/ping", "127.0.0.1:4242", nil); w.Body.String() != "randogeddon-bridge" {
		t.Errorf("ping: %s", w.Body.String())
	}
}

func TestChangesNeedThePagesHeader(t *testing.T) {
	_, h := testApp(t)
	quit := func(prepare func(*http.Request)) int {
		return request(h, "POST", "/api/quit", "127.0.0.1:4242", func(r *http.Request) {
			withCookie("tok123")(r)
			prepare(r)
		}).Code
	}
	if code := quit(func(*http.Request) {}); code != http.StatusForbidden {
		t.Errorf("without the header: %d", code)
	}
	if code := quit(func(r *http.Request) { r.Header.Set("X-Bridge", "1"); r.Header.Set("Origin", "https://evil.example") }); code != http.StatusForbidden {
		t.Errorf("from another site: %d", code)
	}
	if code := quit(func(r *http.Request) { r.Header.Set("X-Bridge", "1"); r.Header.Set("Origin", "http://127.0.0.1:4242") }); code != http.StatusNoContent {
		t.Errorf("from the page: %d", code)
	}
}

func TestTheDashboardOnlyOverHTTPS(t *testing.T) {
	for server, ok := range map[string]bool{
		"https://randogeddon.eratiem.net": true,
		"http://localhost:8000":           true,
		"http://127.0.0.1:8000":           true,
		"http://randogeddon.eratiem.net":  false,
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

func TestBuildName(t *testing.T) {
	if got := buildName("windows", "amd64"); got != "randogeddon-bridge-windows-amd64.exe" {
		t.Error(got)
	}
	if got := buildName("darwin", "arm64"); got != "randogeddon-bridge-macos-arm64" {
		t.Error(got)
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
		header := http.Header{}
		header.Set("Cookie", tokenCookie+"="+app.token)
		c, _, err := websocket.Dial(ctx, fmt.Sprintf("ws://127.0.0.1:%d/api/alive", app.port), &websocket.DialOptions{HTTPHeader: header})
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

func TestAnotherSitesPageCantKeepItAlive(t *testing.T) {
	app, _ := testApp(t)
	server := httptest.NewServer(nil)
	defer server.Close()
	app.port = server.Listener.Addr().(*net.TCPAddr).Port
	server.Config.Handler = app.Handler()
	header := http.Header{}
	header.Set("Cookie", tokenCookie+"="+app.token)
	header.Set("Origin", "http://evil.example")
	_, _, err := websocket.Dial(context.Background(), fmt.Sprintf("ws://127.0.0.1:%d/api/alive", app.port), &websocket.DialOptions{HTTPHeader: header})
	if err == nil {
		t.Fatal("another site's page got in")
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
		return request(h, "GET", path, "127.0.0.1:4242", withCookie("tok123"))
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
