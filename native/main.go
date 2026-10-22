package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/pelletier/go-toml/v2"
)

const maxInput = 16 << 20

var validID = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,80}$`)

type profile struct {
	ID               string            `json:"id"`
	Type             string            `json:"type"`
	Toml             string            `json:"toml"`
	AutoStart        bool              `json:"autoStart"`
	Retry            bool              `json:"retry"`
	NetworkReconnect bool              `json:"networkReconnect"`
	Files            map[string][]byte `json:"files,omitempty"`
}

type request struct {
	Action   string    `json:"action"`
	Profile  profile   `json:"profile"`
	ID       string    `json:"id"`
	After    int64     `json:"after"`
	Profiles []profile `json:"profiles"`
	Revision uint64    `json:"revision"`
	Before   int64     `json:"before"`
}

type state struct {
	ID      string `json:"id"`
	Type    string `json:"type"`
	State   string `json:"state"`
	Error   string `json:"error,omitempty"`
	Desired bool   `json:"desired"`
}

type logEntry struct {
	Seq     int64  `json:"seq"`
	ID      string `json:"id"`
	Type    string `json:"type"`
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    int64  `json:"time"`
}

type response struct {
	Error    string     `json:"error,omitempty"`
	States   []state    `json:"states"`
	Logs     []logEntry `json:"logs"`
	Cursor   int64      `json:"cursor"`
	Revision uint64     `json:"revision"`
}

type instance struct {
	profile   profile
	cmd       *exec.Cmd
	done      chan struct{}
	state     state
	desired   bool
	cancel    chan struct{}
	attempt   int
	startedAt time.Time
	retrying  bool
}

type snapshot struct {
	BootID   string    `json:"bootId"`
	Profiles []profile `json:"profiles"`
	Desired  []string  `json:"desired"`
	States   []state   `json:"states,omitempty"`
}

type manager struct {
	opMu      sync.Mutex
	mu        sync.Mutex
	root      string
	bin       string
	persist   bool
	instances map[string]*instance
	logMu     sync.Mutex
	logs      []logEntry
	cursor    int64
	offline   bool
	eventMu   sync.Mutex
	changed   chan struct{}
	revision  uint64
	closing   bool
}

func newManager(root, bin string, persist bool) *manager {
	return &manager{root: root, bin: bin, persist: persist, instances: make(map[string]*instance), changed: make(chan struct{})}
}

func (m *manager) notify() {
	m.eventMu.Lock()
	close(m.changed)
	m.changed = make(chan struct{})
	m.revision++
	m.eventMu.Unlock()
}

func (m *manager) events() (<-chan struct{}, uint64) {
	m.eventMu.Lock()
	defer m.eventMu.Unlock()
	return m.changed, m.revision
}

func (m *manager) needsRun() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, item := range m.instances {
		if item.cmd != nil || (item.desired && (item.profile.Retry || (m.persist && item.profile.NetworkReconnect) || item.state.State == "pending")) {
			return true
		}
	}
	return false
}

func retryDelay(attempt int) time.Duration {
	delays := []time.Duration{10, 30, 60, 120, 300, 600}
	base := delays[min(max(attempt, 1)-1, len(delays)-1)] * time.Second
	return time.Duration(float64(base) * (0.9 + rand.Float64()*0.1))
}

func atomicWrite(path string, data []byte, mode os.FileMode) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".write-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if err = f.Chmod(mode); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(f.Name(), path)
}

func privateDir(path string) error {
	path = filepath.Clean(path)
	if parent := filepath.Dir(path); parent != path {
		info, err := os.Lstat(parent)
		if os.IsNotExist(err) {
			if err := privateDir(parent); err != nil {
				return err
			}
		} else if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("unsafe parent directory")
		}
	}
	if err := os.MkdirAll(path, 0700); err != nil {
		return err
	}
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("unsafe directory")
	}
	return os.Chmod(path, 0700)
}

func validateProfile(p profile) error {
	if !validID.MatchString(p.ID) || (p.Type != "client" && p.Type != "server") {
		return errors.New("invalid profile identity")
	}
	if len(p.Toml) > 1<<20 || len(p.Files) > 3 {
		return errors.New("invalid configuration size")
	}
	for name, data := range p.Files {
		if (name != "ca.pem" && name != "cert.pem" && name != "key.pem") || len(data) > 1<<20 {
			return errors.New("invalid certificate")
		}
	}
	return nil
}

func collectSecrets(value any, key string, out *[]string) {
	switch v := value.(type) {
	case map[string]any:
		for k, child := range v {
			collectSecrets(child, strings.ToLower(k), out)
		}
	case []any:
		for _, child := range v {
			collectSecrets(child, key, out)
		}
	case string:
		if len(v) > 0 && (strings.Contains(key, "token") || strings.Contains(key, "password") ||
			strings.Contains(key, "secret") || key == "authorization" || key == "sk") {
			*out = append(*out, v)
		}
	}
}

func redact(message string, secrets []string) string {
	for _, secret := range secrets {
		message = strings.ReplaceAll(message, secret, "***")
	}
	return message
}

func resolveFiles(value any, paths map[string]string) any {
	switch v := value.(type) {
	case map[string]any:
		for key, child := range v {
			v[key] = resolveFiles(child, paths)
		}
	case []any:
		for i, child := range v {
			v[i] = resolveFiles(child, paths)
		}
	case string:
		if path, ok := paths[v]; ok {
			return path
		}
	}
	return value
}

func (m *manager) prepare(p profile, dir string, captureLogs bool) (string, []string, error) {
	if err := validateProfile(p); err != nil {
		return "", nil, err
	}
	if err := privateDir(dir); err != nil {
		return "", nil, err
	}
	values := make(map[string]any)
	if err := toml.Unmarshal([]byte(p.Toml), &values); err != nil {
		return "", nil, errors.New("INVALID_TOML: unable to parse configuration")
	}
	paths := make(map[string]string, len(p.Files))
	for name, data := range p.Files {
		path := filepath.Join(dir, name)
		if err := atomicWrite(path, data, 0600); err != nil {
			return "", nil, err
		}
		paths["frpmobile://"+name] = filepath.ToSlash(path)
	}
	resolveFiles(values, paths)
	var secrets []string
	collectSecrets(values, "", &secrets)
	sort.Slice(secrets, func(i, j int) bool { return len(secrets[i]) > len(secrets[j]) })
	if captureLogs {
		logConfig, _ := values["log"].(map[string]any)
		if logConfig == nil {
			logConfig = make(map[string]any)
		}
		logConfig["to"] = "console"
		logConfig["disablePrintColor"] = true
		values["log"] = logConfig
	}
	data, err := toml.Marshal(values)
	if err != nil {
		return "", nil, err
	}
	path := filepath.Join(dir, "config.toml")
	return path, secrets, atomicWrite(path, data, 0600)
}

func (m *manager) executable(typ string) string {
	name := "libfrpc.so"
	if typ == "server" {
		name = "libfrps.so"
	}
	return filepath.Join(m.bin, name)
}

func (m *manager) verify(ctx context.Context, p profile) error {
	dir, err := os.MkdirTemp(m.root, ".verify-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	path, secrets, err := m.prepare(p, dir, false)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, m.executable(p.Type), "verify", "-c", path)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
	var output limitedBuffer
	cmd.Stdout, cmd.Stderr = &output, &output
	if err = cmd.Run(); err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return errors.New("VERIFY_TIMEOUT: frp verification timed out")
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return fmt.Errorf("INVALID_TOML: %v %s", err, redact(strings.TrimSpace(output.String()), secrets))
	}
	return nil
}

type limitedBuffer struct{ data []byte }

func (b *limitedBuffer) Write(p []byte) (int, error) {
	n := len(p)
	remaining := (64 << 10) - len(b.data)
	if remaining > 0 {
		b.data = append(b.data, p[:min(remaining, n)]...)
	}
	return n, nil
}
func (b *limitedBuffer) String() string { return string(b.data) }

func (m *manager) start(p profile) error {
	if err := validateProfile(p); err != nil {
		return err
	}
	m.mu.Lock()
	old := m.instances[p.ID]
	if old != nil && old.cmd != nil {
		m.mu.Unlock()
		return nil
	}
	m.mu.Unlock()
	dir := filepath.Join(m.root, "instances", p.ID)
	path, secrets, err := m.prepare(p, dir, true)
	if err != nil {
		os.RemoveAll(dir)
		return err
	}
	cmd := exec.Command(m.executable(p.Type), "-c", path)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL, Setpgid: true}
	reader, writer := io.Pipe()
	cmd.Stdout, cmd.Stderr = writer, writer
	if err = cmd.Start(); err != nil {
		reader.Close()
		writer.Close()
		os.RemoveAll(dir)
		return err
	}
	item := &instance{profile: p, cmd: cmd, done: make(chan struct{}), desired: true,
		state: state{ID: p.ID, Type: p.Type, State: "running"}, cancel: make(chan struct{}), startedAt: time.Now()}
	m.mu.Lock()
	if old != nil {
		item.attempt = old.attempt
		if old.cancel != nil {
			close(old.cancel)
			old.cancel = nil
			old.retrying = false
		}
	}
	m.instances[p.ID] = item
	m.mu.Unlock()
	logDone := make(chan struct{})
	go func() {
		defer close(logDone)
		defer reader.Close()
		scanner := bufio.NewScanner(reader)
		scanner.Buffer(make([]byte, 4096), 64<<10)
		for scanner.Scan() {
			level := "info"
			line := scanner.Text()
			for marker, candidate := range map[string]string{"[E]": "error", "[W]": "warn", "[D]": "debug", "[T]": "trace"} {
				if strings.Contains(line, marker) {
					level = candidate
					break
				}
			}
			m.addLog(p, level, redact(line, secrets))
		}
		if scanner.Err() != nil {
			m.addLog(p, "error", "LOG_LIMIT: oversized log line")
			io.Copy(io.Discard, reader)
		}
	}()
	go func() {
		err := cmd.Wait()
		writer.Close()
		<-logDone
		if err := os.RemoveAll(dir); err != nil {
			m.addLog(p, "error", "CONFIG_CLEANUP_FAILED")
		}
		m.mu.Lock()
		item.cmd = nil
		if time.Since(item.startedAt) >= time.Minute {
			item.attempt = 0
		}
		item.state.State = "stopped"
		if err != nil && item.desired {
			item.state.State = "failed"
			item.state.Error = err.Error()
		}
		m.mu.Unlock()
		close(item.done)
		m.opMu.Lock()
		m.mu.Lock()
		current := m.instances[p.ID] == item && item.desired
		retry := current && item.profile.Retry
		m.mu.Unlock()
		if current {
			if err := m.save(); err != nil {
				m.addLog(p, "error", "STATE_SAVE_FAILED")
			}
			m.notify()
		}
		m.opMu.Unlock()
		if retry {
			m.retry(item)
		}
	}()
	m.notify()
	return nil
}

func (m *manager) retry(item *instance) {
	m.mu.Lock()
	if item.retrying {
		m.mu.Unlock()
		return
	}
	item.retrying = true
	if item.cancel == nil {
		item.cancel = make(chan struct{})
	}
	cancel := item.cancel
	m.mu.Unlock()
	defer func() {
		m.mu.Lock()
		if item.cancel == cancel {
			item.retrying = false
		}
		m.mu.Unlock()
	}()
	for {
		m.mu.Lock()
		if m.instances[item.profile.ID] != item || !item.desired || !item.profile.Retry {
			m.mu.Unlock()
			return
		}
		item.attempt++
		delay := retryDelay(item.attempt)
		m.mu.Unlock()
		select {
		case <-cancel:
			return
		case <-time.After(delay):
		}
		m.opMu.Lock()
		m.mu.Lock()
		p := item.profile
		current := m.instances[p.ID] == item && item.desired && p.Retry
		m.mu.Unlock()
		if !current {
			m.opMu.Unlock()
			return
		}
		err := m.verify(context.Background(), p)
		if err == nil {
			err = m.start(p)
		}
		if err != nil {
			m.addLog(p, "error", "RETRY_FAILED")
			m.mu.Lock()
			item.state.State, item.state.Error = "failed", err.Error()
			item.desired = !strings.HasPrefix(err.Error(), "INVALID_TOML")
			m.mu.Unlock()
		}
		if saveErr := m.save(); saveErr != nil {
			m.addLog(p, "error", "STATE_SAVE_FAILED")
		}
		m.notify()
		m.opMu.Unlock()
		if err == nil {
			return
		}
	}
}

func (m *manager) stop(id string) error {
	m.mu.Lock()
	item := m.instances[id]
	if item == nil {
		m.mu.Unlock()
		return nil
	}
	item.desired = false
	if item.cancel != nil {
		close(item.cancel)
		item.cancel = nil
		item.retrying = false
	}
	cmd, done := item.cmd, item.done
	m.mu.Unlock()
	if cmd != nil {
		syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM)
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
			select {
			case <-done:
			case <-time.After(2 * time.Second):
				return errors.New("STOP_FAILED: process did not exit")
			}
		}
	}
	m.mu.Lock()
	item.state.State = "stopped"
	item.state.Error = ""
	m.mu.Unlock()
	return os.RemoveAll(filepath.Join(m.root, "instances", id))
}

func (m *manager) addLog(p profile, level, message string) {
	m.logMu.Lock()
	defer m.logMu.Unlock()
	if len(message) > 2048 {
		message = message[:2048] + "…"
	}
	m.cursor++
	m.logs = append(m.logs, logEntry{m.cursor, p.ID, p.Type, level, message, time.Now().UnixMilli()})
	if len(m.logs) > 1000 {
		copy(m.logs, m.logs[len(m.logs)-1000:])
		m.logs = m.logs[:1000]
	}
	m.notify()
}

func (m *manager) stopMany(ids []string) error {
	var workers sync.WaitGroup
	results := make(chan error, len(ids))
	for _, id := range ids {
		workers.Go(func() { results <- m.stop(id) })
	}
	workers.Wait()
	close(results)
	var result error
	for err := range results {
		result = errors.Join(result, err)
	}
	return result
}

func (m *manager) status(after int64) response {
	result := response{States: []state{}, Logs: []logEntry{}}
	_, result.Revision = m.events()
	m.mu.Lock()
	for _, item := range m.instances {
		observed := item.state
		if observed.State == "pending" {
			observed.State = "stopped"
		}
		observed.Desired = item.desired
		result.States = append(result.States, observed)
	}
	m.mu.Unlock()
	sort.Slice(result.States, func(i, j int) bool { return result.States[i].ID < result.States[j].ID })
	m.logMu.Lock()
	for _, entry := range m.logs {
		if entry.Seq > after || after > m.cursor {
			result.Logs = append(result.Logs, entry)
		}
	}
	result.Cursor = m.cursor
	m.logMu.Unlock()
	return result
}

func bootID() string {
	data, _ := os.ReadFile("/proc/sys/kernel/random/boot_id")
	return strings.TrimSpace(string(data))
}

func (m *manager) save() error {
	if !m.persist {
		return nil
	}
	saved := snapshot{BootID: bootID()}
	m.mu.Lock()
	for _, item := range m.instances {
		saved.Profiles = append(saved.Profiles, item.profile)
		if item.desired {
			saved.Desired = append(saved.Desired, item.profile.ID)
		}
		saved.States = append(saved.States, item.state)
	}
	m.mu.Unlock()
	data, err := json.Marshal(saved)
	if err != nil {
		return err
	}
	return atomicWrite(filepath.Join(m.root, "state.json"), data, 0600)
}

func (m *manager) load() error {
	data, err := os.ReadFile(filepath.Join(m.root, "state.json"))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var saved snapshot
	if err := json.Unmarshal(data, &saved); err != nil {
		return err
	}
	desired := make(map[string]bool)
	for _, id := range saved.Desired {
		desired[id] = true
	}
	for _, p := range saved.Profiles {
		if err := validateProfile(p); err != nil {
			return err
		}
	}
	for _, p := range saved.Profiles {
		run := desired[p.ID]
		if saved.BootID != bootID() {
			run = p.AutoStart
		}
		item := &instance{profile: p, desired: run, state: state{ID: p.ID, Type: p.Type, State: "stopped"}}
		if run {
			item.state.State = "pending"
		}
		if saved.BootID == bootID() {
			for _, observed := range saved.States {
				if observed.ID == p.ID && (observed.State == "failed" || observed.State == "stopped") {
					item.state = observed
				}
			}
		}
		m.mu.Lock()
		m.instances[p.ID] = item
		m.mu.Unlock()
	}
	if data, err := os.ReadFile(filepath.Join(m.root, "exit.json")); err == nil {
		var previous response
		if err := json.Unmarshal(data, &previous); err != nil {
			return err
		}
		m.logs, m.cursor = previous.Logs, previous.Cursor
	} else if !os.IsNotExist(err) {
		return err
	}
	return nil
}

func (m *manager) restore() error {
	m.opMu.Lock()
	defer m.opMu.Unlock()
	if err := m.load(); err != nil {
		return err
	}
	for _, item := range m.instances {
		p := item.profile
		if item.desired && (p.Retry || item.state.State == "pending") {
			err := m.verify(context.Background(), p)
			if err == nil {
				err = m.start(p)
			}
			if err != nil {
				m.addLog(p, "error", "RESTORE_FAILED")
				m.mu.Lock()
				item.state.State, item.state.Error = "failed", "RESTORE_FAILED"
				item.desired = !strings.HasPrefix(err.Error(), "INVALID_TOML")
				m.mu.Unlock()
				if item.desired && p.Retry {
					go m.retry(item)
				}
			}
		}
	}
	return m.save()
}

func (m *manager) handle(req request) response {
	m.opMu.Lock()
	defer m.opMu.Unlock()
	if m.closing {
		return response{Error: "RUNTIME_IDLE"}
	}
	var err error
	switch req.Action {
	case "status":
	case "pruneLogs":
		m.logMu.Lock()
		retained := m.logs[:0]
		for _, entry := range m.logs {
			if entry.Time >= req.Before {
				retained = append(retained, entry)
			}
		}
		m.logs = retained
		m.logMu.Unlock()
		if m.offline {
			data, marshalErr := json.Marshal(m.status(0))
			err = marshalErr
			if err == nil {
				err = atomicWrite(filepath.Join(m.root, "exit.json"), data, 0600)
			}
		}
	case "start", "reload":
		if err = m.verify(context.Background(), req.Profile); err == nil {
			m.mu.Lock()
			var previous *profile
			if item := m.instances[req.Profile.ID]; item != nil && item.cmd != nil {
				saved := item.profile
				previous = &saved
			}
			m.mu.Unlock()
			if m.offline {
				m.mu.Lock()
				m.instances[req.Profile.ID] = &instance{profile: req.Profile, desired: true,
					state: state{ID: req.Profile.ID, Type: req.Profile.Type, State: "pending"}}
				m.mu.Unlock()
			} else if req.Action == "reload" {
				err = m.stop(req.Profile.ID)
			}
			if err == nil && !m.offline {
				err = m.start(req.Profile)
				if err != nil && previous != nil {
					err = errors.Join(err, m.start(*previous))
				}
			}
		}
		if err != nil && validateProfile(req.Profile) == nil {
			m.mu.Lock()
			item := m.instances[req.Profile.ID]
			if item == nil {
				item = &instance{profile: req.Profile, state: state{ID: req.Profile.ID, Type: req.Profile.Type}}
				m.instances[req.Profile.ID] = item
			}
			if item.cmd == nil {
				item.profile = req.Profile
				item.state.State, item.state.Error = "failed", err.Error()
				item.desired = !strings.HasPrefix(err.Error(), "INVALID_TOML")
				if item.desired && item.profile.Retry && !m.offline {
					go m.retry(item)
				}
			}
			m.mu.Unlock()
		}
	case "stop", "delete":
		if !validID.MatchString(req.ID) {
			err = errors.New("invalid profile ID")
		} else {
			err = m.stop(req.ID)
			if err == nil && req.Action == "delete" {
				m.mu.Lock()
				delete(m.instances, req.ID)
				m.mu.Unlock()
			}
		}
	case "stopAll":
		m.mu.Lock()
		ids := make([]string, 0, len(m.instances))
		for id := range m.instances {
			ids = append(ids, id)
		}
		m.mu.Unlock()
		err = m.stopMany(ids)
	case "configure":
		seen := make(map[string]bool)
		for _, p := range req.Profiles {
			if err = validateProfile(p); err != nil {
				break
			}
			if seen[p.ID] {
				err = errors.New("duplicate profile")
				break
			}
			seen[p.ID] = true
		}
		if err == nil {
			m.mu.Lock()
			var removed []string
			for id := range m.instances {
				if !seen[id] {
					removed = append(removed, id)
				}
			}
			m.mu.Unlock()
			for _, id := range removed {
				if err = m.stop(id); err != nil {
					break
				}
				m.mu.Lock()
				delete(m.instances, id)
				m.mu.Unlock()
			}
		}
		if err == nil {
			m.mu.Lock()
			for _, p := range req.Profiles {
				if item := m.instances[p.ID]; item != nil {
					item.profile = p
					if !p.Retry && item.cancel != nil {
						close(item.cancel)
						item.cancel = nil
						item.retrying = false
					} else if p.Retry && item.desired && item.cmd == nil && !m.offline {
						go m.retry(item)
					}
				} else {
					m.instances[p.ID] = &instance{profile: p, state: state{ID: p.ID, Type: p.Type, State: "stopped"}}
				}
			}
			m.mu.Unlock()
		}
	default:
		err = errors.New("unknown action")
	}
	if req.Action != "status" {
		err = errors.Join(err, m.save())
		m.notify()
	}
	result := m.status(req.After)
	if err != nil {
		result.Error = err.Error()
	}
	return result
}

func serve(root, bin string, parent int) error {
	if err := privateDir(root); err != nil {
		return err
	}
	lock, err := os.OpenFile(filepath.Join(root, "daemon.lock"), os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		return errors.New("daemon already running")
	}
	if err := os.RemoveAll(filepath.Join(root, "instances")); err != nil {
		return err
	}
	socket := filepath.Join(root, "control.sock")
	if err := os.Remove(socket); err != nil && !os.IsNotExist(err) {
		return err
	}
	listener, err := net.Listen("unix", socket)
	if err != nil {
		return err
	}
	defer listener.Close()
	defer os.Remove(socket)
	os.Chmod(socket, 0600)
	m := newManager(root, bin, parent == 0)
	done := make(chan struct{})
	var connections sync.WaitGroup
	defer func() { close(done); connections.Wait() }()
	defer func() {
		m.opMu.Lock()
		defer m.opMu.Unlock()
		m.persist = false
		m.closing = true
		m.mu.Lock()
		ids := make([]string, 0, len(m.instances))
		for id := range m.instances {
			ids = append(ids, id)
		}
		m.mu.Unlock()
		m.stopMany(ids)
		m.notify()
	}()
	if m.persist {
		if err := m.restore(); err != nil {
			return err
		}
		connections.Add(1)
		go func() {
			defer connections.Done()
			m.watchNetworks(done)
		}()
	}
	if err := os.Remove(filepath.Join(root, "exit.json")); err != nil && !os.IsNotExist(err) {
		return err
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)
	defer signal.Stop(stop)
	go func() {
		select {
		case <-done:
			return
		case <-time.After(time.Second):
		}
		for {
			changed, _ := m.events()
			m.opMu.Lock()
			idle := !m.needsRun()
			if idle && m.save() != nil {
				idle = false
			}
			if idle {
				m.closing = true
				data, err := json.Marshal(m.status(0))
				if err == nil {
					err = atomicWrite(filepath.Join(root, "exit.json"), data, 0600)
				}
				if err != nil {
					fmt.Fprintln(os.Stderr, "STATUS_SAVE_FAILED")
				}
				listener.Close()
			}
			m.opMu.Unlock()
			if idle {
				return
			}
			select {
			case <-done:
				return
			case <-changed:
			}
		}
	}()
	go func() {
		if parent > 0 {
			ticker := time.NewTicker(2 * time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-done:
					return
				case <-stop:
					listener.Close()
					return
				case <-ticker.C:
					if os.Getppid() != parent {
						listener.Close()
						return
					}
				}
			}
		} else {
			select {
			case <-stop:
				listener.Close()
			case <-done:
			}
		}
	}()
	for {
		conn, err := listener.Accept()
		if err != nil {
			return nil
		}
		connections.Add(1)
		go func() {
			defer connections.Done()
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(30 * time.Second))
			var req request
			if err := json.NewDecoder(io.LimitReader(conn, maxInput+1)).Decode(&req); err != nil {
				json.NewEncoder(conn).Encode(response{Error: "invalid request"})
				return
			}
			if req.Action == "watch" {
				changed, revision := m.events()
				if req.Revision == revision {
					select {
					case <-changed:
					case <-done:
					case <-time.After(20 * time.Second):
					}
				}
				_, revision = m.events()
				json.NewEncoder(conn).Encode(response{Revision: revision})
				return
			}
			shutdown := req.Action == "shutdown"
			if shutdown {
				req.Action = "stopAll"
			}
			result := m.handle(req)
			json.NewEncoder(conn).Encode(result)
			if shutdown && result.Error == "" {
				listener.Close()
			}
		}()
	}
}

func call(root string, req request) (response, error) {
	conn, err := net.DialTimeout("unix", filepath.Join(root, "control.sock"), 2*time.Second)
	if err != nil {
		return response{}, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(25 * time.Second))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return response{}, err
	}
	var result response
	err = json.NewDecoder(io.LimitReader(conn, maxInput+1)).Decode(&result)
	return result, err
}

func run(args []string) error {
	if len(args) == 3 && args[0] == "verify" {
		var p profile
		if err := json.NewDecoder(io.LimitReader(os.Stdin, maxInput+1)).Decode(&p); err != nil {
			return err
		}
		if err := privateDir(args[1]); err != nil {
			return err
		}
		ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
		defer cancel()
		result := response{}
		if err := newManager(args[1], args[2], false).verify(ctx, p); err != nil {
			result.Error = err.Error()
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	}
	if len(args) == 4 && args[0] == "serve" {
		parent, err := strconv.Atoi(args[3])
		if err != nil {
			return err
		}
		return serve(args[1], args[2], parent)
	}
	if len(args) == 2 && args[0] == "request" {
		var req request
		if err := json.NewDecoder(io.LimitReader(os.Stdin, maxInput+1)).Decode(&req); err != nil {
			return err
		}
		var result response
		var err error
		if args[1] == moduleRoot && os.Geteuid() == 0 {
			result, err = moduleRequest(req)
		} else {
			result, err = call(args[1], req)
		}
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	}
	if len(args) >= 1 && args[0] == "module" {
		return module(args[1:])
	}
	return errors.New("invalid command")
}

func main() {
	if err := run(os.Args[1:]); err != nil {
		json.NewEncoder(os.Stdout).Encode(response{Error: err.Error()})
		os.Exit(1)
	}
}
