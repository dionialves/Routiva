package server

import (
	"context"
	"fmt"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	pb "routiva/collector/gen"
	"routiva/collector/internal/engine"
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type Server struct {
	pb.UnimplementedCollectorServer
	Engine                        *engine.Engine
	BatchSize, MaxBatches         int
	Flush                         time.Duration
	mu                            sync.Mutex
	batches                       []*pb.Batch
	serial                        uint64
	connected                     atomic.Bool
	connections, discarded, ackNS atomic.Int64
}

func New(e *engine.Engine, size, max int, flush time.Duration) *Server {
	return &Server{Engine: e, BatchSize: size, MaxBatches: max, Flush: flush}
}
func (s *Server) Run(ctx context.Context) {
	t := time.NewTicker(s.Flush)
	defer t.Stop()
	var results []*pb.Result
	flush := func() {
		if len(results) == 0 {
			return
		}
		s.mu.Lock()
		s.serial++
		if len(s.batches) >= s.MaxBatches {
			s.discarded.Add(int64(len(results)))
		} else {
			s.batches = append(s.batches, &pb.Batch{CollectorId: s.Engine.ID, BatchId: s.serial, ProtocolVersion: 1, Results: results})
		}
		s.mu.Unlock()
		results = nil
	}
	for {
		select {
		case <-ctx.Done():
			flush()
			return
		case r := <-s.Engine.Results:
			results = append(results, r)
			if len(results) >= s.BatchSize {
				flush()
			}
		case <-t.C:
			flush()
		}
	}
}
func (s *Server) Connect(stream pb.Collector_ConnectServer) error {
	if !s.connected.CompareAndSwap(false, true) {
		return status.Error(codes.AlreadyExists, "one backend per collector")
	}
	defer s.connected.Store(false)
	s.connections.Add(1)
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
				errors <- status.Error(codes.FailedPrecondition, "protocol version must be 1")
				return
			}
			if cfg := c.GetConfig(); cfg != nil {
				if err = s.Engine.Configure(ctx, cfg); err != nil {
					errors <- status.Error(codes.InvalidArgument, err.Error())
					return
				}
				if !initialized {
					initialized = true
					close(ready)
				}
			}
			if ack := c.GetAck(); ack != nil {
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
		return status.Error(codes.DeadlineExceeded, "configuration required")
	}
	ticker := time.NewTicker(s.Flush)
	defer ticker.Stop()
	var pending *pb.Batch
	var sent time.Time
	for {
		if pending == nil {
			s.mu.Lock()
			if len(s.batches) > 0 {
				pending = s.batches[0]
			}
			s.mu.Unlock()
			if pending != nil {
				sent = time.Now()
				if err := stream.Send(pending); err != nil {
					return err
				}
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case err := <-errors:
			return err
		case ack := <-acks:
			if pending == nil || ack.CollectorId != s.Engine.ID || ack.BatchId != pending.BatchId {
				return status.Error(codes.InvalidArgument, "unexpected acknowledgement")
			}
			s.ackNS.Store(time.Since(sent).Nanoseconds())
			s.mu.Lock()
			s.batches[0] = nil
			s.batches = s.batches[1:]
			s.mu.Unlock()
			pending = nil
		case <-ticker.C:
			if pending != nil && time.Since(sent) > 30*time.Second {
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
	metrics := map[string]float64{"cpu_seconds": cpu, "collection_errors_total": float64(m.CollectionErrors.Load()), "icmp_errors_total": float64(m.ICMPErrors.Load()), "processing_latency_ns": float64(m.ProcessingNS.Load()), "scheduler_max_lag_ns": float64(m.MaxLagNS.Load()), "active_hosts": float64(m.Active.Load()), "sent_total": float64(m.Sent.Load()), "replies_total": float64(m.Replies.Load()), "pending": float64(m.Pending.Load()), "timeouts_total": float64(m.Timeouts.Load()), "send_errors_total": float64(m.SendErrors.Load()), "skipped_total": float64(m.Skipped.Load()), "scheduler_lag_ns": float64(m.LagNS.Load()), "receive_errors_total": float64(m.ReceiveErrors.Load()), "dropped_total": float64(m.Dropped.Load() + s.discarded.Load()), "result_buffer": float64(buffer + len(s.Engine.Results)), "heap_bytes": float64(mem.HeapAlloc), "goroutines": float64(runtime.NumGoroutine()), "connections_total": float64(s.connections.Load()), "ack_latency_ns": float64(s.ackNS.Load())}
	return &pb.HealthReply{CollectorId: s.Engine.ID, ProtocolVersion: 1, Metrics: metrics}, nil
}
func (s *Server) String() string { return fmt.Sprintf("collector %s", s.Engine.ID) }

func (s *Server) Buffered() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.batches) + len(s.Engine.Results)
}
