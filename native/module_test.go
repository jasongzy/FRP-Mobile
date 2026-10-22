package main

import (
	"os"
	"path/filepath"
	"strconv"
	"syscall"
	"testing"
)

func TestStopWatcherIgnoresStalePIDOnlyWhenUnlocked(t *testing.T) {
	previous := moduleRoot
	moduleRoot = t.TempDir()
	t.Cleanup(func() { moduleRoot = previous })
	lock, err := os.OpenFile(filepath.Join(moduleRoot, "watch.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	pidPath := filepath.Join(moduleRoot, "watch.pid")
	for _, held := range []bool{false, true} {
		if held {
			if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
				t.Fatal(err)
			}
		}
		if err := os.WriteFile(pidPath, []byte(strconv.Itoa(os.Getpid())), 0600); err != nil {
			t.Fatal(err)
		}
		err := stopWatcher()
		if held {
			if err == nil || err.Error() != "WATCHER_IDENTITY_CHANGED" {
				t.Fatalf("active lock bypassed identity check: %v", err)
			}
		} else if _, statErr := os.Stat(pidPath); err != nil || !os.IsNotExist(statErr) {
			t.Fatalf("stale PID blocked cleanup: %v, %v", err, statErr)
		}
	}
}

func TestModuleVersion(t *testing.T) {
	oldDir, oldRoot, oldCode, oldFrp := moduleDir, moduleRoot, moduleVersionCode, frpVersion
	t.Cleanup(func() { moduleDir, moduleRoot, moduleVersionCode, frpVersion = oldDir, oldRoot, oldCode, oldFrp })
	moduleVersionCode, frpVersion = "2", "0.71.1"
	for _, test := range []struct {
		name, code, frp string
		installed       bool
		versionCode     int
		needsReinstall  bool
	}{
		{"absent", "", "", false, 0, false},
		{"invalid", "invalid", "0.71.1", true, 0, true},
		{"older-module", "1", "0.71.1", true, 1, true},
		{"same", "2", "0.71.1", true, 2, false},
		{"newer-module", "3", "0.71.1", true, 3, true},
		{"older-core", "2", "0.70.0", true, 2, true},
		{"newer-core", "2", "0.72.0", true, 2, true},
		{"missing-core", "2", "", true, 2, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			moduleDir, moduleRoot = root, filepath.Join(root, "runtime")
			if test.installed {
				properties := "version=development\nversionCode=" + test.code + "\nfrpVersion=" + test.frp + "\n"
				if err := os.WriteFile(filepath.Join(moduleDir, "module.prop"), []byte(properties), 0600); err != nil {
					t.Fatal(err)
				}
			}
			status, err := inspectModule()
			if err != nil || status.Installed != test.installed || status.VersionCode != test.versionCode ||
				status.NeedsReinstall != test.needsReinstall || status.BundledVersionCode != 2 ||
				status.BundledVersion != moduleVersionName || status.BundledFrpVersion != frpVersion ||
				status.Enabled != test.installed || status.Running {
				t.Fatalf("status: %+v, error: %v", status, err)
			}
			if test.installed && (status.Version != "development" || status.FrpVersion != test.frp) {
				t.Fatalf("installed versions: %+v", status)
			}
		})
	}
}
