package main

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"golang.org/x/term"
)

// cliUsage is what `randogeddon-bridge help` prints.
const cliUsage = `Randogeddon Bridge on the command line (for a computer without a browser, e.g. the one a hosted game runs on):

  randogeddon-bridge [-server <url>] login              the key from Your connection: typed (not shown), or login < keyfile
  randogeddon-bridge logout                             ends the key on the dashboard and forgets it
  randogeddon-bridge slots                              the slots it can open, per running session
  randogeddon-bridge relay -session <id> -slot <name> [-port <port>]
                                                        a relay until Ctrl+C: the game connects to localhost:<port>

Without a command it opens its page in the browser.
`

// isCommand is whether [arg] names one of the command line's commands.
func isCommand(arg string) bool {
	switch arg {
	case "login", "logout", "slots", "relay", "help":
		return true
	}
	return false
}

// runCLI runs one command with [cfg] (the server already set by -server) and returns the exit code. It logs only to
// the terminal: bridge.log stays the page's.
func runCLI(dir string, cfg Config, args []string, stdin io.Reader, out io.Writer) int {
	log.SetOutput(os.Stderr)
	if len(args) == 0 || args[0] == "help" {
		fmt.Fprint(out, cliUsage)
		return 0
	}
	err := func() error {
		switch args[0] {
		case "login":
			return cliLogin(dir, cfg, stdin, out)
		case "logout":
			return cliLogout(dir, cfg, out)
		case "slots":
			return cliSlots(cfg, out)
		case "relay":
			return cliRelay(cfg, args[1:], out)
		}
		return fmt.Errorf("unknown command %q", args[0])
	}()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Randogeddon Bridge: %v\n", err)
		return 1
	}
	return 0
}

// cliLogin reads the key from [stdin] (never an argument: others on the computer could see it in the process list).
func cliLogin(dir string, cfg Config, stdin io.Reader, out io.Writer) error {
	if err := checkServer(cfg.Server); err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "Key for %s (Your connection, Create key): ", cfg.Server)
	key, err := readKey(stdin)
	if err != nil {
		return err
	}
	if key == "" {
		return errors.New("no key given")
	}
	info, err := newClient(cfg.Server, key, "").Info()
	if err != nil {
		return err
	}
	cfg.Key = key
	if err := cfg.save(dir); err != nil {
		return err
	}
	fmt.Fprintf(out, "Logged in as %s at %s\n", info.Name, cfg.Server)
	return nil
}

// readKey reads the key: typed at a terminal it isn't shown (it is a login), otherwise one line of what comes in (a
// file or a pipe).
func readKey(stdin io.Reader) (string, error) {
	if f, ok := stdin.(*os.File); ok && term.IsTerminal(int(f.Fd())) {
		typed, err := term.ReadPassword(int(f.Fd()))
		fmt.Fprintln(os.Stderr)
		return strings.TrimSpace(string(typed)), err
	}
	line, err := bufio.NewReader(stdin).ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return "", err
	}
	return strings.TrimSpace(line), nil
}

func cliLogout(dir string, cfg Config, out io.Writer) error {
	if cfg.Key == "" {
		fmt.Fprintln(out, "Not logged in")
		return nil
	}
	if err := newClient(cfg.Server, cfg.Key, "").Logout(); err != nil {
		log.Printf("The key couldn't be ended on the dashboard: %v", err)
	}
	cfg.Key = ""
	if err := cfg.save(dir); err != nil {
		return err
	}
	fmt.Fprintln(out, "Logged out")
	return nil
}

// loggedIn is the client of [cfg], or why there is none.
func loggedIn(cfg Config) (*Client, error) {
	if cfg.Key == "" {
		return nil, errors.New("not logged in: randogeddon-bridge login")
	}
	return newClient(cfg.Server, cfg.Key, ""), nil
}

func cliSlots(cfg Config, out io.Writer) error {
	c, err := loggedIn(cfg)
	if err != nil {
		return err
	}
	sessions, err := c.Sessions()
	if err != nil {
		return err
	}
	found := false
	for _, s := range sessions {
		if !s.Offered() {
			continue
		}
		conn, err := c.Connection(s.ID)
		if err != nil {
			return err
		}
		for _, slot := range conn.Slots {
			if !slot.Usable() {
				continue
			}
			found = true
			note := ""
			if slot.Hosted {
				note = "  (hosted)"
			}
			fmt.Fprintf(out, "%s\t%s\t%s%s\n", s.ID, slot.Name, slot.Game, note)
		}
	}
	if !found {
		fmt.Fprintln(out, "No slot to open (none in a running session, or each locked)")
	}
	return nil
}

func cliRelay(cfg Config, args []string, out io.Writer) error {
	flags := flag.NewFlagSet("relay", flag.ContinueOnError)
	session := flags.String("session", "", "the session's ID")
	slotName := flags.String("slot", "", "the slot's name")
	port := flags.Int("port", 0, "the local port (default: a free one)")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *session == "" || *slotName == "" {
		return errors.New("relay needs -session and -slot (randogeddon-bridge slots lists them)")
	}
	c, err := loggedIn(cfg)
	if err != nil {
		return err
	}
	fetch := func() (Slot, string, error) { return slotOf(c, *session, *slotName) }
	slot, upstream, err := fetch()
	if err != nil {
		return err
	}
	relay, err := startRelay("1", *session, *session, slot, upstream, fetch, *port)
	if err != nil {
		return err
	}
	defer relay.Close()
	fmt.Fprintf(out, "Connect %s to localhost:%d (Ctrl+C ends it)\n", slot.Game, relay.Port)

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	// An ended key ends the relay, like the page's logout; checked beside the loop, so Ctrl+C never waits for it
	gone := make(chan struct{})
	stop := make(chan struct{})
	defer close(stop)
	go watch(stop, func() bool {
		if _, err := c.Info(); keyGone(err) {
			close(gone)
			return false
		}
		return true
	})
	last := ""
	for {
		select {
		case <-signals:
			fmt.Fprintln(out, "Relay ended")
			return nil
		case <-gone:
			return errors.New("the key was ended on the dashboard: randogeddon-bridge login")
		case <-tick.C:
			v := relay.View()
			now := v.State
			if v.Detail != "" {
				now += ": " + v.Detail
			}
			if now != last {
				fmt.Fprintf(out, "%s %s\n", time.Now().Format("15:04:05"), now)
				last = now
			}
		}
	}
}
