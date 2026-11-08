// Package spool holds unacknowledged protobuf batches on a local persistent disk.
package spool

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"google.golang.org/protobuf/proto"
	_ "modernc.org/sqlite"
	pb "routiva/collector/gen"
)

type Queue struct {
	mu       sync.Mutex
	db       *sql.DB
	lock     *os.File
	ID       string
	MaxBytes int64
	MaxAge   time.Duration
}
type Stats struct {
	Batches, Results, Bytes, Dropped int64
	Oldest                           time.Time
}

func Open(path string, maxBytes int64, maxAge time.Duration) (_ *Queue, err error) {
	if maxBytes < 1<<20 || maxAge <= 0 || maxAge > 48*time.Hour {
		return nil, fmt.Errorf("invalid spool limits (minimum 1 MiB, age up to 48h)")
	}
	if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	lock, err := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		lock.Close()
		return nil, fmt.Errorf("spool already owned: %w", err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		lock.Close()
		return nil, err
	}
	q := &Queue{db: db, lock: lock, MaxBytes: maxBytes, MaxAge: maxAge}
	defer func() {
		if err != nil {
			q.Close()
		}
	}()
	db.SetMaxOpenConns(1)
	for _, statement := range []string{
		"PRAGMA journal_mode=WAL", "PRAGMA synchronous=FULL", "PRAGMA busy_timeout=5000",
		"PRAGMA wal_autocheckpoint=256", "PRAGMA journal_size_limit=4194304",
		// Bound the database file too; deleted pages are reused. WAL is additional overhead.
		fmt.Sprintf("PRAGMA max_page_count=%d", (maxBytes*2+(4<<20))/4096),
		"CREATE TABLE IF NOT EXISTS meta(id INTEGER PRIMARY KEY CHECK(id=1), collector TEXT NOT NULL, dropped INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0, results INTEGER NOT NULL DEFAULT 0, batches INTEGER NOT NULL DEFAULT 0)",
		"CREATE TABLE IF NOT EXISTS batches(id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, results INTEGER NOT NULL, payload BLOB NOT NULL)",
		"CREATE INDEX IF NOT EXISTS batches_created ON batches(created)",
		"CREATE TRIGGER IF NOT EXISTS queue_insert AFTER INSERT ON batches BEGIN UPDATE meta SET bytes=bytes+length(NEW.payload),results=results+NEW.results,batches=batches+1 WHERE id=1; END",
		"CREATE TRIGGER IF NOT EXISTS queue_delete AFTER DELETE ON batches BEGIN UPDATE meta SET bytes=bytes-length(OLD.payload),results=results-OLD.results,batches=batches-1 WHERE id=1; END",
	} {
		if _, err = db.Exec(statement); err != nil {
			return nil, err
		}
	}
	var seed [16]byte
	if _, err = rand.Read(seed[:]); err != nil {
		return nil, err
	}
	if _, err = db.Exec("INSERT OR IGNORE INTO meta(id,collector) VALUES(1,?)", hex.EncodeToString(seed[:])); err != nil {
		return nil, err
	}
	err = db.QueryRow("SELECT collector FROM meta WHERE id=1").Scan(&q.ID)
	return q, err
}
func (q *Queue) Close() error { err := q.db.Close(); q.lock.Close(); return err }

// Append commits before a batch can be sent. An in-flight batch is never evicted.
func (q *Queue) Append(results []*pb.Result, protected uint64) (dropped int64, err error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	batch := &pb.Batch{CollectorId: q.ID, ProtocolVersion: 1, Results: results}
	payload, err := proto.Marshal(batch)
	if err != nil {
		return 0, err
	}
	tx, err := q.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	cutoff := time.Now().Add(-q.MaxAge).UnixMilli()
	var used int64
	if err = tx.QueryRow("SELECT bytes FROM meta WHERE id=1").Scan(&used); err != nil {
		return 0, err
	}
	for {
		var id, n, size, at int64
		err = tx.QueryRow("SELECT id,results,length(payload),created FROM batches WHERE id<>? ORDER BY id LIMIT 1", protected).Scan(&id, &n, &size, &at)
		if err == sql.ErrNoRows {
			break
		}
		if err != nil {
			return 0, err
		}
		if at >= cutoff && used+int64(len(payload)) <= q.MaxBytes {
			break
		}
		if _, err = tx.Exec("DELETE FROM batches WHERE id=?", id); err != nil {
			return 0, err
		}
		used -= size
		dropped += n
	}
	if used+int64(len(payload)) > q.MaxBytes {
		dropped += int64(len(results))
	} else {
		if _, err = tx.Exec("INSERT INTO batches(created,results,payload) VALUES(?,?,?)", time.Now().UnixMilli(), len(results), payload); err != nil {
			return 0, err
		}
	}
	if _, err = tx.Exec("UPDATE meta SET dropped=dropped+? WHERE id=1", dropped); err != nil {
		return 0, err
	}
	return dropped, tx.Commit()
}
func (q *Queue) Peek() (*pb.Batch, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	// Expire before replay too, including after an idle restart without new pings.
	var oldest int64
	if err := q.db.QueryRow("SELECT COALESCE(MIN(created),0) FROM batches").Scan(&oldest); err != nil {
		return nil, err
	}
	var err error
	if oldest > 0 && oldest < time.Now().Add(-q.MaxAge).UnixMilli() {
		tx, err := q.db.Begin()
		if err != nil {
			return nil, err
		}
		defer tx.Rollback()
		cutoff := time.Now().Add(-q.MaxAge).UnixMilli()
		if _, err = tx.Exec("UPDATE meta SET dropped=dropped+(SELECT COALESCE(SUM(results),0) FROM batches WHERE created<?) WHERE id=1", cutoff); err != nil {
			return nil, err
		}
		if _, err = tx.Exec("DELETE FROM batches WHERE created<?", cutoff); err != nil {
			return nil, err
		}
		if err = tx.Commit(); err != nil {
			return nil, err
		}
	}
	var id uint64
	var payload []byte
	err = q.db.QueryRow("SELECT id,payload FROM batches ORDER BY id LIMIT 1").Scan(&id, &payload)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	b := new(pb.Batch)
	if err = proto.Unmarshal(payload, b); err != nil {
		return nil, err
	}
	b.BatchId = id
	return b, nil
}
func (q *Queue) Ack(id uint64) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	_, err := q.db.Exec("DELETE FROM batches WHERE id=?", id)
	return err
}
func (q *Queue) Stats() (Stats, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	var s Stats
	var oldest int64
	err := q.db.QueryRow("SELECT batches,results,bytes,COALESCE((SELECT created FROM batches ORDER BY id LIMIT 1),0) FROM meta WHERE id=1").Scan(&s.Batches, &s.Results, &s.Bytes, &oldest)
	if err == nil {
		err = q.db.QueryRow("SELECT dropped FROM meta WHERE id=1").Scan(&s.Dropped)
	}
	if oldest > 0 {
		s.Oldest = time.UnixMilli(oldest)
	}
	return s, err
}
