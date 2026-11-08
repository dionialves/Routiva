// Package spool holds unacknowledged protobuf batches on a local persistent disk.
package spool

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"google.golang.org/protobuf/proto"
	_ "modernc.org/sqlite"
	pb "routiva/collector/gen"
)

type Queue struct {
	mu                                     sync.Mutex
	db                                     *sql.DB
	lock                                   *os.File
	ID                                     string
	MaxBytes                               int64
	MaxAge                                 time.Duration
	snapshot                               atomic.Pointer[Stats]
	closed                                 atomic.Bool
	reservedThrough                        uint64 // guarded by mu; reserved atomically with the read
	appendMetrics, readMetrics, ackMetrics operation
	ackedBatches, lastAckBatches           atomic.Int64
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
	if err != nil {
		return nil, err
	}
	snapshot, err := readStats(db)
	if err == nil {
		q.snapshot.Store(snapshot)
	}
	return q, err
}
func (q *Queue) Close() error {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.closed.Swap(true) {
		return nil
	}
	err := q.db.Close()
	q.lock.Close()
	return err
}

type rowReader interface{ QueryRow(string, ...any) *sql.Row }

func readStats(reader rowReader) (*Stats, error) {
	s := new(Stats)
	var oldest int64
	err := reader.QueryRow("SELECT batches,results,bytes,dropped,COALESCE((SELECT created FROM batches ORDER BY id LIMIT 1),0) FROM meta WHERE id=1").Scan(&s.Batches, &s.Results, &s.Bytes, &s.Dropped, &oldest)
	if oldest > 0 {
		s.Oldest = time.UnixMilli(oldest)
	}
	return s, err
}

// Read counters inside the transaction, but publish them only after a successful commit.
func (q *Queue) commit(tx *sql.Tx) (time.Duration, error) {
	snapshot, err := readStats(tx)
	if err != nil {
		return 0, err
	}
	started := time.Now()
	err = tx.Commit()
	elapsed := time.Since(started)
	if err == nil {
		q.snapshot.Store(snapshot)
	}
	return elapsed, err
}

// Stats returns the last committed snapshot. It never waits for SQLite or the writer mutex.
func (q *Queue) Stats() (Stats, error) {
	if q.closed.Load() {
		return Stats{}, errors.New("spool closed")
	}
	snapshot := q.snapshot.Load()
	if snapshot == nil {
		return Stats{}, errors.New("spool snapshot unavailable")
	}
	return *snapshot, nil
}

// ReleaseReservations is called after a stream ends and before another may connect.
func (q *Queue) ReleaseReservations() { q.mu.Lock(); q.reservedThrough = 0; q.mu.Unlock() }

// Append commits before a batch can be sent. All IDs up to protected (the sent prefix) are never evicted.
func (q *Queue) Append(results []*pb.Result, protected uint64) (dropped int64, err error) {
	batch := &pb.Batch{CollectorId: q.ID, ProtocolVersion: 1, Results: results}
	payload, err := proto.Marshal(batch)
	if err != nil {
		return 0, err
	}
	waiting := time.Now()
	q.mu.Lock()
	wait := time.Since(waiting)
	started := time.Now()
	var commit time.Duration
	defer func() { q.appendMetrics.finish(wait, time.Since(started), commit, err); q.mu.Unlock() }()
	if q.closed.Load() {
		return 0, errors.New("spool closed")
	}
	if q.reservedThrough > protected {
		protected = q.reservedThrough
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
		err = tx.QueryRow("SELECT id,results,length(payload),created FROM batches WHERE id>? ORDER BY id LIMIT 1", protected).Scan(&id, &n, &size, &at)
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
	if dropped > 0 {
		if _, err = tx.Exec("UPDATE meta SET dropped=dropped+? WHERE id=1", dropped); err != nil {
			return 0, err
		}
	}
	commit, err = q.commit(tx)
	return dropped, err
}
func (q *Queue) Peek() (*pb.Batch, error)                  { return q.PeekAfter(0) }
func (q *Queue) PeekAfter(after uint64) (*pb.Batch, error) { return q.readAfter(after, false) }

// ReserveAfter atomically protects a selected batch before a concurrent append can evict it.
func (q *Queue) ReserveAfter(after uint64) (*pb.Batch, error) { return q.readAfter(after, true) }
func (q *Queue) readAfter(after uint64, reserve bool) (batch *pb.Batch, err error) {
	waiting := time.Now()
	q.mu.Lock()
	wait := time.Since(waiting)
	started := time.Now()
	var commit time.Duration
	defer func() { q.readMetrics.finish(wait, time.Since(started), commit, err); q.mu.Unlock() }()
	if q.closed.Load() {
		return nil, errors.New("spool closed")
	}
	if reserve && q.reservedThrough > after {
		after = q.reservedThrough
	}
	var id uint64
	var created int64
	var payload []byte
	err = q.db.QueryRow("SELECT id,created,payload FROM batches WHERE id>? ORDER BY id LIMIT 1", after).Scan(&id, &created, &payload)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if created < time.Now().Add(-q.MaxAge).UnixMilli() && id > q.reservedThrough {
		// Bound expiry work to keep the write critical section short. Another tick continues it.
		tx, beginErr := q.db.Begin()
		if beginErr != nil {
			return nil, beginErr
		}
		defer tx.Rollback()
		protected := max(after, q.reservedThrough)
		cutoff := time.Now().Add(-q.MaxAge).UnixMilli()
		candidates := "SELECT id FROM batches WHERE created<? AND id>? ORDER BY id LIMIT 128"
		_, err = tx.Exec("UPDATE meta SET dropped=dropped+(SELECT COALESCE(SUM(results),0) FROM batches WHERE id IN ("+candidates+")) WHERE id=1", cutoff, protected)
		if err != nil {
			return nil, err
		}
		if _, err = tx.Exec("DELETE FROM batches WHERE id IN ("+candidates+")", cutoff, protected); err != nil {
			return nil, err
		}
		commit, err = q.commit(tx)
		return nil, err
	}
	batch = new(pb.Batch)
	if err = proto.Unmarshal(payload, batch); err != nil {
		return nil, err
	}
	batch.BatchId = id
	if reserve {
		q.reservedThrough = id
	}
	return batch, nil
}
func (q *Queue) Ack(id uint64) error { return q.AckMany([]uint64{id}) }

// One DELETE and one durable commit for a validated group of ACK identities.
func (q *Queue) AckMany(ids []uint64) (err error) {
	if len(ids) == 0 {
		return nil
	}
	if len(ids) > 32 {
		return errors.New("ACK group exceeds 32 batches")
	}
	waiting := time.Now()
	q.mu.Lock()
	wait := time.Since(waiting)
	started := time.Now()
	var commit time.Duration
	defer func() { q.ackMetrics.finish(wait, time.Since(started), commit, err); q.mu.Unlock() }()
	if q.closed.Load() {
		return errors.New("spool closed")
	}
	tx, err := q.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")
	args := make([]any, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	result, err := tx.Exec("DELETE FROM batches WHERE id IN ("+placeholders+")", args...)
	if err != nil {
		return err
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	commit, err = q.commit(tx)
	if err == nil {
		q.ackedBatches.Add(removed)
		q.lastAckBatches.Store(removed)
	}
	return err
}
