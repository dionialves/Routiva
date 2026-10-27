// Package audit writes bounded, rotating, batch-level operational logs.
package audit

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
)

type Logger struct {
	mu          sync.Mutex
	seq         uint64
	closed      bool
	queue       chan []byte
	done        chan struct{}
	file        *os.File
	path        string
	size, limit int64
	files       int
	dropped     atomic.Uint64
	failures    atomic.Uint64
}

func Open(path string, limit int64, files int) (*Logger, error) {
	if limit < 1 || files < 1 {
		return nil, fmt.Errorf("invalid audit rotation limits")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0750); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0640)
	if err != nil {
		return nil, err
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	l := &Logger{queue: make(chan []byte, 4096), done: make(chan struct{}), file: f, path: path, size: st.Size(), limit: limit, files: files}
	go l.run()
	return l, nil
}

// Event serializes numbering and enqueueing, never disk I/O, on the caller.
func (l *Logger) Event(level slog.Level, event, message string, args ...any) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed {
		return
	}
	l.seq++
	attrs := []any{"seq", l.seq, "event", event}
	attrs = append(attrs, args...)
	slog.New(slog.NewTextHandler(queueWriter{l}, nil)).Log(context.Background(), level, message, attrs...)
}

type queueWriter struct{ l *Logger }

func (w queueWriter) Write(p []byte) (int, error) {
	b := append([]byte(nil), p...)
	select {
	case w.l.queue <- b:
	default:
		w.l.dropped.Add(1)
	}
	return len(p), nil
}
func (l *Logger) rotate() error {
	if l.file != nil {
		if err := l.file.Close(); err != nil {
			return err
		}
		l.file = nil
	}
	if l.files == 1 {
		if err := os.Remove(l.path); err != nil && !os.IsNotExist(err) {
			return err
		}
	} else {
		for i := l.files - 2; i >= 1; i-- {
			if err := os.Rename(fmt.Sprintf("%s.%d", l.path, i), fmt.Sprintf("%s.%d", l.path, i+1)); err != nil && !os.IsNotExist(err) {
				return err
			}
		}
		if err := os.Rename(l.path, l.path+".1"); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	f, err := os.OpenFile(l.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0640)
	if err != nil {
		return err
	}
	l.file = f
	l.size = 0
	return nil
}
func (l *Logger) write(b []byte) error {
	if l.file == nil || (l.size > 0 && l.size+int64(len(b)) > l.limit) {
		if err := l.rotate(); err != nil {
			return err
		}
	}
	n, err := l.file.Write(b)
	l.size += int64(n)
	if err == nil && n != len(b) {
		err = io.ErrShortWrite
	}
	return err
}
func (l *Logger) run() {
	defer close(l.done)
	var reportedDrops, reportedFailures uint64
	for b := range l.queue {
		if err := l.write(b); err != nil {
			n := l.failures.Add(1)
			if n == 1 || n-reportedFailures >= 100 {
				slog.Error("Audit file write failed", "event", "LOG_WRITE_FAILED", "error", err, "failed_events_total", n)
				reportedFailures = n
			}
		}
		if n := l.dropped.Load(); n != reportedDrops {
			slog.Warn("Audit events dropped; collection continues", "event", "LOG_EVENTS_DROPPED", "dropped_events_total", n)
			reportedDrops = n
		}
	}
	if l.file != nil {
		if err := l.file.Close(); err != nil {
			slog.Error("Audit file close failed", "error", err)
		}
	}
}
func (l *Logger) Close() {
	l.mu.Lock()
	if !l.closed {
		l.closed = true
		close(l.queue)
	}
	l.mu.Unlock()
	<-l.done
}
