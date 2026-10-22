package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/pelletier/go-toml/v2"
)

func TestRestoreUsesSessionIntentWithinBootAndAutostartAfterReboot(t *testing.T) {
	for _, reboot := range []bool{false, true} {
		t.Run(map[bool]string{false: "same boot", true: "new boot"}[reboot], func(t *testing.T) {
			m := testManager(t)
			m.persist = true
			saved := snapshot{BootID: bootID(), Desired: []string{"manual"}, Profiles: []profile{
				{ID: "manual", Type: "client", Toml: "serverAddr = 'localhost'"},
				{ID: "automatic", Type: "server", Toml: "bindPort = 7000", AutoStart: true},
			}}
			if reboot {
				saved.BootID = "previous-boot"
			}
			data, _ := json.Marshal(saved)
			if err := atomicWrite(filepath.Join(m.root, "state.json"), data, 0600); err != nil {
				t.Fatal(err)
			}
			if err := m.restore(); err != nil {
				t.Fatal(err)
			}
			for _, observed := range m.status(0).States {
				expected := (observed.ID == "automatic") == reboot
				if (observed.State == "running") != expected || observed.Desired != expected {
					t.Fatalf("incorrect restored intent: %+v", observed)
				}
			}
		})
	}
}

func testManager(t *testing.T) *manager {
	t.Helper()
	root := t.TempDir()
	bin := t.TempDir()
	script := "#!/bin/sh\nif [ \"$1\" = verify ]; then grep -q INVALID \"$3\" && exit 1; exit 0; fi\nexec sleep 300\n"
	for _, name := range []string{"libfrpc.so", "libfrps.so"} {
		if err := os.WriteFile(filepath.Join(bin, name), []byte(script), 0700); err != nil {
			t.Fatal(err)
		}
	}
	m := newManager(root, bin, false)
	t.Cleanup(func() { m.handle(request{Action: "stopAll"}) })
	return m
}

func TestReloadValidationAndStop(t *testing.T) {
	m := testManager(t)
	p := profile{ID: "test", Type: "client", Toml: "serverAddr = 'localhost'"}
	if r := m.handle(request{Action: "start", Profile: p}); r.Error != "" {
		t.Fatal(r.Error)
	}
	p.Toml = "serverAddr = 'INVALID'"
	if r := m.handle(request{Action: "reload", Profile: p}); r.Error == "" || r.States[0].State != "running" {
		t.Fatalf("invalid replacement must preserve running process: %+v", r)
	}
	if r := m.handle(request{Action: "stop", ID: p.ID}); r.Error != "" || r.States[0].State != "stopped" {
		t.Fatal(r)
	}
	if _, err := os.Stat(filepath.Join(m.root, "instances", p.ID)); !os.IsNotExist(err) {
		t.Fatal("temporary secrets retained")
	}
}

func TestConfigurationBoundaryAndRedaction(t *testing.T) {
	m := testManager(t)
	p := profile{ID: "../escape", Type: "server", Toml: "bindPort = 7000"}
	if r := m.handle(request{Action: "start", Profile: p}); r.Error == "" {
		t.Fatal("accepted traversal")
	}
	p.ID = "safe"
	p.Files = map[string][]byte{"../key": []byte("secret")}
	if err := validateProfile(p); err == nil {
		t.Fatal("accepted unsafe certificate")
	}
	p.Files = map[string][]byte{"key.pem": []byte("private")}
	p.Toml = `auth.token = 'test-secret'
webServer.password = 'test-secret-long'
metadatas.note = 'Use frpmobile://key.pem here'
transport.tls.keyFile = "frpmobile://\u006bey.pem"
[[proxies]]
plugin.keyPath = 'frpmobile://key.pem'
plugin.crtPath = 'frpmobile://key.pem.bak'`
	dir := filepath.Join(m.root, "prepared")
	path, secrets, err := m.prepare(p, dir)
	if err != nil {
		t.Fatal(err)
	}
	config, _ := os.ReadFile(path)
	var prepared struct {
		Transport struct{ TLS struct{ KeyFile string } }
		Proxies   []struct {
			Plugin struct{ KeyPath, CrtPath string }
		}
		Metadatas map[string]string
	}
	if err := toml.Unmarshal(config, &prepared); err != nil {
		t.Fatal(err)
	}
	keyPath := filepath.ToSlash(filepath.Join(dir, "key.pem"))
	if prepared.Transport.TLS.KeyFile != keyPath || prepared.Proxies[0].Plugin.KeyPath != keyPath {
		t.Fatal("escaped or nested certificate reference was not resolved")
	}
	if prepared.Metadatas["note"] != "Use frpmobile://key.pem here" || prepared.Proxies[0].Plugin.CrtPath != "frpmobile://key.pem.bak" {
		t.Fatal("partial file reference was rewritten")
	}
	if redact("token test-secret password test-secret-long", secrets) != "token *** password ***" {
		t.Fatal("secret leaked")
	}
	target := t.TempDir()
	link := filepath.Join(m.root, "linked")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if _, _, err := m.prepare(p, link); err == nil {
		t.Fatal("accepted symlink")
	}
}

func TestConfigureRemovalAndBoundedLogs(t *testing.T) {
	m := testManager(t)
	p := profile{ID: "test", Type: "client", Toml: "serverAddr = 'localhost'"}
	if r := m.handle(request{Action: "start", Profile: p}); r.Error != "" {
		t.Fatal(r.Error)
	}
	if r := m.handle(request{Action: "configure", Profiles: []profile{p, p}}); r.Error == "" {
		t.Fatal("accepted duplicate")
	}
	if r := m.handle(request{Action: "configure"}); r.Error != "" || len(r.States) != 0 {
		t.Fatal(r)
	}
	for i := 0; i < 1100; i++ {
		m.addLog(p, "info", "message")
	}
	r := m.status(0)
	if len(r.Logs) != 1000 || r.Logs[0].Seq != 101 {
		t.Fatal("unbounded log ring")
	}
	if len(m.status(r.Cursor).Logs) != 0 || len(m.status(r.Cursor+1).Logs) != 1000 {
		t.Fatal("invalid cursor handling")
	}
	pruned := m.handle(request{Action: "pruneLogs", Before: r.Logs[len(r.Logs)-1].Time + 1})
	if pruned.Error != "" || len(pruned.Logs) != 0 || pruned.Cursor != r.Cursor {
		t.Fatal("log pruning changed the cursor or retained expired entries")
	}
}
