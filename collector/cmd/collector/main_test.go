package main

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"google.golang.org/grpc"
	pb "routiva/collector/gen"
)

func TestNumericConfigurationRejectsInvalidValues(t *testing.T) {
	for _, value := range []string{"0", "-1", "text", "999999999999999999999999"} {
		t.Run(value, func(t *testing.T) {
			t.Setenv("ROUTIVA_TEST_NUMBER", value)
			defer func() {
				if recover() == nil {
					t.Fatal("invalid setting accepted")
				}
			}()
			number("ROUTIVA_TEST_NUMBER", 10)
		})
	}
}

func TestConfigurationUsesDefaultsAndExplicitValues(t *testing.T) {
	t.Setenv("ROUTIVA_TEST_NUMBER", "")
	if number("ROUTIVA_TEST_NUMBER", 10) != 10 {
		t.Fatal("default ignored")
	}
	t.Setenv("ROUTIVA_TEST_NUMBER", "42")
	if number("ROUTIVA_TEST_NUMBER", 10) != 42 {
		t.Fatal("explicit setting ignored")
	}
}

type healthServer struct {
	pb.UnimplementedCollectorServer
}

func (healthServer) Health(context.Context, *pb.HealthRequest) (*pb.HealthReply, error) {
	return &pb.HealthReply{CollectorId: "test-collector"}, nil
}

func TestHealthReadsLocalUnixSocketAndOutputsJSON(t *testing.T) {
	endpoint := filepath.Join(t.TempDir(), "health.sock")
	listener, err := net.Listen("unix", endpoint)
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer()
	pb.RegisterCollectorServer(server, healthServer{})
	go server.Serve(listener)
	defer server.Stop()
	t.Setenv("COLLECTOR_LISTEN", "unix://"+endpoint)
	output, err := os.CreateTemp(t.TempDir(), "health-output")
	if err != nil {
		t.Fatal(err)
	}
	defer output.Close()
	original := os.Stdout
	os.Stdout = output
	defer func() { os.Stdout = original }()
	if err := health(); err != nil {
		t.Fatal(err)
	}
	if _, err := output.Seek(0, 0); err != nil {
		t.Fatal(err)
	}
	var response map[string]any
	if err := json.NewDecoder(output).Decode(&response); err != nil {
		t.Fatal(err)
	}
	if response["collector_id"] != "test-collector" {
		t.Fatal(response)
	}
}

func TestHealthRejectsRemoteAddress(t *testing.T) {
	t.Setenv("COLLECTOR_LISTEN", "tcp://127.0.0.1:1")
	if err := health(); err == nil || !strings.Contains(err.Error(), "local UDS") {
		t.Fatal(err)
	}
}

func TestStartupNeverDeletesRegularFileAtSocketPath(t *testing.T) {
	dir := t.TempDir()
	endpoint := filepath.Join(dir, "collector.sock")
	if err := os.WriteFile(endpoint, []byte("preserve me"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("COLLECTOR_LOG_PATH", filepath.Join(dir, "audit.log"))
	t.Setenv("COLLECTOR_LISTEN", "unix://"+endpoint)
	err := run()
	if err == nil || !strings.Contains(err.Error(), "non-socket") {
		t.Fatal(err)
	}
	content, err := os.ReadFile(endpoint)
	if err != nil || string(content) != "preserve me" {
		t.Fatal(string(content), err)
	}
}
