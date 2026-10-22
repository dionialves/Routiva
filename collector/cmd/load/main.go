// Simulated transport load harness. This is NOT a network capacity test.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	pb "routiva/collector/gen"
	"routiva/collector/internal/engine"
	"routiva/collector/internal/server"
	"runtime"
	"sync"
	"syscall"
	"time"
)

type simulation struct {
	replies chan engine.Reply
	done    chan struct{}
	once    sync.Once
}

func (s *simulation) Send(ip string, id uint64) error {
	select {
	case s.replies <- engine.Reply{Token: id, Source: ip, At: time.Now()}:
		return nil
	case <-s.done:
		return io.EOF
	}
}
func (s *simulation) Receive() (engine.Reply, error) {
	select {
	case r := <-s.replies:
		return r, nil
	case <-s.done:
		return engine.Reply{}, io.EOF
	}
}
func (s *simulation) Close() error { s.once.Do(func() { close(s.done) }); return nil }
func cpu() float64 {
	var u syscall.Rusage
	syscall.Getrusage(syscall.RUSAGE_SELF, &u)
	return float64(u.Utime.Sec+u.Stime.Sec) + float64(u.Utime.Usec+u.Stime.Usec)/1e6
}
func main() {
	duration := 10 * time.Second
	if d := os.Getenv("LOAD_DURATION"); d != "" {
		var err error
		duration, err = time.ParseDuration(d)
		if err != nil {
			panic(err)
		}
	}
	for _, count := range []int{800, 2000, 5000, 10000} {
		ctx, cancel := context.WithCancel(context.Background())
		e := engine.New("simulated", &simulation{replies: make(chan engine.Reply, 32768), done: make(chan struct{})}, 32768)
		done := make(chan struct{})
		go func() { e.Run(ctx); close(done) }()
		c := &pb.Configuration{Replace: true}
		for i := 0; i < count; i++ {
			c.Hosts = append(c.Hosts, &pb.HostConfig{HostId: fmt.Sprint(i), Target: "127.0.0.1", IntervalMs: 1000, TimeoutMs: 1500, Enabled: true})
		}
		if err := e.Configure(ctx, c); err != nil {
			panic(err)
		}
		var results uint64
		drained := make(chan struct{})
		go func() {
			defer close(drained)
			for {
				select {
				case <-ctx.Done():
					return
				case <-e.Results:
					results++
				}
			}
		}()
		baseline := cpu()
		time.Sleep(duration)
		h, _ := server.New(e, 500, 128, 100*time.Millisecond).Health(ctx, &pb.HealthRequest{})
		used := cpu() - baseline
		cancel()
		<-done
		<-drained
		h.Metrics["duration_seconds"] = duration.Seconds()
		h.Metrics["cpu_percent_one_core"] = used / duration.Seconds() * 100
		h.Metrics["results_per_second"] = float64(results) / duration.Seconds()
		h.Metrics["results_total"] = float64(results)
		json.NewEncoder(os.Stdout).Encode(h.Metrics)
		runtime.GC()
	}
}
