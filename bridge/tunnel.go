package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/coder/websocket"
)

// tunnelURL is where a Minecraft Dig slot's tunnel connects: the session's address (`wss://<session>.<domain>` or
// `ws://host:port/<session>`) with `/minecraft/<slot>` after it.
func tunnelURL(upstream, slot string) string {
	return strings.TrimRight(upstream, "/") + "/minecraft/" + url.PathEscape(slot)
}

// serveTunnel takes the Minecraft connections on [ln] (127.0.0.1): each one gets its own connection to the gateway,
// logged in with the slot's tunnel key, and every byte passes both ways in binary messages. The gateway starts the slot's
// Minecraft server when one joins and holds the join until it runs.
func (r *Relay) serveTunnel(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			if !errors.Is(err, net.ErrClosed) {
				log.Printf("tunnel %s: %v", r.Slot, err)
			}
			return
		}
		go r.tunnel(conn)
	}
}

func (r *Relay) tunnel(game net.Conn) {
	defer game.Close()
	r.note(func() { r.open++; r.detail = "" })
	defer r.note(func() { r.open-- })
	upstream, key := r.target()
	ctx, cancel := context.WithCancel(r.closing)
	defer cancel()
	// Closing the relay (or either side ending) ends the Minecraft connection too
	go func() { <-ctx.Done(); _ = game.Close() }()
	dialCtx, dialCancel := context.WithTimeout(ctx, 20*time.Second)
	server, _, err := websocket.Dial(dialCtx, tunnelURL(upstream, r.Slot), &websocket.DialOptions{
		HTTPHeader: http.Header{
			"User-Agent":               {"RandogeddonBridge/" + version},
			"X-Randogeddon-Tunnel-Key": {key},
		},
	})
	dialCancel()
	if err != nil {
		r.note(func() { r.detail = "unreachable: " + err.Error() })
		log.Printf("tunnel %s: unreachable: %v", r.Slot, err)
		return
	}
	server.SetReadLimit(maxMessage)
	defer server.CloseNow()
	r.note(func() { r.connected++ })
	defer r.note(func() { r.connected-- })
	done := make(chan struct{}, 2)
	// Minecraft's bytes to the gateway
	go func() {
		buffer := make([]byte, 32*1024)
		for {
			n, err := game.Read(buffer)
			if n > 0 {
				if server.Write(ctx, websocket.MessageBinary, buffer[:n]) != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	// The gateway's to Minecraft
	go func() {
		for {
			_, data, err := server.Read(ctx)
			if err != nil {
				break
			}
			if _, err := game.Write(data); err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	<-done
	cancel()
	_ = server.Close(websocket.StatusNormalClosure, "")
}
