package audit

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestConcurrentOrderAndEscaping(t *testing.T) {
	path := filepath.Join(t.TempDir(), "collector.log")
	l, err := Open(path, 1<<20, 3)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); l.Event(slog.LevelInfo, "BATCH_CREATED", "Batch\nqueued", "batch_id", 1) }()
	}
	wg.Wait()
	l.Close()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(b)), "\n")
	if len(lines) != 20 {
		t.Fatalf("got %d lines", len(lines))
	}
	for i, line := range lines {
		if !strings.Contains(line, fmt.Sprintf("seq=%d ", i+1)) {
			t.Fatalf("unordered line: %s", line)
		}
	}
	l.Event(slog.LevelInfo, "AFTER_CLOSE", "Ignored safely")
	l.Close()
}
func TestRotationAndAppend(t *testing.T) {
	path := filepath.Join(t.TempDir(), "collector.log")
	l, err := Open(path, 200, 3)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		l.Event(slog.LevelInfo, "BATCH_CREATED", "Batch queued", "batch_id", i)
	}
	l.Close()
	files, err := filepath.Glob(path + "*")
	if err != nil || len(files) != 3 {
		t.Fatalf("rotation files %v: %v", files, err)
	}
	for _, f := range files {
		st, _ := os.Stat(f)
		if st.Size() > 200 {
			t.Fatalf("oversized file %s", f)
		}
	}
	before, _ := os.ReadFile(path)
	l, err = Open(path, 1<<20, 3)
	if err != nil {
		t.Fatal(err)
	}
	l.Event(slog.LevelInfo, "STARTING", "Collector starting")
	l.Close()
	after, _ := os.ReadFile(path)
	if !strings.HasPrefix(string(after), string(before)) {
		t.Fatal("restart truncated log")
	}
}
func TestQueueOverflowDoesNotBlock(t *testing.T) {
	l := &Logger{queue: make(chan []byte, 1)}
	l.Event(slog.LevelInfo, "BATCH_CREATED", "First")
	l.Event(slog.LevelInfo, "BATCH_CREATED", "Second")
	if l.dropped.Load() != 1 {
		t.Fatal("missing overflow accounting")
	}
}
func TestWriteFailureCounted(t *testing.T) {
	path := filepath.Join(t.TempDir(), "collector.log")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	f.Close()
	l := &Logger{file: f, path: path, limit: 1024, files: 1, queue: make(chan []byte, 1), done: make(chan struct{})}
	l.Event(slog.LevelInfo, "BATCH_CREATED", "Batch queued")
	go l.run()
	l.Close()
	if l.failures.Load() != 1 {
		t.Fatal("missing write failure accounting")
	}
}
