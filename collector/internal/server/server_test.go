package server

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"fmt"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/test/bufconn"
	"io"
	sqlite "modernc.org/sqlite"
	"net"
	"os"
	"path/filepath"
	pb "routiva/collector/gen"
	"routiva/collector/internal/audit"
	"routiva/collector/internal/engine"
	"routiva/collector/internal/spool"
	"strings"
	"sync"
	"testing"
	"time"
)

type silent struct{ done chan struct{} }

func (s *silent) Send(string, uint64) error            { return nil }
func (s *silent) Receive() (engine.Reply, error)       { <-s.done; return engine.Reply{}, io.EOF }
func (s *silent) Close() error                         { close(s.done); return nil }
func TestStreamingReplayAndAck(t *testing.T)           { testStreamingReplay(t, false) }
func TestPersistentStreamingReplayAndAck(t *testing.T) { testStreamingReplay(t, true) }
func testStreamingReplay(t *testing.T, persistent bool) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	id := "boot"
	var queue *spool.Queue
	if persistent {
		var err error
		queue, err = spool.Open(filepath.Join(t.TempDir(), "queue.db"), 1<<20, time.Hour)
		if err != nil {
			t.Fatal(err)
		}
		defer queue.Close()
		id = queue.ID
	}
	e := engine.New(id, &silent{make(chan struct{})}, 8)
	s := New(e, 2, 2, 5*time.Millisecond)
	s.Queue = queue
	logPath := filepath.Join(t.TempDir(), "collector.log")
	logger, err := audit.Open(logPath, 1<<20, 2)
	if err != nil {
		t.Fatal(err)
	}
	s.Audit = logger
	defer logger.Close()
	go e.Run(ctx)
	done := make(chan struct{})
	go func() { defer close(done); s.Run(ctx) }()
	defer func() { cancel(); <-done }()
	lis := bufconn.Listen(1 << 20)
	g := grpc.NewServer()
	pb.RegisterCollectorServer(g, s)
	go g.Serve(lis)
	defer g.Stop()
	conn, err := grpc.NewClient("passthrough:///test", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithContextDialer(func(context.Context, string) (net.Conn, error) { return lis.Dial() }))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	client := pb.NewCollectorClient(conn)
	open := func() (pb.Collector_ConnectClient, context.CancelFunc) {
		c, stop := context.WithTimeout(ctx, 3*time.Second)
		stream, err := client.Connect(c)
		if err != nil {
			t.Fatal(err)
		}
		if err = stream.Send(&pb.Command{ProtocolVersion: 1, Body: &pb.Command_Config{Config: &pb.Configuration{Replace: true}}}); err != nil {
			t.Fatal(err)
		}
		return stream, stop
	}
	stream, stop := open()
	e.Results <- &pb.Result{CollectorId: id, AttemptId: 1}
	e.Results <- &pb.Result{CollectorId: id, AttemptId: 2}
	first, err := stream.Recv()
	if err != nil {
		t.Fatal(err)
	}
	stop()
	deadline := time.Now().Add(time.Second)
	for s.connected.Load() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	stream, stop = open()
	defer stop()
	replay, err := stream.Recv()
	if err != nil {
		t.Fatal(err)
	}
	if replay.BatchId != first.BatchId || len(replay.Results) != 2 {
		t.Fatal("identity changed on replay")
	}
	if err = stream.Send(&pb.Command{ProtocolVersion: 1, Body: &pb.Command_Ack{Ack: &pb.Ack{CollectorId: id, BatchId: replay.BatchId}}}); err != nil {
		t.Fatal(err)
	}
	deadline = time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		n := len(s.batches)
		if queue != nil {
			stats, err := queue.Stats()
			if err != nil {
				t.Fatal(err)
			}
			n = int(stats.Batches)
		}
		s.mu.Unlock()
		if n == 0 {
			logger.Close()
			data, err := os.ReadFile(logPath)
			if err != nil {
				t.Fatal(err)
			}
			log := string(data)
			for _, event := range []string{"BATCH_CREATED", "SEND_STARTED", "SEND_COMPLETED", "ACK_RECEIVED", "ACK_VALIDATED", "BATCH_REMOVED"} {
				if !strings.Contains(log, "event="+event) {
					t.Fatalf("missing %s: %s", event, log)
				}
			}
			if !strings.Contains(log, "send_attempt=2") {
				t.Fatal("replay not logged")
			}
			if strings.Contains(log, "attempt_id=") || strings.Contains(log, "host_id=") {
				t.Fatal("individual result leaked into audit")
			}
			if strings.Index(log, "event=ACK_VALIDATED") > strings.Index(log, "event=BATCH_REMOVED") {
				t.Fatal("batch removed before ACK validation")
			}
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("ack did not release replay buffer")
}
func TestOfflineBufferIsBounded(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	e := engine.New("x", nil, 10)
	s := New(e, 1, 2, time.Millisecond)
	logPath := filepath.Join(t.TempDir(), "collector.log")
	logger, err := audit.Open(logPath, 1<<20, 2)
	if err != nil {
		t.Fatal(err)
	}
	s.Audit = logger
	defer logger.Close()
	go s.Run(ctx)
	for i := 0; i < 10; i++ {
		e.Results <- &pb.Result{AttemptId: uint64(i + 1)}
	}
	deadline := time.Now().Add(time.Second)
	for s.discarded.Load() < 8 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if s.discarded.Load() != 8 {
		t.Fatal("missing discard accounting")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.batches) != 2 {
		t.Fatal("buffer not bounded")
	}
	logger.Close()
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(string(data), "event=BATCH_DROPPED") != 8 {
		t.Fatal("discard events missing")
	}
	if !strings.Contains(string(data), "reason=BATCH_LIMIT") {
		t.Fatal("discard reason missing")
	}
}

func TestNegotiatedWindowBackpressureAndReplay(t *testing.T) {
	for _, window := range []int{1, 4} {
		t.Run(fmt.Sprint(window), func(t *testing.T) {
			q, err := spool.Open(filepath.Join(t.TempDir(), "queue.db"), 1<<20, time.Hour)
			if err != nil {
				t.Fatal(err)
			}
			defer q.Close()
			for i := 0; i < window+1; i++ {
				if _, err = q.Append([]*pb.Result{{CollectorId: q.ID, AttemptId: uint64(i + 1)}}, 0); err != nil {
					t.Fatal(err)
				}
			}
			logger, err := audit.Open(filepath.Join(t.TempDir(), "audit.log"), 1<<20, 2)
			if err != nil {
				t.Fatal(err)
			}
			defer logger.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			e := engine.New(q.ID, &silent{make(chan struct{})}, 1)
			engineDone := make(chan struct{})
			go func() { defer close(engineDone); e.Run(ctx) }()
			defer func() { cancel(); <-engineDone }()
			s := New(e, 1, 0, time.Millisecond)
			s.Queue = q
			s.Audit = logger
			listener := bufconn.Listen(1 << 20)
			g := grpc.NewServer()
			pb.RegisterCollectorServer(g, s)
			go g.Serve(listener)
			defer g.Stop()
			conn, err := grpc.NewClient("passthrough:///window", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithContextDialer(func(context.Context, string) (net.Conn, error) { return listener.Dial() }))
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			open := func() (pb.Collector_ConnectClient, context.CancelFunc) {
				request, stop := context.WithTimeout(ctx, 5*time.Second)
				if window > 1 {
					request = metadata.AppendToOutgoingContext(request, "x-routiva-window", fmt.Sprint(window))
				}
				stream, err := pb.NewCollectorClient(conn).Connect(request)
				if err != nil {
					t.Fatal(err)
				}
				if err = stream.Send(&pb.Command{ProtocolVersion: 1, Body: &pb.Command_Config{Config: &pb.Configuration{Replace: true}}}); err != nil {
					t.Fatal(err)
				}
				return stream, stop
			}
			stream, stop := open()
			for i := 1; i <= window; i++ {
				b, err := stream.Recv()
				if err != nil || b.BatchId != uint64(i) {
					t.Fatal(b, err)
				}
			}
			next := make(chan *pb.Batch, 1)
			nextError := make(chan error, 1)
			go func() {
				b, err := stream.Recv()
				if err != nil {
					nextError <- err
				} else {
					next <- b
				}
			}()
			select {
			case <-next:
				t.Fatal("window exceeded before ACK")
			case err := <-nextError:
				t.Fatal(err)
			case <-time.After(50 * time.Millisecond):
			}
			if err = stream.Send(&pb.Command{ProtocolVersion: 1, Body: &pb.Command_Ack{Ack: &pb.Ack{CollectorId: q.ID, BatchId: 1}}}); err != nil {
				t.Fatal(err)
			}
			select {
			case b := <-next:
				if b.BatchId != uint64(window+1) {
					t.Fatal(b)
				}
			case err := <-nextError:
				t.Fatal(err)
			case <-time.After(time.Second):
				t.Fatal("ACK did not release a delivery slot")
			}
			stop()
			deadline := time.Now().Add(time.Second)
			for s.connected.Load() && time.Now().Before(deadline) {
				time.Sleep(time.Millisecond)
			}
			stream, stop = open()
			defer stop()
			for i := 2; i <= window+1; i++ {
				b, err := stream.Recv()
				if err != nil || b.BatchId != uint64(i) {
					t.Fatal("replay identity changed", b, err)
				}
			}
			if err = stream.Send(&pb.Command{ProtocolVersion: 1, Body: &pb.Command_Ack{Ack: &pb.Ack{CollectorId: q.ID, BatchId: uint64(window + 2)}}}); err != nil {
				t.Fatal(err)
			}
			if _, err = stream.Recv(); err == nil {
				t.Fatal("invalid ACK accepted")
			}
			stats, err := q.Stats()
			if err != nil || stats.Batches != int64(window) {
				t.Fatal("unacknowledged batches lost", stats, err)
			}
		})
	}
}

// Reproduce slow storage inside SQLite, without sleeping while holding the server mutex.
func TestHealthRemainsAvailableWhileSQLiteInsertIsBlocked(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	var once sync.Once
	function := fmt.Sprintf("spool_block_%d", time.Now().UnixNano())
	if err := sqlite.RegisterScalarFunction(function, 0, func(*sqlite.FunctionContext, []driver.Value) (driver.Value, error) {
		close(started)
		<-release
		return int64(1), nil
	}); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "queue.db")
	q, err := spool.Open(path, 1<<20, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	defer q.Close()
	admin, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = admin.Exec("CREATE TRIGGER slow_storage BEFORE INSERT ON batches BEGIN SELECT " + function + "(); END"); err != nil {
		admin.Close()
		t.Fatal(err)
	}
	admin.Close()
	logger, err := audit.Open(filepath.Join(t.TempDir(), "audit.log"), 1<<20, 2)
	if err != nil {
		t.Fatal(err)
	}
	defer logger.Close()
	e := engine.New(q.ID, nil, 8)
	s := New(e, 1, 0, time.Millisecond)
	s.Queue = q
	s.Audit = logger
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); s.Run(ctx) }()
	defer func() { once.Do(func() { close(release) }); cancel(); <-done }()
	e.Results <- &pb.Result{CollectorId: q.ID, AttemptId: 1}
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("SQLite write did not start")
	}
	health := make(chan *pb.HealthReply, 1)
	failed := make(chan error, 1)
	go func() {
		reply, err := s.Health(ctx, &pb.HealthRequest{})
		if err != nil {
			failed <- err
		} else {
			health <- reply
		}
	}()
	select {
	case reply := <-health:
		if reply.Metrics["queue_batches"] != 0 || reply.Metrics["forming_results"] != 1 {
			t.Fatal("uncommitted batch exposed", reply.Metrics)
		}
	case err := <-failed:
		t.Fatal(err)
	case <-time.After(time.Second):
		t.Fatal("SQLite disk wait blocked collector health")
	}
	once.Do(func() { close(release) })
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		stats, err := q.Stats()
		if err != nil {
			t.Fatal(err)
		}
		if stats.Batches == 1 && q.Metrics()["sqlite_append_operations_total"] == 1 {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("write did not commit after storage recovered")
}
