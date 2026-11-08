package server

import (
	"context"
	"fmt"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"log/slog"
	pb "routiva/collector/gen"
	"routiva/collector/internal/audit"
	"routiva/collector/internal/engine"
	"routiva/collector/internal/spool"
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type Server struct {
	forming       atomic.Int64
	storageFailed atomic.Bool
	pb.UnimplementedCollectorServer
	Engine                        *engine.Engine
	Queue                         *spool.Queue
	storageErrors                 atomic.Int64
	BatchSize, MaxBatches         int
	Flush                         time.Duration
	Audit                         *audit.Logger
	sends                         map[uint64]int
	inFlight                      uint64
	mu                            sync.Mutex
	batches                       []*pb.Batch
	serial                        uint64
	connected                     atomic.Bool
	connections, discarded, ackNS atomic.Int64
}

func New(e *engine.Engine, size, max int, flush time.Duration) *Server {
	return &Server{Engine: e, BatchSize: size, MaxBatches: max, Flush: flush, sends: make(map[uint64]int)}
}
func (s *Server) log(level slog.Level, event, message string, args ...any) {
	s.Audit.Event(level, event, message, append([]any{"collector_id", s.Engine.ID}, args...)...)
}

// queueFields is called with mu held. The in-flight batch remains retained.
func (s *Server) queueFields() []any {
	if s.Queue != nil {
		stats, err := s.Queue.Stats()
		if err != nil {
			return []any{"storage_error", err}
		}
		return []any{"retained_batches", stats.Batches, "retained_results", stats.Results, "queue_bytes", stats.Bytes, "in_flight_batch_id", s.inFlight, "queue_dropped_total", stats.Dropped}
	}
	waiting := len(s.batches)
	retainedResults := 0
	for _, batch := range s.batches {
		retainedResults += len(batch.Results)
	}
	ack := 0
	if s.inFlight != 0 {
		waiting--
		ack = 1
	}
	return []any{"retained_batches", len(s.batches), "retained_results", retainedResults, "waiting_to_send", waiting, "awaiting_ack", ack, "in_flight_batch_id", s.inFlight}
}
func (s *Server) totalDropped() int64 {
	total := s.discarded.Load() + s.Engine.Metrics.Dropped.Load()
	if s.Queue != nil {
		if stats, err := s.Queue.Stats(); err == nil {
			total += stats.Dropped
		}
	}
	return total
}
func (s *Server) Run(ctx context.Context) {
	t := time.NewTicker(s.Flush)
	defer t.Stop()
	summary := time.NewTicker(10 * time.Second)
	defer summary.Stop()
	var results []*pb.Result
	flush := func(reason string) {
		if len(results) == 0 {
			return
		}
		s.mu.Lock()
		s.serial++
		if s.Queue != nil {
			dropped, err := s.Queue.Append(results, s.inFlight)
			if err != nil {
				s.storageErrors.Add(1)
				s.storageFailed.Store(true)
				s.log(slog.LevelError, "SPOOL_WRITE_FAILED", "Local persistence failed; retrying with bounded buffers", "error", err)
				s.mu.Unlock()
				return
			}
			s.storageFailed.Store(false)
			if dropped > 0 {
				s.log(slog.LevelWarn, "BATCH_DROPPED", "Queue retention limit exceeded", "results", dropped, "reason", "SPOOL_LIMIT")
			}
			s.log(slog.LevelInfo, "BATCH_CREATED", "Batch persisted", s.queueFields()...)
		} else if len(s.batches) >= s.MaxBatches {
			s.discarded.Add(int64(len(results)))
			s.log(slog.LevelWarn, "BATCH_DROPPED", "New results dropped; retained batches preserved", append(s.queueFields(), "batch_id", s.serial, "results", len(results), "reason", "BATCH_LIMIT", "dropped_total", s.discarded.Load())...)
		} else {
			s.batches = append(s.batches, &pb.Batch{CollectorId: s.Engine.ID, BatchId: s.serial, ProtocolVersion: 1, Results: results})
			s.log(slog.LevelInfo, "BATCH_CREATED", "Batch queued", append(s.queueFields(), "batch_id", s.serial, "results", len(results), "reason", reason)...)
		}
		s.mu.Unlock()
		results = nil
		s.forming.Store(0)
	}
	for {
		input := s.Engine.Results
		if len(results) >= s.BatchSize {
			input = nil
		}
		select {
		case <-ctx.Done():
			for len(s.Engine.Results) > 0 {
				if len(results) >= s.BatchSize {
					flush("SHUTDOWN")
					if len(results) > 0 {
						break
					}
				}
				results = append(results, <-s.Engine.Results)
			}
			flush("SHUTDOWN")
			if len(results) > 0 {
				s.log(slog.LevelError, "SHUTDOWN_UNPERSISTED", "Disk failure prevented final persistence", "results", len(results))
			}
			return
		case r := <-input:
			results = append(results, r)
			s.forming.Store(int64(len(results)))
			if len(results) >= s.BatchSize {
				flush("SIZE")
			}
		case <-summary.C:
			s.mu.Lock()
			s.log(slog.LevelInfo, "QUEUE_STATUS", "Batch queue snapshot", append(s.queueFields(), "forming_results", len(results), "engine_buffer_results", len(s.Engine.Results), "dropped_total", s.totalDropped())...)
			s.mu.Unlock()
		case <-t.C:
			flush("FLUSH")
		}
	}
}
func (s *Server) Connect(stream pb.Collector_ConnectServer) (retErr error) {
	if !s.connected.CompareAndSwap(false, true) {
		s.log(slog.LevelWarn, "CONNECTION_REJECTED", "Another backend is connected")
		return status.Error(codes.AlreadyExists, "one backend per collector")
	}
	session := s.connections.Add(1)
	s.log(slog.LevelInfo, "CONNECTION_ACCEPTED", "Java opened the Connect stream", "session_id", session)
	defer func() {
		s.mu.Lock()
		pending := s.inFlight
		s.inFlight = 0
		s.log(slog.LevelInfo, "CONNECTION_CLOSED", "Unacknowledged batches retained for replay", append(s.queueFields(), "session_id", session, "unacknowledged_batch_id", pending, "error", retErr)...)
		s.mu.Unlock()
		s.connected.Store(false)
	}()
	ctx, cancel := context.WithCancel(stream.Context())
	defer cancel()
	errors := make(chan error, 1)
	ready := make(chan struct{})
	acks := make(chan *pb.Ack, 16)
	go func() {
		initialized := false
		for {
			c, err := stream.Recv()
			if err != nil {
				errors <- err
				return
			}
			if c.ProtocolVersion != 1 {
				s.log(slog.LevelWarn, "PROTOCOL_REJECTED", "Unsupported protocol version", "session_id", session, "protocol_version", c.ProtocolVersion)
				errors <- status.Error(codes.FailedPrecondition, "protocol version must be 1")
				return
			}
			if cfg := c.GetConfig(); cfg != nil {
				s.log(slog.LevelInfo, "CONFIG_RECEIVED", "Configuration received", "session_id", session, "replace", cfg.Replace, "hosts", len(cfg.Hosts), "removed_hosts", len(cfg.Remove))
				if err = s.Engine.Configure(ctx, cfg); err != nil {
					s.log(slog.LevelWarn, "CONFIG_REJECTED", "Invalid configuration", "session_id", session)
					errors <- status.Error(codes.InvalidArgument, err.Error())
					return
				}
				s.log(slog.LevelInfo, "CONFIG_APPLIED", "Configuration applied", "session_id", session, "active_hosts", s.Engine.Metrics.Active.Load())
				if !initialized {
					initialized = true
					close(ready)
				}
			}
			if ack := c.GetAck(); ack != nil {
				s.log(slog.LevelInfo, "ACK_RECEIVED", "Acknowledgement received", "session_id", session, "batch_id", ack.BatchId, "ack_collector_id", ack.CollectorId)
				select {
				case acks <- ack:
				case <-ctx.Done():
					return
				}
			}
		}
	}()
	select {
	case <-ready:
	case err := <-errors:
		return err
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(10 * time.Second):
		s.log(slog.LevelWarn, "CONFIG_TIMEOUT", "Initial configuration deadline exceeded", "session_id", session)
		return status.Error(codes.DeadlineExceeded, "configuration required")
	}
	ticker := time.NewTicker(s.Flush)
	defer ticker.Stop()
	var pending *pb.Batch
	var sent time.Time
	for {
		if pending == nil {
			s.mu.Lock()
			if s.Queue != nil {
				var err error
				pending, err = s.Queue.Peek()
				if err != nil {
					s.storageErrors.Add(1)
					s.mu.Unlock()
					return status.Error(codes.Internal, "spool read failed")
				}
			} else if len(s.batches) > 0 {
				pending = s.batches[0]
			}
			if pending != nil {
				s.inFlight = pending.BatchId
				if _, exists := s.sends[pending.BatchId]; !exists {
					s.sends = make(map[uint64]int)
				}
				s.sends[pending.BatchId]++
				s.log(slog.LevelInfo, "SEND_STARTED", "Sending batch", append(s.queueFields(), "batch_id", pending.BatchId, "send_attempt", s.sends[pending.BatchId])...)
			}
			s.mu.Unlock()
			if pending != nil {
				sent = time.Now()
				if err := stream.Send(pending); err != nil {
					s.log(slog.LevelWarn, "SEND_FAILED", "Batch send failed; retained for replay", "session_id", session, "batch_id", pending.BatchId, "error", err)
					return err
				}
				s.log(slog.LevelInfo, "SEND_COMPLETED", "gRPC accepted the send; awaiting ACK", "session_id", session, "batch_id", pending.BatchId)
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case err := <-errors:
			return err
		case ack := <-acks:
			if pending == nil || ack.CollectorId != s.Engine.ID || ack.BatchId != pending.BatchId {
				s.log(slog.LevelWarn, "ACK_REJECTED", "Unexpected acknowledgement; batch retained", "session_id", session, "batch_id", ack.BatchId)
				return status.Error(codes.InvalidArgument, "unexpected acknowledgement")
			}
			s.ackNS.Store(time.Since(sent).Nanoseconds())
			s.log(slog.LevelInfo, "ACK_VALIDATED", "Java confirmed persistence; ACK identity is valid", "session_id", session, "batch_id", pending.BatchId, "ack_ms", float64(s.ackNS.Load())/1e6)
			s.mu.Lock()
			if s.Queue != nil {
				if err := s.Queue.Ack(pending.BatchId); err != nil {
					s.storageErrors.Add(1)
					s.mu.Unlock()
					return status.Error(codes.Internal, "spool ACK failed")
				}
			} else {
				s.batches[0] = nil
				s.batches = s.batches[1:]
			}
			delete(s.sends, pending.BatchId)
			s.inFlight = 0
			s.log(slog.LevelInfo, "BATCH_REMOVED", "Confirmed batch removed from queue", append(s.queueFields(), "batch_id", pending.BatchId, "results", len(pending.Results))...)
			s.mu.Unlock()
			pending = nil
		case <-ticker.C:
			if pending != nil && time.Since(sent) > 30*time.Second {
				s.log(slog.LevelWarn, "ACK_TIMEOUT", "Acknowledgement deadline exceeded; batch retained", "session_id", session, "batch_id", pending.BatchId)
				return status.Error(codes.DeadlineExceeded, "commit acknowledgement timeout")
			}
		}
	}
}
func (s *Server) Health(context.Context, *pb.HealthRequest) (*pb.HealthReply, error) {
	var usage syscall.Rusage
	_ = syscall.Getrusage(syscall.RUSAGE_SELF, &usage)
	cpu := float64(usage.Utime.Sec+usage.Stime.Sec) + float64(usage.Utime.Usec+usage.Stime.Usec)/1e6
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)
	m := &s.Engine.Metrics
	s.mu.Lock()
	buffer := 0
	for _, b := range s.batches {
		buffer += len(b.Results)
	}
	s.mu.Unlock()

	extra := map[string]float64{"storage_errors_total": float64(s.storageErrors.Load()), "forming_results": float64(s.forming.Load())}
	extra["persistence_healthy"] = 1
	if s.storageFailed.Load() {
		extra["persistence_healthy"] = 0
	}
	if s.Queue != nil {
		stats, err := s.Queue.Stats()
		if err != nil {
			return nil, status.Error(codes.Internal, "spool health failed")
		}
		buffer = int(stats.Results)
		extra["queue_bytes"] = float64(stats.Bytes)
		extra["queue_max_bytes"] = float64(s.Queue.MaxBytes)
		extra["queue_batches"] = float64(stats.Batches)
		extra["queue_dropped_total"] = float64(stats.Dropped)
		if !stats.Oldest.IsZero() {
			extra["queue_oldest_age_seconds"] = time.Since(stats.Oldest).Seconds()
		}
	}
	metrics := map[string]float64{"cpu_seconds": cpu, "collection_errors_total": float64(m.CollectionErrors.Load()), "icmp_errors_total": float64(m.ICMPErrors.Load()), "processing_latency_ns": float64(m.ProcessingNS.Load()), "scheduler_max_lag_ns": float64(m.MaxLagNS.Load()), "active_hosts": float64(m.Active.Load()), "sent_total": float64(m.Sent.Load()), "replies_total": float64(m.Replies.Load()), "pending": float64(m.Pending.Load()), "timeouts_total": float64(m.Timeouts.Load()), "send_errors_total": float64(m.SendErrors.Load()), "skipped_total": float64(m.Skipped.Load()), "scheduler_lag_ns": float64(m.LagNS.Load()), "receive_errors_total": float64(m.ReceiveErrors.Load()), "dropped_total": float64(m.Dropped.Load() + s.discarded.Load()), "result_buffer": float64(buffer + len(s.Engine.Results)), "heap_bytes": float64(mem.HeapAlloc), "goroutines": float64(runtime.NumGoroutine()), "connections_total": float64(s.connections.Load()), "ack_latency_ns": float64(s.ackNS.Load())}
	for key, value := range extra {
		metrics[key] = value
	}
	if s.Queue != nil {
		metrics["dropped_total"] += extra["queue_dropped_total"]
	}
	return &pb.HealthReply{CollectorId: s.Engine.ID, ProtocolVersion: 1, Metrics: metrics}, nil
}
func (s *Server) String() string { return fmt.Sprintf("collector %s", s.Engine.ID) }

func (s *Server) Buffered() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.Queue != nil {
		stats, err := s.Queue.Stats()
		if err != nil {
			return 1
		}
		return int(stats.Batches) + len(s.Engine.Results) + int(s.forming.Load())
	}
	return len(s.batches) + len(s.Engine.Results)
}
