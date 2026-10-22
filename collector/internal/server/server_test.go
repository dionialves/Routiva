package server

import (
	"context"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/test/bufconn"
	"io"
	"net"
	pb "routiva/collector/gen"
	"routiva/collector/internal/engine"
	"testing"
	"time"
)

type silent struct{ done chan struct{} }

func (s *silent) Send(string, uint64) error      { return nil }
func (s *silent) Receive() (engine.Reply, error) { <-s.done; return engine.Reply{}, io.EOF }
func (s *silent) Close() error                   { close(s.done); return nil }
func TestStreamingReplayAndAck(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	e := engine.New("boot", &silent{make(chan struct{})}, 8)
	s := New(e, 2, 2, 5*time.Millisecond)
	go e.Run(ctx)
	go s.Run(ctx)
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
	e.Results <- &pb.Result{CollectorId: "boot", AttemptId: 1}
	e.Results <- &pb.Result{CollectorId: "boot", AttemptId: 2}
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
	if err = stream.Send(&pb.Command{ProtocolVersion: 1, Body: &pb.Command_Ack{Ack: &pb.Ack{CollectorId: "boot", BatchId: replay.BatchId}}}); err != nil {
		t.Fatal(err)
	}
	deadline = time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		n := len(s.batches)
		s.mu.Unlock()
		if n == 0 {
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
}
