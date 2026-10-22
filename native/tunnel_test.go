package main

import (
	"fmt"
	"io"
	"net"
	"os"
	"testing"
	"time"
)

func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	return port
}

func TestRealTunnelAndInstanceIsolation(t *testing.T) {
	bin := os.Getenv("FRP_TEST_BIN")
	if bin == "" {
		t.Skip("FRP_TEST_BIN points to the built upstream CLI binaries")
	}
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			conn, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { defer conn.Close(); io.Copy(conn, conn) }()
		}
	}()
	m := newManager(t.TempDir(), bin, false)
	t.Cleanup(func() { m.handle(request{Action: "stopAll"}) })
	serverPort, proxyPort := freePort(t), freePort(t)
	server := profile{ID: "server", Type: "server", Toml: fmt.Sprintf("bindAddr = '127.0.0.1'\nbindPort = %d\nauth.token = 'test-token'", serverPort)}
	client := profile{ID: "client", Type: "client", Toml: fmt.Sprintf(
		"serverAddr = '127.0.0.1'\nserverPort = %d\nloginFailExit = false\nauth.token = 'test-token'\n[[proxies]]\nname = 'echo'\ntype = 'tcp'\nlocalIP = '127.0.0.1'\nlocalPort = %d\nremotePort = %d",
		serverPort, echo.Addr().(*net.TCPAddr).Port, proxyPort)}
	for _, p := range []profile{server, client} {
		if r := m.handle(request{Action: "start", Profile: p}); r.Error != "" {
			t.Fatal(r.Error)
		}
	}
	var connection net.Conn
	for deadline := time.Now().Add(15 * time.Second); time.Now().Before(deadline); {
		connection, err = net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", proxyPort), 100*time.Millisecond)
		if err == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("tunnel unavailable: %v, %+v", err, m.status(0).Logs)
	}
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := connection.Write([]byte("FRP Mobile")); err != nil {
		t.Fatal(err)
	}
	output := make([]byte, len("FRP Mobile"))
	if _, err := io.ReadFull(connection, output); err != nil {
		t.Fatal(err)
	}
	if string(output) != "FRP Mobile" {
		t.Fatalf("unexpected payload: %s", output)
	}
	if result := m.handle(request{Action: "stop", ID: client.ID}); result.Error != "" {
		t.Fatal(result.Error)
	}
	for _, current := range m.status(0).States {
		if current.ID == server.ID && current.State != "running" {
			t.Fatal("stopping client also stopped server")
		}
	}
}
