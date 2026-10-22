// Package engine owns all scheduling and pending state in one event loop.
package engine

import (
	"container/heap"
	"context"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"hash/fnv"
	"net"
	pb "routiva/collector/gen"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

type Reply struct {
	Token  uint64
	Source string
	At     time.Time
	Error  string
}
type Transport interface {
	Send(string, uint64) error
	Receive() (Reply, error)
	Close() error
}
type host struct {
	cfg      *pb.HostConfig
	next     time.Time
	sequence uint64
}
type hosts []*host

func (h hosts) Len() int           { return len(h) }
func (h hosts) Less(i, j int) bool { return h[i].next.Before(h[j].next) }
func (h hosts) Swap(i, j int)      { h[i], h[j] = h[j], h[i] }
func (h *hosts) Push(v any)        { *h = append(*h, v.(*host)) }
func (h *hosts) Pop() any          { a := *h; v := a[len(a)-1]; *h = a[:len(a)-1]; return v }

type attempt struct {
	result         *pb.Result
	sent, deadline time.Time
	timeout        time.Duration
	index          int
}
type expirations []*attempt

func (h expirations) Len() int           { return len(h) }
func (h expirations) Less(i, j int) bool { return h[i].deadline.Before(h[j].deadline) }
func (h expirations) Swap(i, j int)      { h[i], h[j] = h[j], h[i]; h[i].index = i; h[j].index = j }
func (h *expirations) Push(v any)        { a := v.(*attempt); a.index = len(*h); *h = append(*h, a) }
func (h *expirations) Pop() any {
	a := *h
	v := a[len(a)-1]
	a[len(a)-1] = nil
	*h = a[:len(a)-1]
	v.index = -1
	return v
}

type event struct {
	id    uint64
	at    time.Time
	err   string
	reply *Reply
}
type job struct {
	target  string
	id      uint64
	expires time.Time
}
type configRequest struct {
	config *pb.Configuration
	done   chan error
}
type Metrics struct{ Active, Sent, Replies, Pending, Timeouts, SendErrors, Skipped, Dropped, LagNS, ReceiveErrors, CollectionErrors, ICMPErrors, ProcessingNS, MaxLagNS atomic.Int64 }
type Engine struct {
	ID         string
	Transport  Transport
	Results    chan *pb.Result
	Metrics    Metrics
	configs    chan configRequest
	events     chan event
	sends      chan job
	pending    map[uint64]*attempt
	deadlines  expirations
	schedule   hosts
	inventory  map[string]*host
	serial     uint64
	MaxPending int
}

func New(id string, t Transport, buffer int) *Engine {
	var seed [8]byte
	if _, err := rand.Read(seed[:]); err != nil {
		panic(err)
	}
	return &Engine{serial: binary.BigEndian.Uint64(seed[:]) & ((1 << 48) - 1), ID: id, Transport: t, Results: make(chan *pb.Result, buffer), configs: make(chan configRequest), events: make(chan event, 32768), sends: make(chan job, 1024), pending: map[uint64]*attempt{}, inventory: map[string]*host{}, MaxPending: 100000}
}
func Validate(c *pb.Configuration) error {
	if len(c.Hosts) > 10000 {
		return fmt.Errorf("maximum 10000 hosts")
	}
	seen := map[string]bool{}
	for _, h := range c.Hosts {
		if h.HostId == "" || len(h.HostId) > 255 || seen[h.HostId] || parseIPv4(h.Target) == nil || h.IntervalMs < 100 || h.IntervalMs > 3600000 || h.TimeoutMs < 100 || h.TimeoutMs > 60000 {
			return fmt.Errorf("invalid host configuration: %s", h.HostId)
		}
		seen[h.HostId] = true
	}
	return nil
}
func (e *Engine) Configure(ctx context.Context, c *pb.Configuration) error {
	if err := Validate(c); err != nil {
		return err
	}
	r := configRequest{c, make(chan error, 1)}
	select {
	case e.configs <- r:
	case <-ctx.Done():
		return ctx.Err()
	}
	select {
	case err := <-r.done:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (e *Engine) configure(c *pb.Configuration, now time.Time) error {
	next := map[string]*host{}
	if !c.Replace {
		for id, h := range e.inventory {
			next[id] = h
		}
	}
	for _, id := range c.Remove {
		delete(next, id)
	}
	for _, cfg := range c.Hosts {
		if !cfg.Enabled {
			delete(next, cfg.HostId)
			continue
		}
		old := e.inventory[cfg.HostId]
		if old != nil && old.cfg.Target == cfg.Target && old.cfg.IntervalMs == cfg.IntervalMs {
			copyHost := *old
			copyHost.cfg = cfg
			next[cfg.HostId] = &copyHost
		} else {
			hash := fnv.New64a()
			hash.Write([]byte(cfg.HostId))
			phase := time.Duration(hash.Sum64() % uint64(cfg.IntervalMs*int64(time.Millisecond)))
			next[cfg.HostId] = &host{cfg: cfg, next: now.Add(phase)}
		}
	}
	if len(next) > 10000 {
		return fmt.Errorf("maximum 10000 active hosts")
	}
	e.inventory = next
	e.schedule = nil
	for _, h := range next {
		heap.Push(&e.schedule, h)
	}
	e.Metrics.Active.Store(int64(len(next)))
	return nil
}
func (e *Engine) emit(a *attempt, status pb.Status, code string, at time.Time) {
	if a.index >= 0 && a.index < len(e.deadlines) && e.deadlines[a.index] == a {
		heap.Remove(&e.deadlines, a.index)
	}
	delete(e.pending, a.result.AttemptId)
	e.Metrics.Pending.Store(int64(len(e.pending)))
	if status == pb.Status_ICMP_ERROR {
		e.Metrics.ICMPErrors.Add(1)
	}
	if status == pb.Status_COLLECTION_ERROR {
		e.Metrics.CollectionErrors.Add(1)
	}
	a.result.Status = status
	a.result.ErrorCode = code
	if !a.sent.IsZero() {
		a.result.SentAt = a.sent.UnixNano()
	}
	if status == pb.Status_SUCCESS {
		a.result.ReceivedAt = at.UnixNano()
		a.result.RttNs = at.Sub(a.sent).Nanoseconds()
	}
	select {
	case e.Results <- a.result:
	default:
		e.Metrics.Dropped.Add(1)
	}
}
func (e *Engine) step(now time.Time) {
	for len(e.deadlines) > 0 && !e.deadlines[0].deadline.After(now) {
		a := heap.Pop(&e.deadlines).(*attempt)
		if e.pending[a.result.AttemptId] != a {
			continue
		}
		if a.sent.IsZero() {
			e.emit(a, pb.Status_COLLECTION_ERROR, "SEND_QUEUE_EXPIRED", now)
		} else {
			e.Metrics.Timeouts.Add(1)
			e.emit(a, pb.Status_TIMEOUT, "", now)
		}
	}
	for len(e.schedule) > 0 && !e.schedule[0].next.After(now) {
		h := heap.Pop(&e.schedule).(*host)
		interval := time.Duration(h.cfg.IntervalMs) * time.Millisecond
		lag := now.Sub(h.next)
		e.Metrics.LagNS.Store(lag.Nanoseconds())
		for old := e.Metrics.MaxLagNS.Load(); lag.Nanoseconds() > old; old = e.Metrics.MaxLagNS.Load() {
			if e.Metrics.MaxLagNS.CompareAndSwap(old, lag.Nanoseconds()) {
				break
			}
		}
		missed := uint64(lag / interval)
		// One SKIPPED record summarizes missed slots; sequence exposes the exact gap.
		if missed > 0 {
			e.serial++
			h.sequence += missed
			e.Metrics.Skipped.Add(int64(missed))
			e.emit(&attempt{result: &pb.Result{CollectorId: e.ID, HostId: h.cfg.HostId, Target: h.cfg.Target, LatencyLimitMs: h.cfg.LatencyLimitMs, AttemptId: e.serial, Sequence: h.sequence, ScheduledAt: h.next.UnixNano()}}, pb.Status_SKIPPED, fmt.Sprintf("SCHEDULER_LATE:%d", missed), now)
			h.next = h.next.Add(time.Duration(missed) * interval)
		}
		e.serial++
		h.sequence++
		a := &attempt{index: -1, timeout: time.Duration(h.cfg.TimeoutMs) * time.Millisecond, result: &pb.Result{CollectorId: e.ID, HostId: h.cfg.HostId, Target: h.cfg.Target, LatencyLimitMs: h.cfg.LatencyLimitMs, AttemptId: e.serial, Sequence: h.sequence, ScheduledAt: h.next.UnixNano()}, deadline: now.Add(time.Duration(h.cfg.TimeoutMs) * time.Millisecond)}
		if len(e.pending) >= e.MaxPending {
			e.Metrics.Skipped.Add(1)
			e.emit(a, pb.Status_SKIPPED, "PENDING_LIMIT", now)
		} else {
			select {
			case e.sends <- job{h.cfg.Target, e.serial, a.deadline}:
				e.pending[e.serial] = a
				heap.Push(&e.deadlines, a)
			default:
				e.Metrics.Skipped.Add(1)
				e.emit(a, pb.Status_SKIPPED, "SEND_QUEUE_FULL", now)
			}
		}
		h.next = h.next.Add(interval)
		heap.Push(&e.schedule, h)
	}
	e.Metrics.Pending.Store(int64(len(e.pending)))
}
func (e *Engine) handle(v event) {
	if v.id == 0 && v.err != "" {
		for _, a := range e.pending {
			e.emit(a, pb.Status_COLLECTION_ERROR, v.err, v.at)
		}
		return
	}
	if v.reply != nil {
		r := v.reply
		e.Metrics.ProcessingNS.Store(time.Since(r.At).Nanoseconds())
		a := e.pending[r.Token]
		if a == nil || a.sent.IsZero() || parseIPv4(a.result.Target).String() != r.Source {
			return
		}
		if r.At.After(a.deadline) {
			return
		}
		if r.Error != "" {
			e.emit(a, pb.Status_ICMP_ERROR, r.Error, r.At)
		} else {
			e.Metrics.Replies.Add(1)
			e.emit(a, pb.Status_SUCCESS, "", r.At)
		}
		return
	}
	a := e.pending[v.id]
	if a == nil {
		return
	}
	if v.err != "" {
		e.Metrics.SendErrors.Add(1)
		e.emit(a, pb.Status_SEND_ERROR, v.err, v.at)
		return
	}
	// The sender posts this event before writing, so replies cannot overtake it.
	a.sent = v.at
	a.deadline = v.at.Add(a.timeout)
	heap.Fix(&e.deadlines, a.index)
}
func (e *Engine) Run(ctx context.Context) {
	defer e.Transport.Close()
	defer func() {
		for _, a := range e.pending {
			e.emit(a, pb.Status_COLLECTION_ERROR, "COLLECTOR_SHUTDOWN", time.Now())
		}
	}()
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case j := <-e.sends:
				at := time.Now()
				if at.After(j.expires) {
					continue
				}
				select {
				case e.events <- event{id: j.id, at: at}:
				case <-ctx.Done():
					return
				}
				if err := e.Transport.Send(j.target, j.id); err != nil {
					select {
					case e.events <- event{id: j.id, at: time.Now(), err: "ICMP_WRITE"}:
					case <-ctx.Done():
						return
					}
				} else {
					e.Metrics.Sent.Add(1)
				}
			}
		}
	}()
	go func() {
		for {
			r, err := e.Transport.Receive()
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				e.Metrics.ReceiveErrors.Add(1)
				select {
				case e.events <- event{err: "ICMP_RECEIVE", at: time.Now()}:
				case <-ctx.Done():
					return
				}
				select {
				case <-time.After(100 * time.Millisecond):
				case <-ctx.Done():
					return
				}
				continue
			}
			select {
			case e.events <- event{reply: &r}:
			case <-ctx.Done():
				return
			}
		}
	}()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case r := <-e.configs:
			r.done <- e.configure(r.config, time.Now())
		case v := <-e.events:
			e.handle(v)
		case <-ticker.C:
			for n := len(e.events); n > 0; n-- {
				e.handle(<-e.events)
			}
			e.step(time.Now())
		}
	}
}
func Token(data []byte) (uint64, bool) {
	if len(data) != 16 || string(data[:8]) != "ROUTIVA1" {
		return 0, false
	}
	return binary.BigEndian.Uint64(data[8:]), true
}

// Preserve the inventory's spelling in storage while normalizing the wire address.
func parseIPv4(target string) net.IP {
	if ip := net.ParseIP(target).To4(); ip != nil {
		return ip
	}
	parts := strings.Split(target, ".")
	if len(parts) != 4 {
		return nil
	}
	ip := make(net.IP, 4)
	for i, p := range parts {
		if len(p) < 1 || len(p) > 3 {
			return nil
		}
		for _, r := range p {
			if r < '0' || r > '9' {
				return nil
			}
		}
		v, err := strconv.Atoi(p)
		if err != nil || v > 255 {
			return nil
		}
		ip[i] = byte(v)
	}
	return ip
}
