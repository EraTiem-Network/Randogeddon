// Randogeddon Bridge: games that can't do TLS connect to localhost, and the bridge takes them to their Randogeddon
// session over wss://, with the slot's name and password filled in. Its page runs in the default browser, served by
// the bridge on 127.0.0.1; it logs in with a key made on the dashboard (Your connection).
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
)

// version is set by the build (`-ldflags "-X main.version=0.12.2"`).
var version = "dev"

const defaultServer = "https://randogeddon.eratiem.net"

func main() {
	server := flag.String("server", "", "the dashboard's address (default: the last one used, else "+defaultServer+")")
	noBrowser := flag.Bool("no-browser", false, "don't open the page; its link is printed")
	flag.Usage = func() {
		fmt.Fprint(flag.CommandLine.Output(), cliUsage+"\nOptions:\n")
		flag.PrintDefaults()
	}
	flag.Parse()

	dir, err := configDir()
	if err != nil {
		fail(err)
	}
	// A command: on the command line, without the page
	if flag.NArg() > 0 {
		if !isCommand(flag.Arg(0)) {
			fmt.Fprintf(os.Stderr, "Randogeddon Bridge: unknown command %q\n\n%s", flag.Arg(0), cliUsage)
			os.Exit(2)
		}
		cfg, err := resolveConfig(dir, *server)
		if err != nil && flag.Arg(0) != "help" {
			fmt.Fprintf(os.Stderr, "Randogeddon Bridge: %v\n", err)
			os.Exit(1)
		}
		os.Exit(runCLI(dir, cfg, flag.Args(), os.Stdin, os.Stdout))
	}
	if err := runPage(dir, *server, *noBrowser); err != nil {
		fail(err)
	}
}

// resolveConfig is the saved config with the dashboard's address of -server (else the saved one, else the default).
// The key goes only to the address it was made for: with another one the bridge is logged out there (the saved pair
// stays as it is until a login there), and an address that would carry it unencrypted is refused (logged out too).
func resolveConfig(dir, flagServer string) (Config, error) {
	saved := loadConfig(dir)
	saved.Server = strings.TrimRight(saved.Server, "/")
	if saved.Server == "" {
		saved.Server = defaultServer
	}
	cfg := saved
	if s := strings.TrimRight(strings.TrimSpace(flagServer), "/"); s != "" {
		cfg.Server = s
	}
	if cfg.Server != saved.Server {
		cfg.Key = ""
	}
	if err := checkServer(cfg.Server); err != nil {
		cfg.Key = ""
		return cfg, err
	}
	return cfg, nil
}

// runPage runs the bridge with its page until it quits (the page's Quit, the page closed, a signal). Started again
// while it runs, it opens the running one's page instead.
func runPage(dir, flagServer string, noBrowser bool) error {
	cfg, refused := resolveConfig(dir, flagServer)
	token := make([]byte, 24)
	if _, err := rand.Read(token); err != nil {
		return err
	}
	app := newApp(dir, hex.EncodeToString(token), cfg)
	if refused != nil {
		app.problem = refused.Error()
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	app.port = ln.Addr().(*net.TCPAddr).Port
	lock := filepath.Join(dir, "bridge.lock")
	if other, ok := claimLock(lock, app.openURL()); !ok {
		_ = ln.Close()
		log.Printf("Already running: opening its page")
		show(other, noBrowser)
		return nil
	}
	defer removeLock(lock, app.openURL())
	// Only the bridge that runs writes the log: a second start leaves it alone
	logTo(dir)

	srv := &http.Server{Handler: app.Handler(), ReadHeaderTimeout: 10 * time.Second}
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	go func() {
		select {
		case <-signals:
			app.Quit()
		case <-app.quit:
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	}()
	go app.resume()

	log.Printf("Randogeddon Bridge %s, for %s; its page: http://127.0.0.1:%d/", version, cfg.Server, app.port)
	if refused != nil {
		log.Printf("Not logged in: %v", refused)
	}
	show(app.newLink(), noBrowser)
	if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		app.Quit()
		return err
	}
	app.Quit()
	log.Printf("Quit")
	return nil
}

// show opens [link] in the default browser, or prints it (-no-browser).
func show(link string, noBrowser bool) {
	if noBrowser {
		fmt.Println(link)
		return
	}
	openBrowser(link)
}

// claimLock makes this start the bridge that runs: [link] goes into the lock file, which is only made where there is
// none (of two starts at the same moment, one gets it). A lock whose bridge doesn't answer is left from one that
// ended without removing it, and is taken over. Another bridge that runs: a link to its page, and false.
func claimLock(path, link string) (string, bool) {
	for try := 0; try < 2; try++ {
		f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err == nil {
			_, err = f.WriteString(link)
			if closeErr := f.Close(); err == nil {
				err = closeErr
			}
			if err != nil {
				log.Printf("The lock file couldn't be written: %v", err)
			}
			return "", true
		}
		if !errors.Is(err, os.ErrExist) {
			log.Printf("The lock file couldn't be written: %v", err)
			return "", true
		}
		if other, ok := running(path); ok {
			return other, false
		}
		_ = os.Remove(path)
	}
	// Taken again by another start in the meantime
	if other, ok := running(path); ok {
		return other, false
	}
	return "", true
}

// removeLock removes the lock file if it is still this bridge's.
func removeLock(path, link string) {
	if data, err := os.ReadFile(path); err == nil && strings.TrimSpace(string(data)) == link {
		_ = os.Remove(path)
	}
}

// running is a link to the page of a bridge that runs already (its lock file, and it answers), if there is one: one
// of its own, with a code (the link in the lock file holds the page's token itself).
func running(path string) (string, bool) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", false
	}
	link := strings.TrimSpace(string(data))
	base, query, _ := strings.Cut(link, "?")
	if !strings.HasPrefix(base, "http://127.0.0.1:") {
		return "", false
	}
	base = strings.TrimRight(base, "/")
	client := http.Client{Timeout: time.Second}
	resp, err := client.Get(base + "/ping")
	if err != nil {
		return "", false
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64))
	resp.Body.Close()
	if string(body) != "randogeddon-bridge" {
		return "", false
	}
	values, _ := url.ParseQuery(query)
	req, err := http.NewRequest(http.MethodPost, base+"/api/link", nil)
	if err != nil {
		return link, true
	}
	req.Header.Set("X-Bridge", values.Get("t"))
	resp, err = client.Do(req)
	if err != nil {
		return link, true
	}
	defer resp.Body.Close()
	var answer struct {
		Link string `json:"link"`
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&answer) != nil ||
		!strings.HasPrefix(answer.Link, base+"/") {
		// A bridge from before: its page opens with the link as it is
		return link, true
	}
	return answer.Link, true
}

// openBrowser opens [link] in the default browser.
func openBrowser(link string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", link)
	case "darwin":
		cmd = exec.Command("open", link)
	default:
		cmd = exec.Command("xdg-open", link)
	}
	if err := cmd.Start(); err != nil {
		log.Printf("The browser couldn't be opened (%v): open %s", err, link)
		return
	}
	go func() { _ = cmd.Wait() }()
}

// logTo writes the log to bridge.log in the config directory (new at each start of the bridge that runs) as well: on
// Windows the bridge has no console window.
func logTo(dir string) {
	f, err := os.Create(filepath.Join(dir, "bridge.log"))
	if err != nil {
		return
	}
	log.SetOutput(io.MultiWriter(f, os.Stderr))
}

func fail(err error) {
	log.Printf("Randogeddon Bridge stopped: %v", err)
	os.Exit(1)
}
