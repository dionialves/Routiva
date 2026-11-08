package spool

import (
	"os"
	"os/exec"
	"path/filepath"
	pb "routiva/collector/gen"
	"strings"
	"testing"
	"time"
)

func openTest(t *testing.T, path string) *Queue {
	t.Helper()
	q, err := Open(path, 1<<20, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	return q
}
func appendTest(t *testing.T, q *Queue, rs []*pb.Result, protected uint64) int64 {
	t.Helper()
	n, err := q.Append(rs, protected)
	if err != nil {
		t.Fatal(err)
	}
	return n
}
func TestRecoveryAfterAbruptProcessExit(t *testing.T) {
	if path := os.Getenv("ROUTIVA_SPOOL_CRASH_TEST"); path != "" {
		q := openTest(t, path)
		appendTest(t, q, []*pb.Result{{CollectorId: q.ID, HostId: "host", AttemptId: 42, ScheduledAt: 123}}, 0)
		os.Exit(0) // Deliberately bypass Close, leaving SQLite WAL recovery to the next process.
	}
	path := filepath.Join(t.TempDir(), "queue.db")
	command := exec.Command(os.Args[0], "-test.run=^TestRecoveryAfterAbruptProcessExit$")
	command.Env = append(os.Environ(), "ROUTIVA_SPOOL_CRASH_TEST="+path)
	if out, err := command.CombinedOutput(); err != nil {
		t.Fatalf("%v: %s", err, out)
	}
	q := openTest(t, path)
	first, err := q.Peek()
	if err != nil || first == nil {
		t.Fatalf("missing recovered batch: %v", err)
	}
	id := q.ID
	batchID := first.BatchId
	if first.Results[0].AttemptId != 42 || first.CollectorId != id {
		t.Fatal(first)
	}
	if err = q.Ack(batchID); err != nil {
		t.Fatal(err)
	}
	q.Close()
	q = openTest(t, path)
	defer q.Close()
	if q.ID != id {
		t.Fatal("collector identity changed")
	}
	appendTest(t, q, []*pb.Result{{AttemptId: 43}}, 0)
	next, err := q.Peek()
	if err != nil || next.BatchId <= batchID {
		t.Fatal("batch identity reused", err)
	}
	stats, err := q.Stats()
	if err != nil || stats.Batches != 1 || stats.Results != 1 {
		t.Fatal(stats, err)
	}
}
func TestByteLimitEvictsOldestButProtectsInFlight(t *testing.T) {
	q := openTest(t, filepath.Join(t.TempDir(), "queue.db"))
	defer q.Close()
	large := []*pb.Result{{ErrorCode: strings.Repeat("x", 600000)}}
	appendTest(t, q, large, 0)
	old, _ := q.Peek()
	if n := appendTest(t, q, large, 0); n != 1 {
		t.Fatal("oldest not counted", n)
	}
	current, _ := q.Peek()
	if current.BatchId <= old.BatchId {
		t.Fatal("oldest retained")
	}
	if n := appendTest(t, q, large, current.BatchId); n != 1 {
		t.Fatal("new drop not counted", n)
	}
	protected, _ := q.Peek()
	if protected.BatchId != current.BatchId {
		t.Fatal("in-flight evicted")
	}
	stats, _ := q.Stats()
	if stats.Bytes > q.MaxBytes || stats.Dropped != 2 {
		t.Fatal(stats)
	}
}
func TestAgeLimitAndExclusiveOwnership(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.db")
	q := openTest(t, path)
	defer q.Close()
	if other, err := Open(path, 1<<20, time.Hour); err == nil {
		other.Close()
		t.Fatal("two owners accepted")
	}
	appendTest(t, q, []*pb.Result{{AttemptId: 1}}, 0)
	q.db.Exec("UPDATE batches SET created=?", time.Now().Add(-2*time.Hour).UnixMilli())
	if n := appendTest(t, q, []*pb.Result{{AttemptId: 2}}, 0); n != 1 {
		t.Fatal(n)
	}
	b, _ := q.Peek()
	if b.Results[0].AttemptId != 2 {
		t.Fatal("expired head retained")
	}
}
func TestExpiredQueueIsPrunedBeforeReplayWithoutNewResults(t *testing.T) {
	q := openTest(t, filepath.Join(t.TempDir(), "queue.db"))
	defer q.Close()
	appendTest(t, q, []*pb.Result{{AttemptId: 1}}, 0)
	if _, err := q.db.Exec("UPDATE batches SET created=?", time.Now().Add(-2*time.Hour).UnixMilli()); err != nil {
		t.Fatal(err)
	}
	b, err := q.Peek()
	if err != nil || b != nil {
		t.Fatal("expired replay", b, err)
	}
	stats, _ := q.Stats()
	if stats.Dropped != 1 || stats.Batches != 0 {
		t.Fatal(stats)
	}
}
func TestFailedAppendDoesNotEvictCommittedData(t *testing.T) {
	q := openTest(t, filepath.Join(t.TempDir(), "queue.db"))
	defer q.Close()
	appendTest(t, q, []*pb.Result{{AttemptId: 1}}, 0)
	q.db.Exec("CREATE TRIGGER fail_insert BEFORE INSERT ON batches BEGIN SELECT RAISE(ABORT,'disk failure'); END")
	q.db.Exec("UPDATE batches SET created=?", time.Now().Add(-2*time.Hour).UnixMilli())
	if _, err := q.Append([]*pb.Result{{AttemptId: 2}}, 0); err == nil {
		t.Fatal("write failure hidden")
	}
	stats, _ := q.Stats()
	if stats.Batches != 1 || stats.Dropped != 0 {
		t.Fatal("failed transaction removed data", stats)
	}

}
func BenchmarkPersistTenThousandResults(b *testing.B) {
	q, err := Open(filepath.Join(b.TempDir(), "queue.db"), 256<<20, time.Hour)
	if err != nil {
		b.Fatal(err)
	}
	defer q.Close()
	results := make([]*pb.Result, 500)
	for i := range results {
		results[i] = &pb.Result{CollectorId: q.ID, HostId: "host-00001", Target: "192.0.2.1", AttemptId: uint64(i + 1), ScheduledAt: time.Now().UnixNano(), Status: pb.Status_SUCCESS, RttNs: 1000000}
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		for batch := 0; batch < 20; batch++ {
			if _, err := q.Append(results, 0); err != nil {
				b.Fatal(err)
			}
		}
	}
	b.ReportMetric(float64(b.N*10000)/b.Elapsed().Seconds(), "results/s")
}

func TestEntireSentPrefixSurvivesRetentionAndReplaysUntilAcknowledged(t *testing.T) {
	q := openTest(t, filepath.Join(t.TempDir(), "queue.db"))
	defer q.Close()
	for i := 0; i < 4; i++ {
		appendTest(t, q, []*pb.Result{{AttemptId: uint64(i + 1), ErrorCode: strings.Repeat("x", 200000)}}, 0)
	}
	first, err := q.Peek()
	if err != nil {
		t.Fatal(err)
	}
	second, err := q.PeekAfter(first.BatchId)
	if err != nil {
		t.Fatal(err)
	}
	third, err := q.PeekAfter(second.BatchId)
	if err != nil {
		t.Fatal(err)
	}
	appendTest(t, q, []*pb.Result{{AttemptId: 5, ErrorCode: strings.Repeat("x", 400000)}}, third.BatchId)
	// The fourth unsent batch is evicted; all three sent batches remain.
	stats, _ := q.Stats()
	if stats.Dropped != 1 || stats.Batches != 4 {
		t.Fatal(stats)
	}
	for i := 0; i < 3; i++ {
		b, err := q.PeekAfter(uint64(i))
		if err != nil || b.BatchId != uint64(i+1) {
			t.Fatal("sent batch evicted", b, err)
		}
	}
	if _, err = q.db.Exec("UPDATE batches SET created=?", time.Now().Add(-2*time.Hour).UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if b, err := q.PeekAfter(third.BatchId); err != nil || b != nil {
		t.Fatal("unsent expired batch replayed", b, err)
	}
	stats, _ = q.Stats()
	if stats.Batches != 3 || stats.Dropped != 2 {
		t.Fatal(stats)
	}
	if err = q.AckMany([]uint64{first.BatchId, second.BatchId}); err != nil {
		t.Fatal(err)
	}
	stats, _ = q.Stats()
	if stats.Batches != 1 {
		t.Fatal(stats)
	}
	// Simulate reconnect: retained third batch can now be replayed when it is not expired.
	q.db.Exec("UPDATE batches SET created=?", time.Now().UnixMilli())
	replay, err := q.Peek()
	if err != nil || replay.BatchId != third.BatchId {
		t.Fatal(replay, err)
	}
}
