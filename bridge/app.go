package main

import (
	"crypto/subtle"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"net/url"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed ui
var uiFiles embed.FS

// The cookie that holds the page's token: only the link the bridge opened gets in.
const tokenCookie = "randogeddon_bridge"

// App is the bridge while it runs: the login, the relays, and the page on localhost that shows them.
type App struct {
	dir   string
	token string
	port  int
	quit  chan struct{}

	quitOnce sync.Once
	mu       sync.Mutex
	cfg      Config
	me       *Me
	info     *Info
	problem  string
	language string // the page's Accept-Language, so the dashboard words its refusals for the user
	relays   []*Relay
	next     int
}

func newApp(dir, token string, cfg Config) *App {
	return &App{dir: dir, token: token, cfg: cfg, quit: make(chan struct{})}
}

// openURL is the page's link with its token; the page then keeps the token as a cookie.
func (a *App) openURL() string { return fmt.Sprintf("http://127.0.0.1:%d/?t=%s", a.port, a.token) }

func (a *App) client() *Client {
	a.mu.Lock()
	defer a.mu.Unlock()
	return newClient(a.cfg.Server, a.cfg.Key, a.language)
}

// resume logs in with the saved key at the start, and checks every minute that the key still works: once the
// dashboard no longer takes it (ended under Your account, expired), the bridge logs out and closes its relays.
func (a *App) resume() {
	for {
		a.mu.Lock()
		key, known := a.cfg.Key, a.me != nil
		a.mu.Unlock()
		if key != "" {
			c := a.client()
			me, err := c.Me()
			var refusal *APIError
			switch {
			case errors.As(err, &refusal) && refusal.KeyGone():
				a.forget(refusal.Message)
			case err != nil:
				if !known {
					a.setProblem(err.Error())
				}
			default:
				info, _ := c.Info()
				a.mu.Lock()
				a.me, a.info, a.problem = &me, &info, ""
				a.mu.Unlock()
			}
		}
		select {
		case <-a.quit:
			return
		case <-time.After(time.Minute):
		}
	}
}

func (a *App) setProblem(problem string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.problem = problem
}

// forget logs out here: the key goes from the config, the relays close.
func (a *App) forget(problem string) {
	a.mu.Lock()
	relays := a.relays
	a.relays, a.me, a.info, a.problem = nil, nil, nil, problem
	a.cfg.Key = ""
	cfg := a.cfg
	a.mu.Unlock()
	for _, r := range relays {
		r.Close()
	}
	if err := cfg.save(a.dir); err != nil {
		log.Printf("The config couldn't be saved: %v", err)
	}
}

// Quit closes the relays and ends the bridge.
func (a *App) Quit() {
	a.quitOnce.Do(func() {
		a.mu.Lock()
		relays := a.relays
		a.relays = nil
		a.mu.Unlock()
		for _, r := range relays {
			r.Close()
		}
		close(a.quit)
	})
}

// Handler is the page and its API, behind [App.guard].
func (a *App) Handler() http.Handler {
	static, _ := fs.Sub(uiFiles, "ui")
	mux := http.NewServeMux()
	mux.HandleFunc("GET /ping", func(w http.ResponseWriter, _ *http.Request) { fmt.Fprint(w, "randogeddon-bridge") })
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) { http.ServeFileFS(w, r, static, "index.html") })
	mux.Handle("GET /static/", http.StripPrefix("/static/", http.FileServerFS(static)))
	mux.HandleFunc("GET /api/state", a.handleState)
	mux.HandleFunc("POST /api/login", a.handleLogin)
	mux.HandleFunc("POST /api/logout", a.handleLogout)
	mux.HandleFunc("GET /api/sessions", a.handleSessions)
	mux.HandleFunc("GET /api/slots", a.handleSlots)
	mux.HandleFunc("POST /api/relays", a.handleOpen)
	mux.HandleFunc("DELETE /api/relays/{id}", a.handleClose)
	mux.HandleFunc("POST /api/quit", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
		go a.Quit()
	})
	return a.guard(mux)
}

// guard lets only this computer's browser, with the page's token, use the page: the Host must be the bridge's own
// (no other name pointing at 127.0.0.1, DNS rebinding), the token cookie must match (SameSite=Strict: other sites'
// requests don't carry it), and changes need the page's own header (another site can't set it without asking first).
func (a *App) guard(next http.Handler) http.Handler {
	hosts := map[string]bool{fmt.Sprintf("127.0.0.1:%d", a.port): true, fmt.Sprintf("localhost:%d", a.port): true}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !hosts[r.Host] {
			http.Error(w, "Not this bridge's address.", http.StatusMisdirectedRequest)
			return
		}
		h := w.Header()
		h.Set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		if r.URL.Path == "/ping" {
			next.ServeHTTP(w, r)
			return
		}
		if t := r.URL.Query().Get("t"); r.URL.Path == "/" && t != "" {
			if !a.validToken(t) {
				http.Error(w, "This link is from an earlier start of the bridge: open it from the bridge again.", http.StatusForbidden)
				return
			}
			http.SetCookie(w, &http.Cookie{Name: tokenCookie, Value: a.token, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode})
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		cookie, err := r.Cookie(tokenCookie)
		if err != nil || !a.validToken(cookie.Value) {
			http.Error(w, "Open this page from Randogeddon Bridge (start it again: it opens the page).", http.StatusForbidden)
			return
		}
		if r.Method != http.MethodGet {
			if origin := r.Header.Get("Origin"); (origin != "" && !hosts[strings.TrimPrefix(origin, "http://")]) || r.Header.Get("X-Bridge") != "1" {
				http.Error(w, "Not from the bridge's page.", http.StatusForbidden)
				return
			}
		}
		if lang := r.Header.Get("Accept-Language"); lang != "" && strings.HasPrefix(r.URL.Path, "/api/") {
			a.mu.Lock()
			a.language = lang
			a.mu.Unlock()
		}
		next.ServeHTTP(w, r)
	})
}

func (a *App) validToken(t string) bool {
	return subtle.ConstantTimeCompare([]byte(t), []byte(a.token)) == 1
}

// State is everything the page shows.
type State struct {
	Version   string      `json:"version"`
	Server    string      `json:"server"`
	User      *Me         `json:"user"`
	Info      *Info       `json:"info"`
	Update    string      `json:"update,omitempty"`    // a newer bridge the dashboard offers
	UpdateURL string      `json:"updateUrl,omitempty"` // its download for this system
	Problem   string      `json:"problem,omitempty"`
	Relays    []RelayView `json:"relays"`
}

func (a *App) handleState(w http.ResponseWriter, _ *http.Request) {
	a.mu.Lock()
	s := State{Version: version, Server: a.cfg.Server, User: a.me, Info: a.info, Problem: a.problem, Relays: []RelayView{}}
	relays := append([]*Relay(nil), a.relays...)
	a.mu.Unlock()
	for _, r := range relays {
		s.Relays = append(s.Relays, r.View())
	}
	if s.Info != nil && s.Info.Version != nil && newer(*s.Info.Version, version) {
		s.Update = *s.Info.Version
		s.UpdateURL = s.Server + "/downloads/bridge/" + buildName(runtime.GOOS, runtime.GOARCH)
	}
	writeJSON(w, http.StatusOK, s)
}

func (a *App) handleLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Key    string `json:"key"`
		Server string `json:"server"`
	}
	if json.NewDecoder(r.Body).Decode(&body) != nil {
		writeProblem(w, http.StatusBadRequest, "not a login")
		return
	}
	key := strings.TrimSpace(body.Key)
	a.mu.Lock()
	server := a.cfg.Server
	language := a.language
	a.mu.Unlock()
	if s := strings.TrimRight(strings.TrimSpace(body.Server), "/"); s != "" {
		server = s
	}
	if err := checkServer(server); err != nil {
		writeProblem(w, http.StatusBadRequest, err.Error())
		return
	}
	c := newClient(server, key, language)
	me, err := c.Me()
	if err != nil {
		writeProblem(w, http.StatusBadGateway, err.Error())
		return
	}
	info, _ := c.Info()
	a.mu.Lock()
	a.cfg.Server, a.cfg.Key = server, key
	a.me, a.info, a.problem = &me, &info, ""
	cfg := a.cfg
	a.mu.Unlock()
	if err := cfg.save(a.dir); err != nil {
		log.Printf("The config couldn't be saved: %v", err)
	}
	log.Printf("Logged in as %s at %s", me.Name, server)
	w.WriteHeader(http.StatusNoContent)
}

// checkServer allows the dashboard over HTTPS, and over plain HTTP only on this computer (the local demo): the key
// never goes over the network unencrypted.
func checkServer(server string) error {
	u, err := url.Parse(server)
	if err != nil || u.Host == "" {
		return fmt.Errorf("%q is not an address", server)
	}
	local := u.Hostname() == "localhost" || u.Hostname() == "127.0.0.1" || u.Hostname() == "::1"
	if u.Scheme == "https" || (u.Scheme == "http" && local) {
		return nil
	}
	return fmt.Errorf("%s: only https:// (or http:// on this computer)", server)
}

func (a *App) handleLogout(w http.ResponseWriter, _ *http.Request) {
	if err := a.client().Logout(); err != nil {
		log.Printf("The key couldn't be ended on the dashboard: %v", err)
	}
	a.forget("")
	log.Printf("Logged out")
	w.WriteHeader(http.StatusNoContent)
}

// call runs a dashboard call for the page; a key the dashboard no longer takes logs the bridge out.
func (a *App) call(w http.ResponseWriter, err error) bool {
	if err == nil {
		return true
	}
	var refusal *APIError
	if errors.As(err, &refusal) && refusal.KeyGone() {
		a.forget(refusal.Message)
		writeProblem(w, http.StatusUnauthorized, refusal.Message)
		return false
	}
	writeProblem(w, http.StatusBadGateway, err.Error())
	return false
}

// handleSessions answers the sessions the user plays in now, by their ID (as the game addresses name them; the title is
// the dashboard's own unless a session has one).
func (a *App) handleSessions(w http.ResponseWriter, _ *http.Request) {
	sessions, err := a.client().Sessions()
	if !a.call(w, err) {
		return
	}
	type entry struct {
		ID    string `json:"id"`
		Title string `json:"title"`
	}
	out := []entry{}
	for _, s := range sessions {
		if s.Running() && len(s.MySlots) > 0 {
			out = append(out, entry{s.ID, s.ID})
		}
	}
	writeJSON(w, http.StatusOK, out)
}

// handleSlots answers the user's slots in a session (without their passwords: the page never needs them).
func (a *App) handleSlots(w http.ResponseWriter, r *http.Request) {
	conn, err := a.client().Connection(r.URL.Query().Get("session"))
	if !a.call(w, err) {
		return
	}
	type entry struct {
		Name string `json:"name"`
		Game string `json:"game"`
	}
	out := []entry{}
	for _, s := range conn.Slots {
		out = append(out, entry{s.Name, s.Game})
	}
	writeJSON(w, http.StatusOK, out)
}

func (a *App) handleOpen(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Session string `json:"session"`
		Title   string `json:"title"`
		Slot    string `json:"slot"`
	}
	if json.NewDecoder(r.Body).Decode(&body) != nil || body.Session == "" || body.Slot == "" {
		writeProblem(w, http.StatusBadRequest, "pick a session and a slot")
		return
	}
	a.mu.Lock()
	for _, existing := range a.relays {
		if existing.Session == body.Session && existing.Slot == body.Slot {
			a.mu.Unlock()
			writeJSON(w, http.StatusOK, existing.View())
			return
		}
	}
	a.mu.Unlock()
	fetch := func() (Slot, string, error) {
		conn, err := a.client().Connection(body.Session)
		if err != nil {
			return Slot{}, "", err
		}
		for _, s := range conn.Slots {
			if s.Name == body.Slot {
				return s, upstreamURL(conn.Server), nil
			}
		}
		return Slot{}, "", fmt.Errorf("no slot %s in %s", body.Slot, body.Session)
	}
	slot, upstream, err := fetch()
	if !a.call(w, err) {
		return
	}
	a.mu.Lock()
	a.next++
	id := strconv.Itoa(a.next)
	a.mu.Unlock()
	title := body.Title
	if title == "" {
		title = body.Session
	}
	relay, err := startRelay(id, body.Session, title, slot, upstream, fetch)
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, err.Error())
		return
	}
	a.mu.Lock()
	a.relays = append(a.relays, relay)
	a.mu.Unlock()
	writeJSON(w, http.StatusOK, relay.View())
}

func (a *App) handleClose(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	a.mu.Lock()
	var closing *Relay
	kept := a.relays[:0]
	for _, relay := range a.relays {
		if relay.ID == id {
			closing = relay
		} else {
			kept = append(kept, relay)
		}
	}
	a.relays = kept
	a.mu.Unlock()
	if closing != nil {
		closing.Close()
		log.Printf("Closed the relay for %s in %s", closing.Slot, closing.Session)
	}
	w.WriteHeader(http.StatusNoContent)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeProblem(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"message": message})
}

// buildName is the bridge's file for a system, as the dashboard offers it.
func buildName(goos, goarch string) string {
	switch goos {
	case "windows":
		return "randogeddon-bridge-windows-" + goarch + ".exe"
	case "darwin":
		return "randogeddon-bridge-macos-" + goarch
	default:
		return "randogeddon-bridge-" + goos + "-" + goarch
	}
}

// newer is whether version a is newer than b (`0.12.3` > `0.12.2.1`); a build without a version (`dev`) is never
// told to update.
func newer(a, b string) bool {
	if b == "dev" {
		return false
	}
	parse := func(v string) []int {
		var out []int
		for _, part := range strings.Split(strings.SplitN(v, "-", 2)[0], ".") {
			n, _ := strconv.Atoi(part)
			out = append(out, n)
		}
		return out
	}
	x, y := parse(a), parse(b)
	for i := 0; i < max(len(x), len(y)); i++ {
		var p, q int
		if i < len(x) {
			p = x[i]
		}
		if i < len(y) {
			q = y[i]
		}
		if p != q {
			return p > q
		}
	}
	return false
}
