package main

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"log/slog"
	"net"
	"os"
	"os/signal"
	pb "routiva/collector/gen"
	"routiva/collector/internal/engine"
	"routiva/collector/internal/server"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func env(k, d string) string {
	if s := os.Getenv(k); s != "" {
		return s
	}
	return d
}
func number(k string, d int) int {
	v, err := strconv.Atoi(env(k, strconv.Itoa(d)))
	if err != nil || v < 1 {
		panic("invalid " + k)
	}
	return v
}
func health() error {
	address := env("COLLECTOR_LISTEN", "unix:///run/routiva/collector.sock")
	if !strings.HasPrefix(address, "unix://") {
		return fmt.Errorf("CLI health supports local UDS only")
	}
	c, err := grpc.NewClient(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return err
	}
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	r, err := pb.NewCollectorClient(c).Health(ctx, &pb.HealthRequest{})
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(r)
}
func run() error {
	if len(os.Args) > 1 && os.Args[1] == "--health" {
		return health()
	}

	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	address := env("COLLECTOR_LISTEN", "unix:///run/routiva/collector.sock")
	network := "unix"
	endpoint := strings.TrimPrefix(address, "unix://")
	var options []grpc.ServerOption
	if strings.HasPrefix(address, "tcp://") {
		network = "tcp"
		endpoint = strings.TrimPrefix(address, "tcp://")
		cert, err := tls.LoadX509KeyPair(os.Getenv("COLLECTOR_TLS_CERT"), os.Getenv("COLLECTOR_TLS_KEY"))
		if err != nil {
			return err
		}
		ca, err := os.ReadFile(os.Getenv("COLLECTOR_TLS_CA"))
		if err != nil {
			return err
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(ca) {
			return fmt.Errorf("invalid CA")
		}
		options = append(options, grpc.Creds(credentials.NewTLS(&tls.Config{Certificates: []tls.Certificate{cert}, ClientCAs: pool, ClientAuth: tls.RequireAndVerifyClientCert, MinVersion: tls.VersionTLS12})))
	} else if !strings.HasPrefix(address, "unix://") {
		return fmt.Errorf("use unix:// or tcp://")
	}
	// A filesystem lock prevents two local collectors from racing over a stale socket.
	if network == "unix" {
		lock, err := os.OpenFile(endpoint+".lock", os.O_CREATE|os.O_RDWR, 0600)
		if err != nil {
			return err
		}
		defer lock.Close()
		if err = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
			return fmt.Errorf("collector socket is owned: %w", err)
		}
		if info, err := os.Lstat(endpoint); err == nil {
			if info.Mode()&os.ModeSocket == 0 {
				return fmt.Errorf("refusing to remove non-socket %s", endpoint)
			}
			conn, err := net.DialTimeout("unix", endpoint, time.Second)
			if err == nil {
				conn.Close()
				return fmt.Errorf("socket already serving")
			}
			if err = os.Remove(endpoint); err != nil {
				return err
			}
		}
	}
	listener, err := net.Listen(network, endpoint)
	if err != nil {
		return err
	}
	defer listener.Close()
	if network == "unix" {
		if err = os.Chmod(endpoint, 0660); err != nil {
			return err
		}
	}
	socket, err := engine.OpenSocket()
	if err != nil {
		return fmt.Errorf("ICMP socket: %w (check ping_group_range)", err)
	}
	var id [16]byte
	if _, err = rand.Read(id[:]); err != nil {
		return err
	}
	e := engine.New(hex.EncodeToString(id[:]), socket, number("COLLECTOR_RESULT_BUFFER", 32768))
	batchSize := number("COLLECTOR_BATCH_SIZE", 500)
	if batchSize > 5000 {
		return fmt.Errorf("COLLECTOR_BATCH_SIZE must be <=5000")
	}
	s := server.New(e, batchSize, number("COLLECTOR_MAX_BATCHES", 128), time.Duration(number("COLLECTOR_FLUSH_MS", 100))*time.Millisecond)
	g := grpc.NewServer(append(options, grpc.MaxRecvMsgSize(4<<20))...)
	pb.RegisterCollectorServer(g, s)
	engineDone := make(chan struct{})
	go func() { e.Run(ctx); close(engineDone) }()
	batchCtx, stopBatch := context.WithCancel(context.Background())
	defer stopBatch()
	go s.Run(batchCtx)
	go func() {
		<-ctx.Done()
		<-engineDone
		// Keep the result stream available briefly to acknowledge the final batches.
		time.Sleep(s.Flush)
		deadline := time.Now().Add(5 * time.Second)
		for s.Buffered() > 0 && time.Now().Before(deadline) {
			time.Sleep(50 * time.Millisecond)
		}
		if n := s.Buffered(); n > 0 {
			slog.Warn("shutdown with unacknowledged in-memory results", "buffer_units", n)
		}
		stopBatch()
		g.Stop()
	}()
	slog.Info("collector started", "collector_id", e.ID, "listen", address)
	err = g.Serve(listener)
	if ctx.Err() != nil {
		return nil
	}
	return err
}
func main() {
	if err := run(); err != nil {
		slog.Error("collector stopped", "error", err)
		os.Exit(1)
	}
}
