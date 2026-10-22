package engine

import (
	"fmt"
	pb "routiva/collector/gen"
	"testing"
	"time"
)

func configured(n int) *Engine {
	e := New("boot", nil, 100000)
	e.serial = 0
	c := &pb.Configuration{Replace: true}
	for i := 0; i < n; i++ {
		c.Hosts = append(c.Hosts, &pb.HostConfig{HostId: fmt.Sprint(i), Target: "127.0.0.1", IntervalMs: 1000, TimeoutMs: 1500, Enabled: true})
	}
	e.configure(c, time.Now())
	return e
}
func drain(e *Engine, now time.Time, success bool) {
	for len(e.sends) > 0 {
		j := <-e.sends
		e.handle(event{id: j.id, at: now})
		e.Metrics.Sent.Add(1)
		if success {
			e.handle(event{reply: &Reply{Token: j.id, Source: j.target, At: now.Add(time.Millisecond)}})
		}
	}
}
func TestIndependentCadenceAndTimeout(t *testing.T) {
	e := configured(1)
	h := e.inventory["0"]
	start := h.next
	e.step(start)
	drain(e, start, false)
	e.step(start.Add(time.Second))
	drain(e, start.Add(time.Second), false)
	if len(e.pending) != 2 {
		t.Fatalf("want overlapping attempts, got %d", len(e.pending))
	}
	e.step(start.Add(1500 * time.Millisecond))
	if r := <-e.Results; r.Status != pb.Status_TIMEOUT || r.AttemptId != 1 {
		t.Fatal(r)
	}
	if len(e.pending) != 1 {
		t.Fatal("second attempt prematurely expired")
	}
}
func TestOutOfOrderDuplicateLateAndWrongSource(t *testing.T) {
	e := configured(1)
	start := e.inventory["0"].next
	e.step(start)
	drain(e, start, false)
	e.step(start.Add(time.Second))
	drain(e, start.Add(time.Second), false)
	e.handle(event{reply: &Reply{Token: 2, Source: "192.0.2.1", At: start.Add(1100 * time.Millisecond)}})
	if len(e.Results) != 0 {
		t.Fatal("wrong source accepted")
	}
	for _, id := range []uint64{2, 1, 2} {
		e.handle(event{reply: &Reply{Token: id, Source: "127.0.0.1", At: start.Add(1200 * time.Millisecond)}})
	}
	if len(e.Results) != 2 {
		t.Fatal("duplicate finalized")
	}
	a, b := <-e.Results, <-e.Results
	if a.AttemptId != 2 || a.RttNs != int64(200*time.Millisecond) || b.AttemptId != 1 {
		t.Fatal(a, b)
	}
	e.step(start.Add(3 * time.Second))
	e.handle(event{reply: &Reply{Token: 1, Source: "127.0.0.1", At: start.Add(3 * time.Second)}})
	if e.Metrics.Replies.Load() != 2 {
		t.Fatal("late reply accepted")
	}
}
func TestRemovalAndUpdate(t *testing.T) {
	e := configured(2)
	old := e.inventory["0"].next
	e.configure(&pb.Configuration{Remove: []string{"1"}, Hosts: []*pb.HostConfig{{HostId: "0", Target: "127.0.0.1", IntervalMs: 1000, TimeoutMs: 2000, Enabled: true}}}, time.Now())
	if len(e.schedule) != 1 || !e.inventory["0"].next.Equal(old) {
		t.Fatal("unchanged phase was reset")
	}
	e.configure(&pb.Configuration{Replace: true}, time.Now())
	if len(e.schedule) != 0 {
		t.Fatal("remove failed")
	}
}
func TestOverloadAndInternalErrorsAreNotLoss(t *testing.T) {
	e := configured(1)
	start := e.inventory["0"].next
	e.step(start)
	j := <-e.sends
	e.handle(event{id: j.id, at: start})
	e.handle(event{id: j.id, at: start, err: "WRITE"})
	if r := <-e.Results; r.Status != pb.Status_SEND_ERROR {
		t.Fatal(r)
	}
	e.step(start.Add(10 * time.Second))
	if e.Metrics.Skipped.Load() != 9 {
		t.Fatal("missed slots not recorded")
	}
	if len(e.sends) != 1 {
		t.Fatal("catch-up burst")
	}
	if r := <-e.Results; r.Status != pb.Status_SKIPPED {
		t.Fatal(r)
	}
}
func TestBoundedResultBuffer(t *testing.T) {
	e := configured(1)
	e.Results = make(chan *pb.Result, 1)
	start := e.inventory["0"].next
	for i := 0; i < 5; i++ {
		now := start.Add(time.Duration(i) * time.Second)
		e.step(now)
		drain(e, now, true)
	}
	if len(e.Results) != 1 || e.Metrics.Dropped.Load() != 4 {
		t.Fatal("unbounded buffer")
	}
}
func TestPayloadTokenSurvivesSequenceWrap(t *testing.T) {
	e := configured(1)
	e.serial = 65535
	now := e.inventory["0"].next
	e.step(now)
	drain(e, now, true)
	if r := <-e.Results; r.AttemptId != 65536 {
		t.Fatal(r)
	}
}
func BenchmarkEngine(b *testing.B) {
	for _, count := range []int{800, 2000, 5000, 10000} {
		b.Run(fmt.Sprint(count), func(b *testing.B) {
			e := configured(count)
			e.Results = make(chan *pb.Result, 1)
			start := time.Now()
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				now := start.Add(time.Duration(i) * time.Millisecond)
				e.step(now)
				drain(e, now, true)
				for len(e.Results) > 0 {
					<-e.Results
				}
			}
			b.ReportMetric(float64(e.Metrics.Sent.Load()), "attempts")
			b.ReportMetric(float64(e.Metrics.Skipped.Load()), "skipped")
		})
	}
}

func TestIntermittentLossAndDeadlinesStayBounded(t *testing.T) {
	e := configured(1)
	start := e.inventory["0"].next
	for i := 0; i < 60; i++ {
		now := start.Add(time.Duration(i) * time.Second)
		e.step(now)
		drain(e, now, i%2 == 0)
		if len(e.deadlines) > 2 {
			t.Fatal("completed attempts retained in expiry heap")
		}
	}
	e.configure(&pb.Configuration{Replace: true}, start)
	e.step(start.Add(62 * time.Second))
	success, loss := 0, 0
	for len(e.Results) > 0 {
		r := <-e.Results
		switch r.Status {
		case pb.Status_SUCCESS:
			success++
		case pb.Status_TIMEOUT:
			loss++
		}
	}
	if success != 30 || loss != 30 {
		t.Fatal(success, loss)
	}
}
func TestReceiveFailureIsCollectionError(t *testing.T) {
	e := configured(1)
	now := e.inventory["0"].next
	e.step(now)
	drain(e, now, false)
	e.handle(event{err: "ICMP_RECEIVE", at: now})
	if r := <-e.Results; r.Status != pb.Status_COLLECTION_ERROR {
		t.Fatal(r)
	}
	e.step(now.Add(1500 * time.Millisecond))
	if e.Metrics.Timeouts.Load() != 0 {
		t.Fatal("receive failure became network loss")
	}
}

func TestInventoryAddressSpellingDoesNotBreakCorrelation(t *testing.T) {
	e := configured(1)
	e.inventory["0"].cfg.Target = "127.000.000.001"
	now := e.inventory["0"].next
	e.step(now)
	j := <-e.sends
	e.handle(event{id: j.id, at: now})
	e.handle(event{reply: &Reply{Token: j.id, Source: "127.0.0.1", At: now.Add(time.Millisecond)}})
	if len(e.Results) != 1 {
		t.Fatal("normalized source rejected")
	}
	if r := <-e.Results; r.Target != "127.000.000.001" {
		t.Fatal("historical target changed")
	}
}

func TestCorrelatedICMPFailureIsNetworkFailure(t *testing.T) {
	e := configured(1)
	now := e.inventory["0"].next
	e.step(now)
	j := <-e.sends
	e.handle(event{id: j.id, at: now})
	e.handle(event{reply: &Reply{Token: j.id, Source: "127.0.0.1", At: now.Add(time.Millisecond), Error: "ICMP_UNREACHABLE"}})
	if r := <-e.Results; r.Status != pb.Status_ICMP_ERROR {
		t.Fatal(r)
	}
	if e.Metrics.CollectionErrors.Load() != 0 || e.Metrics.ICMPErrors.Load() != 1 {
		t.Fatal("network error classified as collector failure")
	}
}
