package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// userAgent is how the bridge names itself to the dashboard and the game servers.
func userAgent() string { return "RandogeddonBridge/" + version }

// Me is who the key belongs to: the account's name, as `GET /bridge/info` gives it.
type Me struct {
	Name string `json:"name"`
}

// Info is who the key belongs to, the bridge the dashboard offers and how the user sees Randogeddon
// (`GET /bridge/info`): one call is the bridge's whole check of its key.
type Info struct {
	Name      string            `json:"name"`
	Version   *string           `json:"version"`
	Downloads []Download        `json:"downloads"`
	Light     map[string]string `json:"light"`
	Dark      map[string]string `json:"dark"`
	Mode      string            `json:"mode"`
	Language  *string           `json:"language"`
	Font      *Font             `json:"font"`
}

// Download is one of the builds the dashboard offers: its system (`windows`, `linux`, `macos`; amd64 or arm64) and its
// file at `/downloads/bridge/<file>`.
type Download struct {
	OS   string `json:"os"`
	Arch string `json:"arch"`
	File string `json:"file"`
}

// buildFor is the file of the offered build for a system, by Go's names for it (darwin is macos there); empty: none.
func (i Info) buildFor(goos, goarch string) string {
	if goos == "darwin" {
		goos = "macos"
	}
	for _, d := range i.Downloads {
		if d.OS == goos && d.Arch == goarch {
			return d.File
		}
	}
	return ""
}

// Font is the font the user reads Randogeddon in: its files among the dashboard's static files, whether it is for
// headings only, and how large it is drawn against the system's font.
type Font struct {
	Regular      string  `json:"regular"`
	Bold         *string `json:"bold"`
	HeadingsOnly bool    `json:"headingsOnly"`
	Scale        float64 `json:"scale"`
}

// What a font's file is called there: nothing else is fetched for the page.
var fontPath = regexp.MustCompile(`^fonts/[a-z0-9-]+-(regular|bold)\.ttf$`)

// A font file is far smaller (the largest: 130 KB)
const maxFontBytes = 2 << 20

// File fetches a static file of the dashboard (a font: no key goes with it).
func (c *Client) File(path string) ([]byte, error) {
	if err := checkServer(c.Server); err != nil {
		return nil, err
	}
	resp, err := c.http.Get(c.Server + "/" + path)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s: %s", path, resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxFontBytes+1))
	if err != nil {
		return nil, err
	}
	if len(body) > maxFontBytes {
		return nil, fmt.Errorf("%s: too large", path)
	}
	return body, nil
}

// Session is one of the user's sessions (`GET /bridge/sessions`); the dashboard leaves out values that are the
// default, so an empty Status is "live".
type Session struct {
	ID      string   `json:"id"`
	Title   string   `json:"title"`
	Status  string   `json:"status"`
	MySlots []string `json:"mySlots"`
	// MyRole is the user's part in it (a key never acts as an Administrator, so every session it lists has one).
	MyRole string `json:"myRole"`
}

// Offered is whether the bridge offers the session: it runs, and the user plays in it or manages it (MySlots holds
// only their own slots, not the ones open to all or hosted). Someone of its support or a watcher who also has slots
// of their own plays in it too: their part names the role, not the slots. Its connection's slot list then says what
// can be opened.
func (s Session) Offered() bool {
	return s.Running() && (s.MyRole == "player" || s.MyRole == "manager" || len(s.MySlots) > 0)
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
	// Tunnel: a hosted Minecraft Dig slot whose server Randogeddon runs, the key for a tunnel to it (for its players
	// too, who don't get its password); empty: none.
	Tunnel string `json:"tunnel"`
}

// Usable is whether the bridge can open the slot: not locked, and with its password (a hosted one only for those who
// run its game) or a tunnel to its Minecraft server.
func (s Slot) Usable() bool { return !s.Locked && (s.Password != "" || s.Tunnel != "") }

// secret is what the bridge sends for the slot: its tunnel key for a tunnel, else its password for a relay.
func (s Slot) secret() string {
	if s.Tunnel != "" {
		return s.Tunnel
	}
	return s.Password
}

// slotOf is [slot] of [session] with where the session's game server is, for a relay. An address that would carry
// the slot's password unencrypted is refused ([checkUpstream]).
func slotOf(c *Client, session, slot string) (Slot, string, error) {
	conn, err := c.Connection(session)
	if err != nil {
		return Slot{}, "", err
	}
	for _, s := range conn.Slots {
		if s.Name == slot && s.Usable() {
			upstream := upstreamURL(conn.Server)
			if err := checkUpstream(upstream); err != nil {
				return Slot{}, "", err
			}
			return s, upstream, nil
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

// KeyGone is whether the dashboard no longer takes the key (ended, expired, or not a key): its own answer says so. Any
// other refusal (a 401 of something in front of it, a maintenance page) is a problem, and the key stays.
func (e *APIError) KeyGone() bool {
	return e.Status == http.StatusUnauthorized && e.Code == "BRIDGE_KEY_GONE"
}

// keyGone is whether [err] is the dashboard's answer that the key is gone.
func keyGone(err error) bool {
	var refusal *APIError
	return errors.As(err, &refusal) && refusal.KeyGone()
}

// refuseRedirects keeps every request at the address it was made for: the dashboard's routes and the game servers
// never redirect, and a redirect would take the key (or a tunnel key) elsewhere, maybe unencrypted.
func refuseRedirects(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }

// noRedirects is the HTTP client of the WebSocket connections to the game servers and the gateway.
var noRedirects = &http.Client{CheckRedirect: refuseRedirects}

// Client calls the dashboard's bridge routes with a key.
type Client struct {
	Server   string
	Key      string
	Language string
	http     *http.Client
}

func newClient(server, key, language string) *Client {
	return &Client{
		Server: strings.TrimRight(server, "/"), Key: key, Language: language,
		http: &http.Client{Timeout: 20 * time.Second, CheckRedirect: refuseRedirects},
	}
}

func (c *Client) do(method, path string, out any) error {
	// The key never goes over the network unencrypted, whatever address the config or -server names
	if err := checkServer(c.Server); err != nil {
		return err
	}
	req, err := http.NewRequest(method, c.Server+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.Key)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", userAgent())
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

// onThisComputer is whether [host] is this computer: localhost or a loopback address.
func onThisComputer(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// checkServer allows the dashboard over HTTPS, and over plain HTTP only on this computer (the local demo): the key
// never goes over the network unencrypted.
func checkServer(server string) error {
	u, err := url.Parse(server)
	if err != nil || u.Host == "" {
		return fmt.Errorf("%q is not an address", server)
	}
	if u.Scheme == "https" || (u.Scheme == "http" && onThisComputer(u.Hostname())) {
		return nil
	}
	return fmt.Errorf("%s: only https:// (or http:// on this computer)", server)
}

// checkUpstream allows a session's game server over wss://, and over plain ws:// only on this computer (the local
// demo): a slot's password or tunnel key never goes over the network unencrypted.
func checkUpstream(address string) error {
	u, err := url.Parse(address)
	if err != nil || u.Host == "" {
		return fmt.Errorf("%q is not an address", address)
	}
	if u.Scheme == "wss" || (u.Scheme == "ws" && onThisComputer(u.Hostname())) {
		return nil
	}
	return fmt.Errorf("the session's address %s isn't encrypted: the bridge connects only over wss:// (ws:// only on this computer)", address)
}

// upstreamURL is the WebSocket address of a session's game server: `friday.randogeddon.eratiem.net:443` becomes
// `wss://friday.randogeddon.eratiem.net`; an address with a scheme (the local demo's `ws://localhost:38281/<id>`)
// stays as it is.
func upstreamURL(server string) string {
	if strings.HasPrefix(server, "ws://") || strings.HasPrefix(server, "wss://") {
		return server
	}
	return "wss://" + strings.TrimSuffix(server, ":443")
}
