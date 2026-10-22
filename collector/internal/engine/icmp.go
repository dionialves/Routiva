package engine

import (
	"encoding/binary"
	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"net"
	"time"
)

// Linux ping sockets share one descriptor. Kernel-assigned Echo ID is intentionally
// not used for correlation; source + 64-bit payload token must both match.
type Socket struct{ conn *icmp.PacketConn }

func OpenSocket() (*Socket, error) {
	c, err := icmp.ListenPacket("udp4", "0.0.0.0")
	if err != nil {
		return nil, err
	}
	return &Socket{c}, nil
}
func (s *Socket) Send(target string, token uint64) error {
	data := make([]byte, 16)
	copy(data, "ROUTIVA1")
	binary.BigEndian.PutUint64(data[8:], token)
	msg := icmp.Message{Type: ipv4.ICMPTypeEcho, Code: 0, Body: &icmp.Echo{ID: 1, Seq: int(token & 65535), Data: data}}
	b, err := msg.Marshal(nil)
	if err != nil {
		return err
	}
	s.conn.SetWriteDeadline(time.Now().Add(10 * time.Millisecond))
	_, err = s.conn.WriteTo(b, &net.UDPAddr{IP: parseIPv4(target)})
	return err
}
func (s *Socket) Receive() (Reply, error) {
	var b [1500]byte
	for {
		n, peer, err := s.conn.ReadFrom(b[:])
		at := time.Now()
		if err != nil {
			return Reply{}, err
		}
		m, err := icmp.ParseMessage(1, b[:n])
		if err != nil {
			continue
		}
		if m.Type != ipv4.ICMPTypeEchoReply {
			var quoted []byte
			code := ""
			switch body := m.Body.(type) {
			case *icmp.DstUnreach:
				quoted = body.Data
				code = "ICMP_UNREACHABLE"
			case *icmp.TimeExceeded:
				quoted = body.Data
				code = "ICMP_TIME_EXCEEDED"
			}
			// A minimal ICMP quotation lacks the payload token. Never guess by 16-bit sequence.
			if len(quoted) < 20 {
				continue
			}
			header := int(quoted[0]&15) * 4
			if header < 20 || len(quoted) < header+24 || quoted[9] != 1 {
				continue
			}
			original, err := icmp.ParseMessage(1, quoted[header:])
			if err != nil {
				continue
			}
			echo, ok := original.Body.(*icmp.Echo)
			if !ok {
				continue
			}
			token, ok := Token(echo.Data)
			if !ok {
				continue
			}
			return Reply{Token: token, Source: net.IP(quoted[16:20]).String(), At: at, Error: code}, nil
		}
		if m.Code != 0 {
			continue
		}
		echo, ok := m.Body.(*icmp.Echo)
		if !ok {
			continue
		}
		token, ok := Token(echo.Data)
		if !ok || echo.Seq != int(token&65535) {
			continue
		}
		source := ""
		switch a := peer.(type) {
		case *net.UDPAddr:
			source = a.IP.String()
		case *net.IPAddr:
			source = a.IP.String()
		}
		return Reply{Token: token, Source: source, At: at}, nil
	}
}
func (s *Socket) Close() error { return s.conn.Close() }
