//go:build linux

package engine

import (
	"os"
	"testing"
	"time"
)

func TestLinuxPingSocketLoopback(t *testing.T) {
	if os.Getenv("ROUTIVA_REAL_ICMP") != "1" {
		t.Skip("set ROUTIVA_REAL_ICMP=1 with ping_group_range configured")
	}
	s, err := OpenSocket()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.conn.SetReadDeadline(time.Now().Add(time.Second))
	if err = s.Send("127.0.0.1", 65537); err != nil {
		t.Fatal(err)
	}
	r, err := s.Receive()
	if err != nil || r.Token != 65537 || r.Source != "127.0.0.1" {
		t.Fatal(r, err)
	}
}
