package main

import (
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestNetworkRecoveryRespectsDemandAndPolicy(t *testing.T) {
	m := testManager(t)
	m.persist = true
	p := profile{ID: "network", Type: "client", Toml: "serverAddr = 'localhost'", NetworkReconnect: true}
	if r := m.handle(request{Action: "start", Profile: p}); r.Error != "" {
		t.Fatal(r)
	}
	kill := func() {
		m.mu.Lock()
		process := m.instances[p.ID].cmd.Process
		m.mu.Unlock()
		process.Kill()
		eventually(t, time.Second, func() bool { return m.status(0).States[0].State == "failed" })
	}
	kill()
	if !m.needsRun() {
		t.Fatal("network recovery lost its listener demand")
	}
	m.recoverNetwork()
	if m.status(0).States[0].State != "running" {
		t.Fatal("network recovery did not restart the failed instance")
	}
	kill()
	p.NetworkReconnect = false
	m.handle(request{Action: "configure", Profiles: []profile{p}})
	m.recoverNetwork()
	if m.needsRun() || m.status(0).States[0].State != "failed" {
		t.Fatal("disabled policy still recovers")
	}
	p.NetworkReconnect = true
	m.handle(request{Action: "configure", Profiles: []profile{p}})
	m.handle(request{Action: "stopAll"})
	m.recoverNetwork()
	if m.needsRun() || m.status(0).States[0].Desired {
		t.Fatal("late network event revived a stopped instance")
	}
}

func TestNetworkEventsAndClose(t *testing.T) {
	if os.Getenv("FRP_NETWORK_TEST_CHILD") != "1" {
		if os.Geteuid() != 0 {
			t.Skip("root is required for an isolated network namespace")
		}
		cmd := exec.Command("unshare", "--net", os.Args[0], "-test.run=^TestNetworkEventsAndClose$", "-test.v")
		cmd.Env = append(os.Environ(), "FRP_NETWORK_TEST_CHILD=1")
		if output, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("isolated network test: %v\n%s", err, output)
		}
		return
	}
	file, changes, err := networkEvents()
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if output, err := exec.Command("ip", "link", "set", "lo", "up").CombinedOutput(); err != nil {
		t.Fatalf("loopback: %v %s", err, output)
	}
	select {
	case _, open := <-changes:
		if !open {
			t.Fatal("network listener closed before receiving events")
		}
	case <-time.After(time.Second):
		t.Fatal("kernel network event was not received")
	}
	file.Close()
	closed := make(chan struct{})
	go func() {
		for range changes {
		}
		close(closed)
	}()
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("closing the socket did not release its reader")
	}
}
