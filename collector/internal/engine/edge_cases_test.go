package engine

import (
	"context"
	"encoding/binary"
	"errors"
	pb "routiva/collector/gen"
	"testing"
	"time"
)

func TestTimeoutIsNotPublishedBeforeActualSendDeadline(t *testing.T) {
	e := configured(1)
	start := e.inventory["0"].next
	e.step(start)
	job := <-e.sends
	sent := start.Add(200 * time.Millisecond)
	e.handle(event{id: job.id, at: sent})
	// Disable future scheduling while keeping the in-flight attempt.
	e.configure(&pb.Configuration{Replace: true}, start)
	e.step(sent.Add(1500*time.Millisecond - time.Nanosecond))
	if len(e.Results) != 0 {
		t.Fatal("timeout published before send deadline")
	}
	e.step(sent.Add(1500 * time.Millisecond))
	if len(e.Results) != 1 {
		t.Fatalf("want one timeout, got %d", len(e.Results))
	}
	r := <-e.Results
	if r.Status != pb.Status_TIMEOUT || r.SentAt != sent.UnixNano() {
		t.Fatal(r)
	}
	if len(e.pending) != 0 || len(e.deadlines) != 0 {
		t.Fatal("expired attempt retained")
	}
}

func TestUnsentExpiredAttemptIsCollectionError(t *testing.T) {
	e := configured(1)
	start := e.inventory["0"].next
	e.step(start)
	e.configure(&pb.Configuration{Replace: true}, start)
	e.step(start.Add(1500 * time.Millisecond))
	if len(e.Results) != 1 {
		t.Fatalf("want one result, got %d", len(e.Results))
	}
	r := <-e.Results
	if r.Status != pb.Status_COLLECTION_ERROR || r.ErrorCode != "SEND_QUEUE_EXPIRED" {
		t.Fatal(r)
	}
	if e.Metrics.Timeouts.Load() != 0 {
		t.Fatal("unsent attempt counted as network loss")
	}
}

func TestPendingLimitDoesNotCreateFalseNetworkLoss(t *testing.T) {
	e := configured(1)
	e.MaxPending = 0
	e.step(e.inventory["0"].next)
	if len(e.Results) != 1 {
		t.Fatalf("want one skipped result, got %d", len(e.Results))
	}
	r := <-e.Results
	if r.Status != pb.Status_SKIPPED || r.ErrorCode != "PENDING_LIMIT" {
		t.Fatal(r)
	}
	if len(e.sends) != 0 || len(e.pending) != 0 || e.Metrics.Timeouts.Load() != 0 {
		t.Fatal("overload produced an attempt or loss")
	}
}

func TestConfigurationValidationBoundaries(t *testing.T) {
	for _, tc := range []struct {
		name              string
		interval, timeout int64
		target            string
		valid             bool
	}{
		{"minimum", 100, 100, "127.0.0.1", true},
		{"maximum", 3600000, 60000, "127.0.0.1", true},
		{"short interval", 99, 100, "127.0.0.1", false},
		{"long interval", 3600001, 100, "127.0.0.1", false},
		{"short timeout", 100, 99, "127.0.0.1", false},
		{"long timeout", 100, 60001, "127.0.0.1", false},
		{"hostname", 100, 100, "localhost", false},
		{"invalid address", 100, 100, "256.0.0.1", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			err := Validate(&pb.Configuration{Hosts: []*pb.HostConfig{{HostId: "h", Target: tc.target, IntervalMs: tc.interval, TimeoutMs: tc.timeout}}})
			if (err == nil) != tc.valid {
				t.Fatalf("valid=%v error=%v", tc.valid, err)
			}
		})
	}
}

func TestCancelledConfigurationDoesNotWaitForEngine(t *testing.T) {
	e := configured(1)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := e.Configure(ctx, &pb.Configuration{Replace: true}); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if len(e.inventory) != 1 {
		t.Fatal("cancelled configuration changed inventory")
	}
}

func TestTokenRejectsTruncatedOversizedAndForeignPayloads(t *testing.T) {
	valid := make([]byte, 16)
	copy(valid, "ROUTIVA1")
	binary.BigEndian.PutUint64(valid[8:], 65536)
	if token, ok := Token(valid); !ok || token != 65536 {
		t.Fatal(token, ok)
	}
	for _, payload := range [][]byte{nil, valid[:7], valid[:15], append(append([]byte{}, valid...), 0), make([]byte, 16)} {
		if _, ok := Token(payload); ok {
			t.Fatalf("accepted invalid payload %v", payload)
		}
	}
}
