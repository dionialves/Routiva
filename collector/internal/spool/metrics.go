package spool

import (
	"sync/atomic"
	"time"
)

// Operation timings include SQLite work and commit; wait measures only the queue mutex.
// Counters deliberately live outside SQLite so observing health never waits for disk.
type operation struct {
	count, failures                      atomic.Int64
	wait, elapsed, commit                atomic.Int64
	totalWait, totalElapsed, totalCommit atomic.Int64
	maxWait, maxElapsed                  atomic.Int64
}

func maximum(value *atomic.Int64, candidate int64) {
	for previous := value.Load(); candidate > previous; previous = value.Load() {
		if value.CompareAndSwap(previous, candidate) {
			return
		}
	}
}
func (o *operation) finish(wait, elapsed, commit time.Duration, err error) {
	o.count.Add(1)
	if err != nil {
		o.failures.Add(1)
	}
	o.wait.Store(int64(wait))
	o.elapsed.Store(int64(elapsed))
	o.commit.Store(int64(commit))
	o.totalWait.Add(int64(wait))
	o.totalElapsed.Add(int64(elapsed))
	o.totalCommit.Add(int64(commit))
	maximum(&o.maxWait, int64(wait))
	maximum(&o.maxElapsed, int64(elapsed))
}
func (o *operation) expose(values map[string]float64, prefix string) {
	values[prefix+"_operations_total"] = float64(o.count.Load())
	values[prefix+"_errors_total"] = float64(o.failures.Load())
	for key, value := range map[string]int64{
		"wait_ms": o.wait.Load(), "operation_ms": o.elapsed.Load(), "commit_ms": o.commit.Load(),
		"wait_ms_total": o.totalWait.Load(), "operation_ms_total": o.totalElapsed.Load(), "commit_ms_total": o.totalCommit.Load(),
		"max_wait_ms": o.maxWait.Load(), "max_operation_ms": o.maxElapsed.Load(),
	} {
		values[prefix+"_"+key] = float64(value) / float64(time.Millisecond)
	}
}
func (q *Queue) Metrics() map[string]float64 {
	values := make(map[string]float64)
	q.appendMetrics.expose(values, "sqlite_append")
	q.readMetrics.expose(values, "sqlite_read")
	q.ackMetrics.expose(values, "sqlite_ack")
	values["sqlite_ack_batches_total"] = float64(q.ackedBatches.Load())
	values["sqlite_ack_last_batches"] = float64(q.lastAckBatches.Load())
	return values
}
