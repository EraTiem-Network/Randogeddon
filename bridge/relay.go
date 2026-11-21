package main

import (
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

	mu        sync.Mutex
	password  string
	upstream  string
	open      int // game connections now
	connected int // of them, the ones the server took in
	detail    string
	listener  net.Listener
	server    *http.Server // nil for a tunnel
	closing   context.Context
	close     context.CancelFunc
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
}

// startRelay opens a relay on [port] of 127.0.0.1 (only this computer reaches it); 0: a free one.
func startRelay(id, session, title string, slot Slot, upstream string, refresh func() (Slot, string, error), port int) (*Relay, error) {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return nil, err
	}
	r := &Relay{
		ID: id, Session: session, Title: title, Slot: slot.Name, Game: slot.Game, Port: ln.Addr().(*net.TCPAddr).Port,
		refresh: refresh, password: slot.Password, upstream: upstream, listener: ln,
	}
	r.closing, r.close = context.WithCancel(context.Background())
	if slot.Game == MinecraftDig {
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

// Close ends the relay and its game connections.
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
		Detail: r.detail, Games: r.open,
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

// ServeHTTP takes one game connection. A request with an Origin header comes from a web page in a browser, not from
// a game: refused, so no page the player has open can act as their slot.
func (r *Relay) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	if req.Header.Get("Origin") != "" {
		http.Error(w, "Randogeddon Bridge doesn't take connections from web pages.", http.StatusForbidden)
		log.Printf("relay %s: refused a connection from a web page (%s)", r.Slot, req.Header.Get("Origin"))
		return
	}
	if !strings.EqualFold(req.Header.Get("Upgrade"), "websocket") {
		fmt.Fprintf(w, "Randogeddon Bridge: connect your game to localhost:%d\n", r.Port)
		return
	}
	game, err := websocket.Accept(w, req, &websocket.AcceptOptions{CompressionMode: websocket.CompressionContextTakeover})
	if err != nil {
		return
	}
	game.SetReadLimit(maxMessage)
	defer game.CloseNow()
	r.note(func() { r.open++; r.detail = "" })
	defer r.note(func() { r.open-- })

	ctx, cancel := context.WithCancel(req.Context())
	defer cancel()
	address, password := r.target()
	dialCtx, dialCancel := context.WithTimeout(ctx, 20*time.Second)
	server, _, err := websocket.Dial(dialCtx, address, &websocket.DialOptions{
		HTTPHeader:      http.Header{"User-Agent": {"RandogeddonBridge/" + version}},
		CompressionMode: websocket.CompressionContextTakeover,
	})
	dialCancel()
	if err != nil {
		r.note(func() { r.detail = "unreachable: " + err.Error() })
		log.Printf("relay %s: %s unreachable: %v", r.Slot, address, err)
		_ = game.Close(websocket.StatusTryAgainLater, "Randogeddon can't be reached")
		return
	}
	server.SetReadLimit(maxMessage)
	defer server.CloseNow()

	var inSlot atomic.Bool
	done := make(chan error, 2)
	// The game's messages, with the slot's name and password in its Connect
	go func() {
		done <- pump(ctx, game, server, func(typ websocket.MessageType, data []byte) []byte {
			if typ == websocket.MessageText {
				data, _ = fillConnect(data, r.Slot, password)
			}
			return data
		})
	}()
	// The server's, watched for whether the slot got in
	go func() {
		done <- pump(ctx, server, game, func(typ websocket.MessageType, data []byte) []byte {
			if typ != websocket.MessageText {
				return data
			}
			connected, refused := serverReply(data)
			switch {
			case connected && inSlot.CompareAndSwap(false, true):
				r.note(func() { r.connected++; r.detail = "" })
			case len(refused) > 0:
				reason := strings.Join(refused, ", ")
				r.note(func() { r.detail = reason })
				log.Printf("relay %s: refused (%s)", r.Slot, reason)
				if strings.Contains(reason, "InvalidPassword") || strings.Contains(reason, "InvalidSlot") {
					go r.update()
				}
			}
			return data
		})
	}()
	err = <-done
	cancel()
	if inSlot.Load() {
		r.note(func() { r.connected-- })
	}
	// The other side ends the way this one did
	code, reason := websocket.StatusNormalClosure, ""
	var closed websocket.CloseError
	if errors.As(err, &closed) && closed.Code != websocket.StatusNoStatusRcvd && closed.Code != websocket.StatusAbnormalClosure {
		code, reason = closed.Code, closed.Reason
	}
	_ = game.Close(code, reason)
	_ = server.Close(code, reason)
}

// update fetches the slot's password and the session's address again, for the next game connection.
func (r *Relay) update() {
	slot, upstream, err := r.refresh()
	if err != nil {
		log.Printf("relay %s: the password couldn't be fetched again: %v", r.Slot, err)
		return
	}
	r.note(func() { r.password = slot.Password; r.upstream = upstream })
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
