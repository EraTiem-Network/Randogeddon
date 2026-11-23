package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"embed"
	"encoding/hex"
	"encoding/json"
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

	"github.com/coder/websocket"
)

//go:embed ui
var uiFiles embed.FS

// aliveProtocol is the subprotocol of the page's WebSocket (/api/alive); the page's token comes as the second one (a
// browser's WebSocket can't set a header).
const aliveProtocol = "randogeddon-bridge"

// checkEvery is how often the bridge checks that its key still works.
const checkEvery = 15 * time.Second

// refreshPause is how soon after a check the page's return (POST /api/refresh) checks again.
const refreshPause = 3 * time.Second

// codeLife is how long a link that opens the page works (once): the browser opens it at once.
const codeLife = 5 * time.Minute

// maxBody is the most the page sends in a body (a key, a session and a slot).
const maxBody = 64 << 10

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
	language string    // the page's Accept-Language, so the dashboard words its refusals for the user
	checked  time.Time // when the last check of the key started
	relays   []*Relay
	next     int
	fonts    map[string][]byte    // the files of the account's font (as the page showed it), by their path there
	codes    map[string]time.Time // the codes of the links that open the page, until when each works

	// The pages open now (each keeps /api/alive open); once the last is gone for [App.pageGrace] and no game is
	// connected through a relay, the bridge quits
	pages     int
	pageGrace time.Duration
	lastPage  *time.Timer
	staying   bool // the page is gone, but a game is connected: the bridge stays until it has left
}

// pageGrace is how long the bridge waits for a page after the last one closed (a reload comes back within it).
const pageGrace = 10 * time.Second

func newApp(dir, token string, cfg Config) *App {
	return &App{dir: dir, token: token, cfg: cfg, quit: make(chan struct{}), pageGrace: pageGrace}
}

// openURL is the page's link with its token itself: what the lock file holds, for a second start (readable only by
// the user). The browser gets a link with a code instead ([App.newLink]).
func (a *App) openURL() string { return fmt.Sprintf("http://127.0.0.1:%d/?t=%s", a.port, a.token) }

// newLink is a link that opens the page: its code is exchanged by the page for the token, once and within
// [codeLife], so a link read from the process list (the browser's opener has it on its command line) is soon dead.
func (a *App) newLink() string {
	code := make([]byte, 16)
	_, _ = rand.Read(code)
	now := time.Now()
	a.mu.Lock()
	for c, until := range a.codes {
		if now.After(until) {
			delete(a.codes, c)
		}
	}
	if a.codes == nil {
		a.codes = map[string]time.Time{}
	}
	a.codes[hex.EncodeToString(code)] = now.Add(codeLife)
	a.mu.Unlock()
	return fmt.Sprintf("http://127.0.0.1:%d/?c=%s", a.port, hex.EncodeToString(code))
}

// redeem is whether [code] opens the page; it works once.
func (a *App) redeem(code string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	until, ok := a.codes[code]
	delete(a.codes, code)
	return ok && time.Now().Before(until)
}

func (a *App) client() *Client {
	a.mu.Lock()
	defer a.mu.Unlock()
	return newClient(a.cfg.Server, a.cfg.Key, a.language)
}

// current is whether [c] asks with the login the bridge has now (an answer to an earlier one changes nothing); a.mu
// is held.
func (a *App) current(c *Client) bool {
	return a.cfg.Key == c.Key && strings.TrimRight(a.cfg.Server, "/") == c.Server
}

// watch calls [check] at once and then every [checkEvery], until it returns false or [quit] closes.
func watch(quit <-chan struct{}, check func() bool) {
	for check() {
		select {
		case <-quit:
			return
		case <-time.After(checkEvery):
		}
	}
}

// resume logs in with the saved key at the start, and checks every 15 seconds that the key still works: once the
// dashboard no longer takes it (ended under Your account, expired), the bridge logs out and closes its relays.
func (a *App) resume() {
	watch(a.quit, func() bool { a.check(); return true })
}

// check asks the dashboard who the key belongs to and how they see Randogeddon (theme, mode, language: a change there
// shows here within moments), in one call; a key that's gone logs out. When the call fails otherwise, what the page
// showed stays.
func (a *App) check() {
	a.mu.Lock()
	a.checked = time.Now()
	a.mu.Unlock()
	c := a.client()
	if c.Key == "" {
		return
	}
	info, err := c.Info()
	if keyGone(err) {
		a.forget(c, err.Error())
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.current(c) {
		return
	}
	if err != nil {
		if a.me == nil {
			a.problem = err.Error()
		}
		return
	}
	a.me, a.info, a.problem = &Me{Name: info.Name}, &info, ""
}

// forget logs out here, if [c] is still the login: the key goes from the config, the relays close.
func (a *App) forget(c *Client, problem string) {
	a.mu.Lock()
	if c.Key == "" || !a.current(c) {
		a.mu.Unlock()
		return
	}
	relays := a.takeRelays()
	a.me, a.info, a.problem = nil, nil, problem
	a.cfg.Key = ""
	cfg := a.cfg
	a.mu.Unlock()
	closeRelays(relays)
	if err := cfg.save(a.dir); err != nil {
		log.Printf("The config couldn't be saved: %v", err)
	}
}

// takeRelays takes the relays out of the list, to be closed once a.mu is free ([closeRelays]); a.mu is held.
func (a *App) takeRelays() []*Relay {
	relays := a.relays
	a.relays = nil
	return relays
}

func closeRelays(relays []*Relay) {
	for _, r := range relays {
		r.Close()
	}
}

// Quit closes the relays and ends the bridge.
func (a *App) Quit() {
	a.quitOnce.Do(func() {
		a.mu.Lock()
		relays := a.takeRelays()
		a.mu.Unlock()
		closeRelays(relays)
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
	// The page's link carries a code: the page exchanges it for the token, which it sends with every call
	mux.HandleFunc("POST /api/exchange", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Code string `json:"code"`
		}
		if readJSON(w, r, &body) != nil || !a.redeem(body.Code) {
			writeProblem(w, http.StatusForbidden, "This link works once and for a few minutes: start Randogeddon Bridge again (it opens its page).")
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"token": a.token})
	})
	// A link of its own for a second start (it has the token from the lock file): it opens this page
	mux.HandleFunc("POST /api/link", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"link": a.newLink()})
	})
	mux.HandleFunc("GET /api/state", a.handleState)
	// The page got the focus back: the account's theme, mode and language now, not at the next check (unless that was
	// a moment ago)
	mux.HandleFunc("POST /api/refresh", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		recent := time.Since(a.checked) < refreshPause
		a.mu.Unlock()
		if !recent {
			a.check()
		}
		a.handleState(w, r)
	})
	// The account's font, fetched from the dashboard by the bridge: the page loads nothing from elsewhere
	mux.HandleFunc("GET /font/{weight}", a.handleFont)
	mux.HandleFunc("POST /api/login", a.handleLogin)
	mux.HandleFunc("POST /api/logout", a.handleLogout)
	mux.HandleFunc("GET /api/sessions", a.handleSessions)
	mux.HandleFunc("GET /api/slots", a.handleSlots)
	mux.HandleFunc("POST /api/relays", a.handleOpen)
	mux.HandleFunc("DELETE /api/relays/{id}", a.handleClose)
	mux.HandleFunc("GET /api/alive", a.handleAlive)
	mux.HandleFunc("POST /api/quit", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
		go a.Quit()
	})
	return a.guard(mux)
}

// guard lets only this computer's browser, with the page's token, use the page: the Host must be the bridge's own
// (no other name pointing at 127.0.0.1, DNS rebinding), and every call carries the token in its X-Bridge header (no
// cookie: a browser would send that to every server on 127.0.0.1, whatever its port; and another site can't set the
// header without asking first). Changes also come from no other site's page (Origin).
func (a *App) guard(next http.Handler) http.Handler {
	hosts := map[string]bool{fmt.Sprintf("127.0.0.1:%d", a.port): true, fmt.Sprintf("localhost:%d", a.port): true}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !hosts[r.Host] {
			http.Error(w, "Not this bridge's address.", http.StatusMisdirectedRequest)
			return
		}
		h := w.Header()
		// The page's own WebSocket (/api/alive) named too: older browsers don't count ws:// as 'self'
		h.Set("Content-Security-Policy", fmt.Sprintf("default-src 'self'; connect-src 'self' ws://%s; frame-ancestors 'none'; "+
			"base-uri 'none'; form-action 'self'", r.Host))
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		// The ping and the page's own files: nothing in them to keep from anyone (the page then needs its token)
		if r.Method == http.MethodGet && (r.URL.Path == "/ping" || r.URL.Path == "/" || strings.HasPrefix(r.URL.Path, "/static/")) {
			next.ServeHTTP(w, r)
			return
		}
		if r.Method != http.MethodGet {
			if origin := r.Header.Get("Origin"); origin != "" && !hosts[strings.TrimPrefix(origin, "http://")] {
				http.Error(w, "Not from the bridge's page.", http.StatusForbidden)
				return
			}
		}
		// The link's code is what lets it in; the header still keeps other sites out
		if r.URL.Path == "/api/exchange" {
			if r.Header.Get("X-Bridge") == "" {
				http.Error(w, "Not from the bridge's page.", http.StatusForbidden)
				return
			}
			next.ServeHTTP(w, r)
			return
		}
		if !a.validToken(pageToken(r)) {
			http.Error(w, "Open this page from Randogeddon Bridge (start it again: it opens the page).", http.StatusForbidden)
			return
		}
		if lang := r.Header.Get("Accept-Language"); lang != "" && strings.HasPrefix(r.URL.Path, "/api/") {
			a.mu.Lock()
			a.language = lang
			a.mu.Unlock()
		}
		next.ServeHTTP(w, r)
	})
}

// pageToken is the token a call of the page carries: its X-Bridge header, or for its WebSocket (/api/alive) the
// subprotocol after [aliveProtocol].
func pageToken(r *http.Request) string {
	if r.URL.Path != "/api/alive" {
		return r.Header.Get("X-Bridge")
	}
	for _, protocols := range r.Header.Values("Sec-WebSocket-Protocol") {
		for _, p := range strings.Split(protocols, ",") {
			if p = strings.TrimSpace(p); p != "" && p != aliveProtocol {
				return p
			}
		}
	}
	return ""
}

// handleAlive keeps a WebSocket open while the page is (it sends nothing). The browser closes it with the tab, and the
// system with a killed browser: once no page is left and none comes back within [App.pageGrace], the bridge quits,
// relays and all; while a game is connected through a relay, it stays until the last one has left. Only after a page
// was there: started with -no-browser it runs until Quit.
func (a *App) handleAlive(w http.ResponseWriter, r *http.Request) {
	// Accept also refuses another site's page (its Origin isn't this host)
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{aliveProtocol}})
	if err != nil {
		return
	}
	a.mu.Lock()
	a.pages++
	a.staying = false
	if a.lastPage != nil {
		a.lastPage.Stop()
		a.lastPage = nil
	}
	a.mu.Unlock()
	// Reads until the page goes (it never sends anything)
	ctx := conn.CloseRead(context.Background())
	select {
	case <-ctx.Done():
	case <-a.quit:
		_ = conn.Close(websocket.StatusGoingAway, "quit")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.pages--
	if a.pages == 0 && a.lastPage == nil {
		a.lastPage = time.AfterFunc(a.pageGrace, a.quitWithoutPage)
	}
}

// quitWithoutPage quits the bridge once no page is open: at once if no game is connected through a relay, else once
// the last one has left (looked at again every [App.pageGrace]).
func (a *App) quitWithoutPage() {
	a.mu.Lock()
	if a.pages > 0 {
		a.mu.Unlock()
		return
	}
	games := 0
	for _, r := range a.relays {
		games += r.View().Games
	}
	if games > 0 {
		if !a.staying {
			a.staying = true
			log.Printf("The page was closed: running on while a game is connected")
		}
		if a.lastPage != nil {
			a.lastPage.Reset(a.pageGrace)
		}
		a.mu.Unlock()
		return
	}
	a.mu.Unlock()
	log.Printf("The page was closed: quitting")
	a.Quit()
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
	UpdateURL string      `json:"updateUrl,omitempty"` // its download for this system; none if it offers none
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
		if file := s.Info.buildFor(runtime.GOOS, runtime.GOARCH); file != "" {
			s.UpdateURL = s.Server + "/downloads/bridge/" + url.PathEscape(file)
		}
	}
	writeJSON(w, http.StatusOK, s)
}

// handleFont serves the regular or the bold file of the account's font (as `GET /bridge/info` named it), fetched from
// the dashboard once and kept while the bridge runs (only the current font's files). Without a font, or if it can't be
// fetched: 404, and the page keeps the system's font.
func (a *App) handleFont(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	var path, other string
	if a.info != nil && a.info.Font != nil {
		var bold string
		if a.info.Font.Bold != nil {
			bold = *a.info.Font.Bold
		}
		switch r.PathValue("weight") {
		case "regular":
			path, other = a.info.Font.Regular, bold
		case "bold":
			path, other = bold, a.info.Font.Regular
		}
	}
	data := a.fonts[path]
	a.mu.Unlock()
	if !fontPath.MatchString(path) {
		http.NotFound(w, r)
		return
	}
	if data == nil {
		fetched, err := a.client().File(path)
		if err != nil {
			log.Printf("The font couldn't be fetched: %v", err)
			http.NotFound(w, r)
			return
		}
		data = fetched
		a.mu.Lock()
		// The font's other file stays; one the account no longer reads in goes
		kept := map[string][]byte{path: data}
		if d, ok := a.fonts[other]; ok {
			kept[other] = d
		}
		a.fonts = kept
		a.mu.Unlock()
	}
	w.Header().Set("Content-Type", "font/ttf")
	w.Write(data)
}

// readJSON reads the page's JSON body, of at most [maxBody].
func readJSON(w http.ResponseWriter, r *http.Request, v any) error {
	return json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody)).Decode(v)
}

func (a *App) handleLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Key    string `json:"key"`
		Server string `json:"server"`
	}
	if readJSON(w, r, &body) != nil {
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
	info, err := c.Info()
	if err != nil {
		writeProblem(w, http.StatusBadGateway, err.Error())
		return
	}
	a.mu.Lock()
	// Another dashboard or another account: the relays of the login before close
	var relays []*Relay
	if strings.TrimRight(a.cfg.Server, "/") != c.Server || a.me == nil || a.me.Name != info.Name {
		relays = a.takeRelays()
	}
	a.cfg.Server, a.cfg.Key = c.Server, key
	a.me, a.info, a.problem = &Me{Name: info.Name}, &info, ""
	cfg := a.cfg
	a.mu.Unlock()
	closeRelays(relays)
	if err := cfg.save(a.dir); err != nil {
		log.Printf("The config couldn't be saved: %v", err)
	}
	log.Printf("Logged in as %s at %s", info.Name, c.Server)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) handleLogout(w http.ResponseWriter, _ *http.Request) {
	c := a.client()
	if c.Key != "" {
		if err := c.Logout(); err != nil {
			log.Printf("The key couldn't be ended on the dashboard: %v", err)
		}
	}
	a.forget(c, "")
	log.Printf("Logged out")
	w.WriteHeader(http.StatusNoContent)
}

// call runs a dashboard call of [c] for the page; a key the dashboard no longer takes logs the bridge out.
func (a *App) call(w http.ResponseWriter, c *Client, err error) bool {
	if err == nil {
		return true
	}
	if keyGone(err) {
		a.forget(c, err.Error())
		writeProblem(w, http.StatusUnauthorized, err.Error())
		return false
	}
	writeProblem(w, http.StatusBadGateway, err.Error())
	return false
}

// handleSessions answers the running sessions the user plays in or manages: their ID (as the game addresses name
// them) and their title.
func (a *App) handleSessions(w http.ResponseWriter, _ *http.Request) {
	c := a.client()
	sessions, err := c.Sessions()
	if !a.call(w, c, err) {
		return
	}
	type entry struct {
		ID    string `json:"id"`
		Title string `json:"title"`
	}
	out := []entry{}
	for _, s := range sessions {
		if s.Offered() {
			title := s.Title
			if title == "" {
				title = s.ID
			}
			out = append(out, entry{s.ID, title})
		}
	}
	writeJSON(w, http.StatusOK, out)
}

// handleSlots answers the user's slots in a session (without their passwords: the page never needs them).
func (a *App) handleSlots(w http.ResponseWriter, r *http.Request) {
	c := a.client()
	conn, err := c.Connection(r.URL.Query().Get("session"))
	if !a.call(w, c, err) {
		return
	}
	type entry struct {
		Name   string `json:"name"`
		Game   string `json:"game"`
		Hosted bool   `json:"hosted"`
	}
	out := []entry{}
	for _, s := range conn.Slots {
		if s.Usable() {
			out = append(out, entry{s.Name, s.Game, s.Hosted})
		}
	}
	writeJSON(w, http.StatusOK, out)
}

// relayFor is the open relay for [slot] of [session], if there is one; a.mu is held.
func (a *App) relayFor(session, slot string) *Relay {
	for _, r := range a.relays {
		if r.Session == session && r.Slot == slot {
			return r
		}
	}
	return nil
}

func (a *App) handleOpen(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Session string `json:"session"`
		Title   string `json:"title"`
		Slot    string `json:"slot"`
	}
	if readJSON(w, r, &body) != nil || body.Session == "" || body.Slot == "" {
		writeProblem(w, http.StatusBadRequest, "pick a session and a slot")
		return
	}
	c := a.client()
	a.mu.Lock()
	existing := a.relayFor(body.Session, body.Slot)
	a.mu.Unlock()
	if existing != nil {
		writeJSON(w, http.StatusOK, existing.View())
		return
	}
	// The relay belongs to this login: its password is fetched again with it
	fetch := func() (Slot, string, error) { return slotOf(c, body.Session, body.Slot) }
	slot, upstream, err := fetch()
	if !a.call(w, c, err) {
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
	relay, err := startRelay(id, body.Session, title, slot, upstream, fetch, 0)
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, err.Error())
		return
	}
	// Kept only if the login is still the one it was opened with, and no other click opened the slot meanwhile
	a.mu.Lock()
	existing = a.relayFor(body.Session, body.Slot)
	kept := existing == nil && a.current(c)
	if kept {
		a.relays = append(a.relays, relay)
	}
	a.mu.Unlock()
	if !kept {
		relay.Close()
		if existing != nil {
			writeJSON(w, http.StatusOK, existing.View())
			return
		}
		writeProblem(w, http.StatusConflict, "logged out meanwhile")
		return
	}
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
