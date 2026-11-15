package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
)

// Config is what the bridge keeps between runs, in the user's config directory (bridge.json, readable only by them):
// the dashboard's address and the key it logs in with, until the user logs out.
type Config struct {
	Server string `json:"server"`
	Key    string `json:"key,omitempty"`
}

// configDir is Randogeddon's directory in the user's config directory (made if needed): %AppData%\Randogeddon,
// ~/Library/Application Support/Randogeddon, ~/.config/Randogeddon.
func configDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(base, "Randogeddon")
	return dir, os.MkdirAll(dir, 0o700)
}

func loadConfig(dir string) Config {
	var c Config
	if data, err := os.ReadFile(filepath.Join(dir, "bridge.json")); err == nil {
		_ = json.Unmarshal(data, &c)
	}
	return c
}

// save writes the config whole (a new file, then renamed over the old one), so a crash never leaves half of it.
func (c Config) save(dir string) error {
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	path := filepath.Join(dir, "bridge.json")
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return errors.Join(err, os.Remove(tmp))
	}
	return nil
}
