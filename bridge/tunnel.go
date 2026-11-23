package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"

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
	if !r.enter() {
		log.Printf("tunnel %s: refused a Minecraft connection (%d open already)", r.Slot, maxGames)
		return
	}
	defer r.leave()
	upstream, key := r.target()
	ctx, cancel := context.WithCancel(r.closing)
	defer cancel()
	// Closing the relay (or either side ending) ends the Minecraft connection too
	go func() { <-ctx.Done(); _ = game.Close() }()
	address := tunnelURL(upstream, r.Slot)
	server, err := dial(ctx, address, websocket.DialOptions{HTTPHeader: http.Header{"X-Randogeddon-Tunnel-Key": {key}}})
	if err != nil {
		r.unreachable("tunnel", address, err)
		return
	}
	defer server.CloseNow()
	// Through once the gateway sent something: a refusal (a wrong key, a locked slot) closes it before that
	var through atomic.Bool
	var refusal error // why the gateway's side ended
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
				refusal = err
				break
			}
			if through.CompareAndSwap(false, true) {
				r.note(func() { r.connected++; r.detail = "" })
			}
			if _, err := game.Write(data); err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	<-done
	// Closed with a code first, cancelled after (a cancelled read would end the connection without one)
	_ = server.Close(websocket.StatusNormalClosure, "")
	cancel()
	<-done
	if through.Load() {
		r.note(func() { r.connected-- })
	} else {
		r.refusedTunnel(refusal)
	}
}

// refusedTunnel notes the gateway's refusal of a tunnel (it closes with its reason before sending anything), for the
// page; a wrong key may be one made from a renewed password, so the slot is fetched again.
func (r *Relay) refusedTunnel(err error) {
	var closed websocket.CloseError
	if !errors.As(err, &closed) {
		return
	}
	switch closed.Code {
	case websocket.StatusNormalClosure, websocket.StatusGoingAway, websocket.StatusNoStatusRcvd, websocket.StatusAbnormalClosure:
		return
	}
	reason := closed.Reason
	if reason == "" {
		reason = fmt.Sprintf("refused (%d)", int(closed.Code))
	}
	r.refused("tunnel", reason)
	if closed.Code == websocket.StatusPolicyViolation {
		r.refreshSoon()
	}
}
