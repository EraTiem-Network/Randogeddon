package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

// maxMessage is the largest message passed on: Archipelago's DataPackage can be several megabytes.
const maxMessage = 64 << 20

// maxGames is how many game connections one relay (or tunnel) carries at once: far more than a real setup has (a game
// and a text client or a tracker), so only a runaway reconnect loop meets it.
const maxGames = 32

// refreshEvery is the least time between two fetches of a relay's password: a game that keeps reconnecting while it is
// refused makes one dashboard call, not one per attempt.
const refreshEvery = 10 * time.Second

// The states a relay shows.
const (
	StateWaiting    = "waiting"    // no game connected
	StateConnecting = "connecting" // a game is connected, the slot isn't in yet
	StateConnected  = "connected"  // the slot is in
	StateError      = "error"      // the last attempt failed (Detail says why); a new game connection tries again
)

// Relay takes the game connections on one local port to one slot of a session: each game connection
// (`ws://localhost:<port>`) gets its own connection to the session's game server, every message passes both ways, and
// the game's Connect carries the slot's name and password.
type Relay struct {
	ID      string
	Session string
	Title   string
	Slot    string
	Game    string
	Port    int

	// refresh fetches the slot's password and the session's address again (after a refusal: it may have changed)
	refresh func() (Slot, string, error)

	mu           sync.Mutex
	password     string
	upstream     string
	open         int // game connections now
	connected    int // of them, the ones the server took in
	limit        int // the most game connections at once ([maxGames])
	detail       string
	refreshing   bool      // a fetch of the password runs
	refreshed    time.Time // when the last one started
	pagesRefused int       // connections from web pages refused (only the first is logged)
	listener     net.Listener
	server       *http.Server // nil for a tunnel
	closing      context.Context
	close        context.CancelFunc
}

// RelayView is a relay as the page shows it.
type RelayView struct {
	ID      string `json:"id"`
	Session string `json:"session"`
	Title   string `json:"title"`
	Slot    string `json:"slot"`
	Game    string `json:"game"`
	Port    int    `json:"port"`
	State   string `json:"state"`
	Detail  string `json:"detail,omitempty"`
	Games   int    `json:"games"`
	// Tunnel: to a Minecraft server Randogeddon runs (Minecraft connects to it), not a relay
	Tunnel bool `json:"tunnel"`
}

// startRelay opens a relay on [port] of 127.0.0.1 (only this computer reaches it); 0: a free one.
func startRelay(id, session, title string, slot Slot, upstream string, refresh func() (Slot, string, error), port int) (*Relay, error) {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return nil, err
	}
	r := &Relay{
		ID: id, Session: session, Title: title, Slot: slot.Name, Game: slot.Game, Port: ln.Addr().(*net.TCPAddr).Port,
		refresh: refresh, password: slot.secret(), upstream: upstream, limit: maxGames, listener: ln,
	}
	r.closing, r.close = context.WithCancel(context.Background())
	if slot.Tunnel != "" {
		go r.serveTunnel(ln)
		log.Printf("Tunnel for %s in %s on localhost:%d", r.Slot, r.Session, r.Port)
		return r, nil
	}
	r.server = &http.Server{Handler: r, ReadHeaderTimeout: 10 * time.Second}
	go func() {
		if err := r.server.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("relay %s: %v", r.Slot, err)
		}
	}()
	log.Printf("Relay for %s in %s on localhost:%d", r.Slot, r.Session, r.Port)
	return r, nil
}

// Close ends the relay and its game connections: they watch [Relay.closing] (the server's Shutdown doesn't know a
// connection taken over by a WebSocket).
func (r *Relay) Close() {
	r.close()
	if r.server == nil {
		_ = r.listener.Close()
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = r.server.Shutdown(ctx)
	_ = r.server.Close()
}

func (r *Relay) View() RelayView {
	r.mu.Lock()
	defer r.mu.Unlock()
	state := StateWaiting
	switch {
	case r.connected > 0:
		state = StateConnected
	case r.detail != "":
		state = StateError
	case r.open > 0:
		state = StateConnecting
	}
	return RelayView{
		ID: r.ID, Session: r.Session, Title: r.Title, Slot: r.Slot, Game: r.Game, Port: r.Port, State: state,
		Detail: r.detail, Games: r.open, Tunnel: r.server == nil,
	}
}

func (r *Relay) target() (string, string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.upstream, r.password
}

func (r *Relay) note(change func()) {
	r.mu.Lock()
	defer r.mu.Unlock()
	change()
}

// enter counts a game connection in (a new attempt: the last one's problem goes), unless the relay carries
// [Relay.limit] already.
func (r *Relay) enter() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.open >= r.limit {
		return false
	}
	r.open++
	r.detail = ""
	return true
}

// leave counts a game connection out.
func (r *Relay) leave() { r.note(func() { r.open-- }) }

// unreachable notes that the game server (or the gateway) couldn't be reached, for the page and the log.
func (r *Relay) unreachable(kind, address string, err error) {
	r.note(func() { r.detail = "unreachable: " + err.Error() })
	log.Printf("%s %s: %s unreachable: %v", kind, r.Slot, address, err)
}

// refused notes why the server refused the slot, for the page and the log.
func (r *Relay) refused(kind, reason string) {
	r.note(func() { r.detail = reason })
	log.Printf("%s %s: refused (%s)", kind, r.Slot, reason)
}

// dial connects to a session's game server (or the gateway's end of a tunnel) at [address]: over wss://, ws:// only on
// this computer ([checkUpstream]), and without following a redirect (the headers, a tunnel key among them, stay with
// the address the dashboard named).
func dial(ctx context.Context, address string, opts websocket.DialOptions) (*websocket.Conn, error) {
	if err := checkUpstream(address); err != nil {
		return nil, err
	}
	if opts.HTTPHeader == nil {
		opts.HTTPHeader = http.Header{}
	}
	opts.HTTPHeader.Set("User-Agent", userAgent())
	opts.HTTPClient = noRedirects
	dialCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(dialCtx, address, &opts)
	if err != nil {
		return nil, err
	}
	conn.SetReadLimit(maxMessage)
	return conn, nil
}

// ServeHTTP takes one game connection. A request with an Origin header comes from a web page in a browser, not from
// a game: refused, so no page the player has open can act as their slot.
func (r *Relay) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	if req.Header.Get("Origin") != "" {
		http.Error(w, "Randogeddon Bridge doesn't take connections from web pages.", http.StatusForbidden)
		r.mu.Lock()
		r.pagesRefused++
		first := r.pagesRefused == 1
		r.mu.Unlock()
		if first {
			log.Printf("relay %s: refused a connection from a web page (%s; more aren't logged)", r.Slot, req.Header.Get("Origin"))
		}
		return
	}
	if !strings.EqualFold(req.Header.Get("Upgrade"), "websocket") {
		fmt.Fprintf(w, "Randogeddon Bridge: connect your game to localhost:%d\n", r.Port)
		return
	}
	if !r.enter() {
		http.Error(w, fmt.Sprintf("Randogeddon Bridge: this relay carries %d game connections already.", maxGames), http.StatusServiceUnavailable)
		return
	}
	defer r.leave()
	game, err := websocket.Accept(w, req, &websocket.AcceptOptions{CompressionMode: websocket.CompressionContextTakeover})
	if err != nil {
		return
	}
	game.SetReadLimit(maxMessage)
	defer game.CloseNow()

	address, password := r.target()
	server, err := dial(r.closing, address, websocket.DialOptions{CompressionMode: websocket.CompressionContextTakeover})
	if err != nil {
		r.unreachable("relay", address, err)
		_ = game.Close(websocket.StatusTryAgainLater, "Randogeddon can't be reached")
		return
	}
	defer server.CloseNow()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// Closing the relay (✕, logout, a key that's gone) ends the game connection too, as a server going away does
	stop := context.AfterFunc(r.closing, func() {
		_ = game.Close(websocket.StatusGoingAway, "Randogeddon Bridge closed the relay")
		_ = server.Close(websocket.StatusGoingAway, "")
	})
	defer stop()

	var inSlot atomic.Bool
	done := make(chan error, 2)
	// The game's messages, with the slot's name and password in its Connect (until the slot is in)
	go func() {
		done <- pump(ctx, game, server, func(typ websocket.MessageType, data []byte) []byte {
			if typ == websocket.MessageText && !inSlot.Load() {
				data, _ = fillConnect(data, r.Slot, password)
			}
			return data
		})
	}()
	// The server's, watched for whether the slot got in: only a frame that can be the answer to the Connect
	// (Connected, ConnectionRefused) is parsed, and none once the slot is in (not the DataPackage of megabytes)
	go func() {
		done <- pump(ctx, server, game, func(typ websocket.MessageType, data []byte) []byte {
			if typ != websocket.MessageText || inSlot.Load() || !bytes.Contains(data, []byte("Connect")) {
				return data
			}
			connected, refused := serverReply(data)
			switch {
			case connected && inSlot.CompareAndSwap(false, true):
				r.note(func() { r.connected++; r.detail = "" })
			case len(refused) > 0:
				reason := strings.Join(refused, ", ")
				r.refused("relay", reason)
				if strings.Contains(reason, "InvalidPassword") || strings.Contains(reason, "InvalidSlot") {
					r.refreshSoon()
				}
			}
			return data
		})
	}()
	err = <-done
	// The other side ends the way this one did: closed with its code first (that also ends the other pump's read),
	// cancelled after (a cancelled read would end the connection without the code)
	code, reason := websocket.StatusNormalClosure, ""
	var closed websocket.CloseError
	if errors.As(err, &closed) && closed.Code != websocket.StatusNoStatusRcvd && closed.Code != websocket.StatusAbnormalClosure {
		code, reason = closed.Code, closed.Reason
	}
	_ = game.Close(code, reason)
	_ = server.Close(code, reason)
	cancel()
	<-done
	if inSlot.Load() {
		r.note(func() { r.connected-- })
	}
}

// refreshSoon fetches the slot's password and the session's address again, for the next game connection: one fetch
// at a time, and at most one every [refreshEvery].
func (r *Relay) refreshSoon() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.refresh == nil || r.refreshing || (!r.refreshed.IsZero() && time.Since(r.refreshed) < refreshEvery) {
		return
	}
	r.refreshing, r.refreshed = true, time.Now()
	go r.update()
}

// update fetches the slot's password and the session's address again; a slot that's gone (or can't be fetched) says
// so on the page.
func (r *Relay) update() {
	slot, upstream, err := r.refresh()
	r.mu.Lock()
	defer r.mu.Unlock()
	r.refreshing = false
	if err != nil {
		r.detail = err.Error()
		log.Printf("relay %s: the password couldn't be fetched again: %v", r.Slot, err)
		return
	}
	r.password, r.upstream = slot.secret(), upstream
}

// pump sends every message of from on to to, after mapping it, until either side ends.
func pump(ctx context.Context, from, to *websocket.Conn, mapping func(websocket.MessageType, []byte) []byte) error {
	for {
		typ, data, err := from.Read(ctx)
		if err != nil {
			return err
		}
		if err := to.Write(ctx, typ, mapping(typ, data)); err != nil {
			return err
		}
	}
}
