package main

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func eventually(t *testing.T, timeout time.Duration, ready func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if ready() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("condition did not converge")
}

func TestRetryIsBoundedAndCancelledByStop(t *testing.T) {
	for attempt, seconds := range []int{10, 30, 60, 120, 300, 600, 600} {
		delay := retryDelay(attempt + 1)
		if delay < time.Duration(seconds)*time.Second*9/10 || delay > time.Duration(seconds)*time.Second {
			t.Fatalf("unexpected backoff: %v", delay)
		}
	}
	m := testManager(t)
	p := profile{ID: "retry", Type: "client", Toml: "serverAddr = 'localhost'", Retry: true}
	if r := m.handle(request{Action: "start", Profile: p}); r.Error != "" {
		t.Fatal(r)
	}
	m.mu.Lock()
	item := m.instances[p.ID]
	process := item.cmd.Process
	m.mu.Unlock()
	process.Kill()
	eventually(t, time.Second, func() bool {
		m.mu.Lock()
		defer m.mu.Unlock()
		return item.retrying
	})
	if r := m.handle(request{Action: "stop", ID: p.ID}); r.Error != "" {
		t.Fatal(r)
	}
	if m.needsRun() {
		t.Fatal("stopped retry still requires a daemon")
	}
	m.mu.Lock()
	cancelled := item.cancel == nil && !item.desired
	m.mu.Unlock()
	if !cancelled {
		t.Fatal("retry wait was not cancelled")
	}
}

func TestServerExitsAfterLastStopAndReleasesWatch(t *testing.T) {
	m := testManager(t)
	finished := make(chan error, 1)
	go func() { finished <- serve(m.root, m.bin, os.Getppid()) }()
	eventually(t, time.Second, func() bool {
		_, err := call(m.root, request{Action: "status"})
		return err == nil
	})
	p := profile{ID: "active", Type: "server", Toml: "bindPort = 7000"}
	started, err := call(m.root, request{Action: "start", Profile: p})
	if err != nil || started.Error != "" {
		t.Fatalf("start failed: %+v %v", started, err)
	}
	watched := make(chan struct{})
	go func() {
		call(m.root, request{Action: "watch", Revision: started.Revision})
		close(watched)
	}()
	if stopped, err := call(m.root, request{Action: "stopAll"}); err != nil || stopped.Error != "" {
		t.Fatalf("stop failed: %+v %v", stopped, err)
	}
	select {
	case err := <-finished:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("idle supervisor remained alive")
	}
	select {
	case <-watched:
	case <-time.After(time.Second):
		t.Fatal("watch did not finish")
	}
	if _, err := os.Stat(filepath.Join(m.root, "control.sock")); !os.IsNotExist(err) {
		t.Fatal("idle control socket retained")
	}
}

func TestRetryRejectsEditedInvalidConfiguration(t *testing.T) {
	m := testManager(t)
	p := profile{ID: "retry", Type: "client", Toml: "serverAddr = 'localhost'", Retry: true}
	if r := m.handle(request{Action: "start", Profile: p}); r.Error != "" {
		t.Fatal(r)
	}
	m.mu.Lock()
	process := m.instances[p.ID].cmd.Process
	m.mu.Unlock()
	process.Kill()
	eventually(t, time.Second, func() bool {
		m.mu.Lock()
		defer m.mu.Unlock()
		return m.instances[p.ID].retrying
	})
	p.Toml = "serverAddr = 'INVALID'"
	if r := m.handle(request{Action: "configure", Profiles: []profile{p}}); r.Error != "" {
		t.Fatal(r)
	}
	eventually(t, 12*time.Second, func() bool {
		m.mu.Lock()
		defer m.mu.Unlock()
		return !m.instances[p.ID].retrying
	})
	state := m.status(0).States[0]
	if state.State != "failed" || state.Desired || !strings.HasPrefix(state.Error, "INVALID_TOML") || m.needsRun() {
		t.Fatalf("invalid configuration kept recovery active: %+v", state)
	}
}

func TestModuleIdleRestartAndBoot(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("root is required for the isolated module lifecycle test")
	}
	dir := t.TempDir()
	modulePath, root := filepath.Join(dir, "module"), filepath.Join(dir, "runtime")
	bin := filepath.Join(dir, "bin")
	if err := os.Mkdir(bin, 0700); err != nil {
		t.Fatal(err)
	}
	binary := filepath.Join(bin, "libfrpmobile.so")
	build := exec.Command("go", "build", "-buildvcs=false", "-o", binary, "-ldflags",
		"-X main.moduleDir="+modulePath+" -X main.moduleRoot="+root, ".")
	if output, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build: %v %s", err, output)
	}
	for name, script := range map[string]string{
		"magisk":     "#!/bin/sh\nexit 0\n",
		"libfrpc.so": "#!/bin/sh\n[ \"$1\" = verify ] && exit 0\nexec sleep 300\n",
		"libfrps.so": "#!/bin/sh\n[ \"$1\" = verify ] && exit 0\nexec sleep 300\n",
	} {
		if err := os.WriteFile(filepath.Join(bin, name), []byte(script), 0700); err != nil {
			t.Fatal(err)
		}
	}
	run := func(input string, args ...string) []byte {
		t.Helper()
		cmd := exec.Command(binary, args...)
		cmd.Env = append(os.Environ(), "PATH="+bin+":"+os.Getenv("PATH"))
		cmd.Stdin = strings.NewReader(input)
		output, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("%v: %v %s", args, err, output)
		}
		return output
	}
	t.Cleanup(func() { run("", "module", "uninstall") })
	running := func() bool {
		var status struct{ Running bool }
		if err := json.Unmarshal(run("", "module", "status"), &status); err != nil {
			t.Fatal(err)
		}
		return status.Running
	}
	idle := func() bool {
		_, err := os.Stat(filepath.Join(root, "watch.pid"))
		return !running() && os.IsNotExist(err)
	}
	send := func(req request) response {
		data, _ := json.Marshal(req)
		var result response
		if err := json.Unmarshal(run(string(data), "request", root), &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	run("", "module", "install")
	if !idle() {
		t.Fatal("empty installation started a daemon")
	}
	p := profile{ID: "automatic", Type: "server", Toml: "bindPort = 7000", AutoStart: true, Retry: true, NetworkReconnect: true}
	if r := send(request{Action: "configure", Profiles: []profile{p}}); r.Error != "" || !idle() {
		t.Fatalf("offline configuration failed: %+v", r)
	}
	if r := send(request{Action: "pruneLogs", Before: time.Now().UnixMilli()}); r.Error != "" || !idle() {
		t.Fatalf("log maintenance started the idle module: %+v", r)
	}
	if err := os.WriteFile(filepath.Join(modulePath, "remove"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	if r := send(request{Action: "start", Profile: p}); r.Error != "MODULE_DISABLED" {
		t.Fatalf("module pending removal accepted start: %+v", r)
	}
	run("", "module", "install")
	var reinstalled moduleStatus
	if err := json.Unmarshal(run("", "module", "status"), &reinstalled); err != nil || !reinstalled.Enabled || !idle() {
		t.Fatalf("reinstall did not cancel removal: %+v %v", reinstalled, err)
	}
	for i := 0; i < 2; i++ {
		if r := send(request{Action: "start", Profile: p}); r.Error != "" || !running() {
			t.Fatalf("start failed: %+v", r)
		}
		if r := send(request{Action: "stopAll"}); r.Error != "" {
			t.Fatal(r)
		}
		eventually(t, 3*time.Second, idle)
	}
	run("", "module", "stop")
	if r := send(request{Action: "start", Profile: p}); r.Error != "MODULE_DISABLED" {
		t.Fatalf("disabled module accepted start: %+v", r)
	}
	run("", "module", "start")
	if !idle() {
		t.Fatal("enabling module revived stopped profiles in the same boot")
	}
	data, err := os.ReadFile(filepath.Join(root, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	var saved snapshot
	json.Unmarshal(data, &saved)
	saved.BootID = "previous-boot"
	data, _ = json.Marshal(saved)
	if err := os.WriteFile(filepath.Join(root, "state.json"), data, 0600); err != nil {
		t.Fatal(err)
	}
	run("", "module", "start")
	if !running() {
		t.Fatal("autostart was not restored after reboot")
	}
	pid, err := os.ReadFile(filepath.Join(root, "watch.pid"))
	if err != nil {
		t.Fatal(err)
	}
	killSupervisor := func() {
		t.Helper()
		files, err := filepath.Glob("/proc/" + string(pid) + "/task/*/children")
		if err != nil {
			t.Fatal(err)
		}
		killed := 0
		for _, file := range files {
			children, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			for _, child := range strings.Fields(string(children)) {
				process, err := os.FindProcess(mustPID(t, child))
				if err != nil {
					t.Fatal(err)
				}
				if err := process.Signal(syscall.SIGKILL); err != nil {
					t.Fatal(err)
				}
				killed++
			}
		}
		if killed != 1 {
			t.Fatalf("expected one supervisor, found %d", killed)
		}
	}
	killSupervisor()
	eventually(t, 2*time.Second, func() bool { return !running() })
	eventually(t, 15*time.Second, running)
	killSupervisor()
	eventually(t, 2*time.Second, func() bool { return !running() })
	send(request{Action: "stopAll"})
	eventually(t, 3*time.Second, idle)
	time.Sleep(11 * time.Second)
	if !idle() {
		t.Fatal("watcher revived stopped configuration")
	}
}

func mustPID(t *testing.T, value string) int {
	t.Helper()
	var pid int
	if err := json.Unmarshal([]byte(value), &pid); err != nil {
		t.Fatal(err)
	}
	return pid
}
