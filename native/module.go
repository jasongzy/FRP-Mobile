package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode"
)

var moduleDir = "/data/adb/modules/frp_mobile"
var moduleRoot = "/data/adb/frp-mobile"

var moduleVersionName = "v1.0"
var moduleVersionCode = "1"
var frpVersion = "unknown"

func moduleFiles() map[string]string {
	return map[string]string{
		"module.prop":  fmt.Sprintf("id=frp_mobile\nname=FRP Mobile\nversion=%s\nversionCode=%s\nfrpVersion=%s\nauthor=jasongzy\ndescription=FRP Mobile background service\n", moduleVersionName, moduleVersionCode, frpVersion),
		"service.sh":   "#!/system/bin/sh\nMODDIR=${0%/*}\n[ -e \"$MODDIR/disable\" ] && exit 0\n[ -e \"$MODDIR/remove\" ] && exit 0\n/data/adb/frp-mobile/bin/libfrpmobile.so module start\n",
		"uninstall.sh": "#!/system/bin/sh\n/data/adb/frp-mobile/bin/libfrpmobile.so module uninstall\n",
		"action.sh":    "#!/system/bin/sh\n/system/bin/am start --user 0 -n com.jasongzy.frpmobile/.MainActivity\n",
	}
}

type moduleStatus struct {
	Installed          bool   `json:"installed"`
	Enabled            bool   `json:"enabled"`
	Running            bool   `json:"running"`
	Version            string `json:"version"`
	VersionCode        int    `json:"versionCode"`
	FrpVersion         string `json:"frpVersion"`
	BundledVersion     string `json:"bundledVersion"`
	BundledVersionCode int    `json:"bundledVersionCode"`
	BundledFrpVersion  string `json:"bundledFrpVersion"`
	NeedsReinstall     bool   `json:"needsReinstall"`
}

func inspectModule() (moduleStatus, error) {
	code, err := strconv.ParseInt(moduleVersionCode, 10, 32)
	if err != nil || code <= 0 {
		return moduleStatus{}, errors.New("INVALID_MODULE_VERSION_CODE")
	}
	status := moduleStatus{BundledVersion: moduleVersionName, BundledVersionCode: int(code), BundledFrpVersion: frpVersion}
	data, err := os.ReadFile(filepath.Join(moduleDir, "module.prop"))
	if os.IsNotExist(err) {
		return status, nil
	}
	if err != nil {
		return status, err
	}
	status.Installed = true
	for _, line := range strings.Split(string(data), "\n") {
		key, value, ok := strings.Cut(line, "=")
		if !ok || len(value) > 64 || strings.ContainsFunc(value, unicode.IsControl) {
			continue
		}
		switch key {
		case "version":
			status.Version = value
		case "versionCode":
			if code, err := strconv.ParseInt(value, 10, 32); err == nil && code > 0 {
				status.VersionCode = int(code)
			}
		case "frpVersion":
			status.FrpVersion = value
		}
	}
	status.NeedsReinstall = status.VersionCode != status.BundledVersionCode || status.FrpVersion != status.BundledFrpVersion
	_, disabled := os.Stat(filepath.Join(moduleDir, "disable"))
	_, removing := os.Stat(filepath.Join(moduleDir, "remove"))
	_, running := call(moduleRoot, request{Action: "status"})
	status.Enabled = os.IsNotExist(disabled) && os.IsNotExist(removing)
	status.Running = running == nil
	return status, nil
}

func module(args []string) error {
	if os.Geteuid() != 0 || len(args) < 1 {
		return errors.New("ROOT_REQUIRED")
	}
	if err := exec.Command("magisk", "-V").Run(); err != nil {
		return errors.New("MAGISK_REQUIRED")
	}
	switch args[0] {
	case "status":
		status, err := inspectModule()
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(status)
	case "install":
		if _, err := os.Stat(filepath.Join(moduleDir, "module.prop")); err == nil {
			if err := module([]string{"stop"}); err != nil {
				return err
			}
		}
		if err := privateDir(moduleDir); err != nil {
			return err
		}
		if err := privateDir(moduleRoot); err != nil {
			return err
		}
		if err := privateDir(filepath.Join(moduleRoot, "bin")); err != nil {
			return err
		}
		executable, err := os.Executable()
		if err != nil {
			return err
		}
		for _, name := range []string{"libfrpmobile.so", "libfrpc.so", "libfrps.so"} {
			data, err := os.ReadFile(filepath.Join(filepath.Dir(executable), name))
			if err != nil {
				return err
			}
			if err := atomicWrite(filepath.Join(moduleRoot, "bin", name), data, 0700); err != nil {
				return err
			}
		}
		for name, content := range moduleFiles() {
			if err := atomicWrite(filepath.Join(moduleDir, name), []byte(content), 0700); err != nil {
				return err
			}
		}
		return module([]string{"start"})
	case "start":
		for _, name := range []string{"disable", "remove"} {
			if err := os.Remove(filepath.Join(moduleDir, name)); err != nil && !os.IsNotExist(err) {
				return err
			}
		}
		_, err := moduleRequest(request{Action: "resume"})
		return err
	case "watch":
		lock, err := os.OpenFile(filepath.Join(moduleRoot, "watch.lock"), os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
		if err != nil {
			return err
		}
		defer lock.Close()
		if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
			return nil
		}
		if err := atomicWrite(filepath.Join(moduleRoot, "watch.pid"), []byte(strconv.Itoa(os.Getpid())), 0600); err != nil {
			return err
		}
		defer os.Remove(filepath.Join(moduleRoot, "watch.pid"))
		stopped := make(chan os.Signal, 1)
		signal.Notify(stopped, syscall.SIGTERM, syscall.SIGINT)
		defer signal.Stop(stopped)
		bin := filepath.Join(moduleRoot, "bin")
		attempt := 0
		for {
			if _, err := os.Stat(filepath.Join(moduleDir, "module.prop")); err != nil {
				return err
			}
			if _, err := os.Stat(filepath.Join(moduleDir, "disable")); err == nil {
				return nil
			}
			if _, err := os.Stat(filepath.Join(moduleDir, "remove")); err == nil {
				return nil
			}
			m := newManager(moduleRoot, bin, true)
			if err := m.load(); err != nil {
				return err
			}
			if !m.needsRun() {
				return nil
			}
			cmd := exec.Command(filepath.Join(bin, "libfrpmobile.so"), "serve", moduleRoot, bin, "0")
			cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
			started := time.Now()
			if err := cmd.Start(); err == nil {
				exited := make(chan error, 1)
				go func() { exited <- cmd.Wait() }()
				select {
				case <-stopped:
					cmd.Process.Signal(syscall.SIGTERM)
					<-exited
					return nil
				case <-exited:
				}
			}
			m = newManager(moduleRoot, bin, true)
			if err := m.load(); err != nil {
				return err
			}
			if !m.needsRun() {
				return nil
			}
			if _, err := os.Stat(filepath.Join(moduleDir, "disable")); err == nil {
				return nil
			}
			if time.Since(started) >= time.Minute {
				attempt = 0
			}
			attempt++
			select {
			case <-stopped:
				return nil
			case <-time.After(retryDelay(attempt)):
			}
		}
	case "stop", "uninstall":
		if _, err := os.Stat(moduleDir); err != nil {
			if os.IsNotExist(err) {
				return nil
			}
			return err
		}
		if err := privateDir(moduleDir); err != nil {
			return err
		}
		if err := atomicWrite(filepath.Join(moduleDir, "disable"), nil, 0600); err != nil {
			return err
		}
		if result, err := moduleRequest(request{Action: "shutdown"}); err != nil {
			return err
		} else if result.Error != "" {
			return errors.New(result.Error)
		}
		if err := stopWatcher(); err != nil {
			return err
		}
		for _, name := range []string{"daemon.lock", "watch.lock"} {
			lock, err := os.OpenFile(filepath.Join(moduleRoot, name), os.O_RDWR|syscall.O_NOFOLLOW, 0600)
			if os.IsNotExist(err) {
				continue
			}
			if err != nil {
				return err
			}
			acquired := false
			for i := 0; i < 80; i++ {
				if syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB) == nil {
					acquired = true
					break
				}
				time.Sleep(100 * time.Millisecond)
			}
			lock.Close()
			if !acquired {
				return errors.New("DAEMON_STOP_TIMEOUT")
			}
		}
		if err := os.Remove(filepath.Join(moduleRoot, "control.sock")); err != nil && !os.IsNotExist(err) {
			return err
		}
		if args[0] == "uninstall" {
			for _, path := range []string{moduleDir, moduleRoot} {
				if err := privateDir(path); err != nil {
					return err
				}
				if err := os.RemoveAll(path); err != nil {
					return err
				}
			}
		}
		return nil
	default:
		return errors.New("invalid module action")
	}
}

func stopWatcher() error {
	lock, err := os.OpenFile(filepath.Join(moduleRoot, "watch.lock"), os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err == nil {
		if err := os.Remove(filepath.Join(moduleRoot, "watch.pid")); err != nil && !os.IsNotExist(err) {
			return err
		}
		return nil
	} else if err != syscall.EWOULDBLOCK {
		return err
	}
	data, err := os.ReadFile(filepath.Join(moduleRoot, "watch.pid"))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	pid, err := strconv.Atoi(string(data))
	if err != nil || pid <= 1 {
		return errors.New("INVALID_WATCHER_PID")
	}
	executable, err := os.Readlink(fmt.Sprintf("/proc/%d/exe", pid))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if strings.TrimSuffix(executable, " (deleted)") != filepath.Join(moduleRoot, "bin", "libfrpmobile.so") {
		return errors.New("WATCHER_IDENTITY_CHANGED")
	}
	command, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil || !strings.Contains(string(command), "\x00module\x00watch\x00") {
		return errors.New("WATCHER_IDENTITY_CHANGED")
	}
	if err := syscall.Kill(pid, syscall.SIGTERM); err != nil && err != syscall.ESRCH {
		return err
	}
	for i := 0; i < 100; i++ {
		if syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB) == nil {
			return nil
		}
		time.Sleep(50 * time.Millisecond)
	}
	return errors.New("WATCHER_STOP_TIMEOUT")
}

func startWatcher() error {
	output, err := os.OpenFile(os.DevNull, os.O_WRONLY, 0)
	if err != nil {
		return err
	}
	defer output.Close()
	cmd := exec.Command(filepath.Join(moduleRoot, "bin", "libfrpmobile.so"), "module", "watch")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	cmd.Stdout, cmd.Stderr = output, output
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}

func moduleRequest(req request) (response, error) {
	if req.Action == "watch" {
		return call(moduleRoot, req)
	}
	if err := privateDir(moduleRoot); err != nil {
		return response{}, err
	}
	control, err := os.OpenFile(filepath.Join(moduleRoot, "request.lock"), os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return response{}, err
	}
	defer control.Close()
	if err := syscall.Flock(int(control.Fd()), syscall.LOCK_EX); err != nil {
		return response{}, err
	}
	if req.Action == "start" || req.Action == "reload" {
		for _, name := range []string{"disable", "remove"} {
			if _, err := os.Stat(filepath.Join(moduleDir, name)); err == nil {
				return response{Error: "MODULE_DISABLED"}, nil
			}
		}
	}
	for deadline := time.Now().Add(20 * time.Second); time.Now().Before(deadline); {
		if req.Action != "resume" {
			if result, err := call(moduleRoot, req); err == nil && result.Error != "RUNTIME_IDLE" {
				return result, nil
			}
		} else if result, err := call(moduleRoot, request{Action: "status"}); err == nil {
			return result, nil
		}
		lock, err := os.OpenFile(filepath.Join(moduleRoot, "daemon.lock"), os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
		if err != nil {
			return response{}, err
		}
		if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
			lock.Close()
			time.Sleep(50 * time.Millisecond)
			continue
		}
		m := newManager(moduleRoot, filepath.Join(moduleRoot, "bin"), true)
		m.offline = true
		if err := m.load(); err != nil {
			lock.Close()
			return response{}, err
		}
		action := req.Action
		if action == "resume" {
			req.Action = "status"
		} else if action == "shutdown" {
			req.Action = "stopAll"
		}
		result := m.handle(req)
		if result.Error == "" && action == "resume" {
			err = m.save()
		}
		needsRun := m.needsRun()
		lock.Close()
		if err != nil {
			return response{}, err
		}
		if !needsRun && action != "status" {
			if err := stopWatcher(); err != nil {
				return response{}, err
			}
		} else if result.Error == "" && action != "status" && action != "pruneLogs" {
			if err := stopWatcher(); err != nil {
				return response{}, err
			}
			if err := startWatcher(); err != nil {
				return response{}, err
			}
			for i := 0; i < 100; i++ {
				if observed, err := call(moduleRoot, request{Action: "status"}); err == nil {
					return observed, nil
				}
				time.Sleep(50 * time.Millisecond)
			}
			return response{}, errors.New("DAEMON_START_FAILED")
		}
		return result, nil
	}
	return response{}, errors.New("DAEMON_BUSY")
}
