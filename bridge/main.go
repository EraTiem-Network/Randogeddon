// Randogeddon Bridge: games that can't do TLS connect to localhost, and the bridge takes them to their Randogeddon
// session over wss://, with the slot's name and password filled in. Its page runs in the default browser, served by
// the bridge on 127.0.0.1; it logs in with a key made on the dashboard (Your connection).
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// version is set by the build (`-ldflags "-X main.version=0.12.2"`).
var version = "dev"

const defaultServer = "https://randogeddon.eratiem.net"

func main() {
	server := flag.String("server", "", "the dashboard's address (default: the last one used, else "+defaultServer+")")
	noBrowser := flag.Bool("no-browser", false, "don't open the page; its link is in the log")
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
		cfg := loadConfig(dir)
		if *server != "" {
			cfg.Server = strings.TrimRight(*server, "/")
		}
		if cfg.Server == "" {
			cfg.Server = defaultServer
		}
		os.Exit(runCLI(dir, cfg, flag.Args(), os.Stdin, os.Stdout))
	}
	logTo(dir)

	// Started again while it runs: the page of the running one opens
	if link, ok := running(dir); ok {
		log.Printf("Already running: opening its page")
		if !*noBrowser {
			openBrowser(link)
		}
		return
	}

	cfg := loadConfig(dir)
	if *server != "" {
		cfg.Server = strings.TrimRight(*server, "/")
	}
	if cfg.Server == "" {
		cfg.Server = defaultServer
	}
	token := make([]byte, 24)
	if _, err := rand.Read(token); err != nil {
		fail(err)
	}
	app := newApp(dir, hex.EncodeToString(token), cfg)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fail(err)
	}
	app.port = ln.Addr().(*net.TCPAddr).Port
	link := app.openURL()
	lock := filepath.Join(dir, "bridge.lock")
	if err := os.WriteFile(lock, []byte(link), 0o600); err != nil {
		log.Printf("The lock file couldn't be written: %v", err)
	}
	defer os.Remove(lock)

	srv := &http.Server{Handler: app.Handler(), ReadHeaderTimeout: 10 * time.Second}
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt)
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

	log.Printf("Randogeddon Bridge %s, for %s; its page: %s", version, cfg.Server, strings.SplitN(link, "?", 2)[0])
	if !*noBrowser {
		openBrowser(link)
	} else {
		fmt.Println(link)
	}
	if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		fail(err)
	}
	app.Quit()
	log.Printf("Quit")
}

// running is the page of a bridge that runs already (its lock file, and it answers), if there is one.
func running(dir string) (string, bool) {
	data, err := os.ReadFile(filepath.Join(dir, "bridge.lock"))
	if err != nil {
		return "", false
	}
	link := strings.TrimSpace(string(data))
	base := strings.SplitN(link, "?", 2)[0]
	if !strings.HasPrefix(base, "http://127.0.0.1:") {
		return "", false
	}
	client := http.Client{Timeout: time.Second}
	resp, err := client.Get(strings.TrimRight(base, "/") + "/ping")
	if err != nil {
		return "", false
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64))
	return link, string(body) == "randogeddon-bridge"
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

// logTo writes the log to bridge.log in the config directory (new at each start) as well: on Windows the bridge has
// no console window.
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
