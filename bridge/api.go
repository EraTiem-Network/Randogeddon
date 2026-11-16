package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Me is who the key belongs to (`GET /bridge/me`).
type Me struct {
	Name string `json:"name"`
}

// Info is the bridge the dashboard offers and how the user sees Randogeddon (`GET /bridge/info`).
type Info struct {
	Version  *string           `json:"version"`
	Light    map[string]string `json:"light"`
	Dark     map[string]string `json:"dark"`
	Mode     string            `json:"mode"`
	Language *string           `json:"language"`
}

// Session is one of the user's sessions (`GET /bridge/sessions`); the dashboard leaves out values that are the
// default, so an empty Status is "live".
type Session struct {
	ID      string   `json:"id"`
	Status  string   `json:"status"`
	MySlots []string `json:"mySlots"`
	// MyRole is the user's part in it; empty for an Administrator without one (they reach every session).
	MyRole string `json:"myRole"`
}

// Offered is whether the bridge offers the session: it runs, and the user plays in it or may connect its hosted slots
// (a Session-Manager, or an Administrator).
func (s Session) Offered() bool {
	return s.Running() && (len(s.MySlots) > 0 || s.MyRole == "manager" || s.MyRole == "")
}

// Running is whether game clients can play the session now.
func (s Session) Running() bool {
	return s.Status == "" || s.Status == "live" || s.Status == "finishing"
}

// Slot is one of the user's slots in a session, with its password. A hosted one comes with its password only for its
// Session-Managers and Administrators (they run its game); its players only get its patch files.
type Slot struct {
	Name     string `json:"name"`
	Game     string `json:"game"`
	Password string `json:"password"`
	Hosted   bool   `json:"hosted"`
	// Locked: its key isn't found yet, so the gateway refuses it (no password until then).
	Locked bool `json:"locked"`
}

// Usable is whether the bridge can open the slot: not locked, and with its password (a hosted one only for those who
// run its game).
func (s Slot) Usable() bool { return !s.Locked && s.Password != "" }

// slotOf is [slot] of [session] with where the session's game server is, for a relay.
func slotOf(c *Client, session, slot string) (Slot, string, error) {
	conn, err := c.Connection(session)
	if err != nil {
		return Slot{}, "", err
	}
	for _, s := range conn.Slots {
		if s.Name == slot && s.Usable() {
			return s, upstreamURL(conn.Server), nil
		}
	}
	return Slot{}, "", fmt.Errorf("no slot %s to open in %s", slot, session)
}

// Connection is where a session's game clients connect and the user's slots in it
// (`GET /bridge/sessions/{id}/connection`).
type Connection struct {
	Server string `json:"server"`
	Slots  []Slot `json:"slots"`
}

// APIError is the dashboard's refusal, in the words of the language asked for.
type APIError struct {
	Status  int
	Code    string
	Message string
}

func (e *APIError) Error() string { return e.Message }

// KeyGone is whether the dashboard no longer takes the key (ended, expired, or not a key).
func (e *APIError) KeyGone() bool { return e.Status == http.StatusUnauthorized }

// Client calls the dashboard's bridge routes with a key.
type Client struct {
	Server   string
	Key      string
	Language string
	http     *http.Client
}

func newClient(server, key, language string) *Client {
	return &Client{Server: strings.TrimRight(server, "/"), Key: key, Language: language, http: &http.Client{Timeout: 20 * time.Second}}
}

func (c *Client) do(method, path string, out any) error {
	req, err := http.NewRequest(method, c.Server+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.Key)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "RandogeddonBridge/"+version)
	if c.Language != "" {
		req.Header.Set("Accept-Language", c.Language)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		var refusal struct {
			Message string `json:"message"`
			Code    string `json:"code"`
		}
		_ = json.Unmarshal(body, &refusal)
		if refusal.Message == "" {
			refusal.Message = fmt.Sprintf("%s answered %d", c.Server, resp.StatusCode)
		}
		return &APIError{Status: resp.StatusCode, Code: refusal.Code, Message: refusal.Message}
	}
	if out == nil || len(body) == 0 {
		return nil
	}
	return json.Unmarshal(body, out)
}

func (c *Client) Me() (Me, error) {
	var me Me
	return me, c.do(http.MethodGet, "/bridge/me", &me)
}

func (c *Client) Info() (Info, error) {
	var info Info
	return info, c.do(http.MethodGet, "/bridge/info", &info)
}

func (c *Client) Sessions() ([]Session, error) {
	var sessions []Session
	return sessions, c.do(http.MethodGet, "/bridge/sessions", &sessions)
}

func (c *Client) Connection(session string) (Connection, error) {
	var conn Connection
	return conn, c.do(http.MethodGet, "/bridge/sessions/"+url.PathEscape(session)+"/connection", &conn)
}

// Logout ends the key on the dashboard.
func (c *Client) Logout() error { return c.do(http.MethodDelete, "/bridge/key", nil) }

// upstreamURL is the WebSocket address of a session's game server: `friday.randogeddon.eratiem.net:443` becomes
// `wss://friday.randogeddon.eratiem.net`; an address with a scheme (the local demo's `ws://localhost:38281/<id>`)
// stays as it is.
func upstreamURL(server string) string {
	if strings.HasPrefix(server, "ws://") || strings.HasPrefix(server, "wss://") {
		return server
	}
	return "wss://" + strings.TrimSuffix(server, ":443")
}
