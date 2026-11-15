package main

import (
	"bytes"
	"encoding/json"
	"io"
)

// fillConnect sets the slot's name and password in every Connect packet of a frame a game sent, so nothing has to be
// typed in the game. The whole frame is parsed (escapes like `Connect` included), as the gateway and
// MultiServer read it; a frame with no Connect, or that isn't a JSON array of packets, passes unchanged (the gateway
// closes a connection that sends something else).
func fillConnect(frame []byte, name, password string) ([]byte, bool) {
	dec := json.NewDecoder(bytes.NewReader(frame))
	dec.UseNumber() // numbers stay as they were written (no float rounding of big ones)
	var packets []any
	if err := dec.Decode(&packets); err != nil {
		return frame, false
	}
	// Anything after the array: not one JSON value, as MultiServer would refuse it; left alone
	if _, err := dec.Token(); err != io.EOF {
		return frame, false
	}
	changed := false
	for _, p := range packets {
		packet, ok := p.(map[string]any)
		if !ok || packet["cmd"] != "Connect" {
			continue
		}
		packet["name"] = name
		packet["password"] = password
		changed = true
	}
	if !changed {
		return frame, false
	}
	var out bytes.Buffer
	enc := json.NewEncoder(&out)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(packets); err != nil {
		return frame, false
	}
	return bytes.TrimRight(out.Bytes(), "\n"), true
}

// serverReply is what the bridge reads from the server's packets: whether the slot got in, or why not.
func serverReply(frame []byte) (connected bool, refused []string) {
	var packets []struct {
		Cmd    string   `json:"cmd"`
		Errors []string `json:"errors"`
	}
	if json.Unmarshal(frame, &packets) != nil {
		return false, nil
	}
	for _, p := range packets {
		switch p.Cmd {
		case "Connected":
			connected = true
		case "ConnectionRefused":
			refused = append(refused, p.Errors...)
			if len(p.Errors) == 0 {
				refused = append(refused, "refused")
			}
		}
	}
	return connected, refused
}
