package main

import (
	"errors"
	"os"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

func networkEvents() (*os.File, <-chan struct{}, error) {
	fd, err := unix.Socket(unix.AF_NETLINK, unix.SOCK_RAW|unix.SOCK_CLOEXEC|unix.SOCK_NONBLOCK, unix.NETLINK_ROUTE)
	if err != nil {
		return nil, nil, err
	}
	err = unix.Bind(fd, &unix.SockaddrNetlink{Family: unix.AF_NETLINK,
		Groups: unix.RTMGRP_LINK | unix.RTMGRP_IPV4_IFADDR | unix.RTMGRP_IPV6_IFADDR |
			unix.RTMGRP_IPV4_ROUTE | unix.RTMGRP_IPV6_ROUTE})
	if err != nil {
		unix.Close(fd)
		return nil, nil, err
	}
	file := os.NewFile(uintptr(fd), "network-events")
	changes := make(chan struct{}, 1)
	go func() {
		defer close(changes)
		buffer := make([]byte, 64<<10)
		for {
			if _, err := file.Read(buffer); err != nil && !errors.Is(err, unix.ENOBUFS) {
				return
			}
			select {
			case changes <- struct{}{}:
			default:
			}
		}
	}()
	return file, changes, nil
}

func (m *manager) watchNetworks(done <-chan struct{}) {
	var socket *os.File
	var changes <-chan struct{}
	var settled <-chan time.Time
	timer := time.NewTimer(time.Hour)
	timer.Stop()
	defer timer.Stop()
	defer func() {
		if socket != nil {
			socket.Close()
		}
	}()
	failed := false
	for {
		changed, _ := m.events()
		m.mu.Lock()
		enabled := false
		for _, item := range m.instances {
			enabled = enabled || (item.desired && item.profile.NetworkReconnect)
		}
		m.mu.Unlock()
		if enabled && socket == nil && !failed {
			var err error
			socket, changes, err = networkEvents()
			if err != nil {
				failed = true
				m.addLog(profile{Type: "frp"}, "error", "NETWORK_MONITOR_FAILED: "+err.Error())
			} else {
				// Cover a network restored while the supervisor was restarting.
				timer.Reset(5 * time.Second)
				settled = timer.C
			}
		} else if !enabled {
			if socket != nil {
				socket.Close()
				socket, changes = nil, nil
			}
			timer.Stop()
			settled, failed = nil, false
		}
		select {
		case <-done:
			return
		case <-changed:
		case _, open := <-changes:
			if !open {
				socket.Close()
				socket, changes, failed = nil, nil, true
				m.addLog(profile{Type: "frp"}, "error", "NETWORK_MONITOR_CLOSED")
			} else {
				timer.Reset(5 * time.Second)
				settled = timer.C
			}
		case <-settled:
			settled = nil
			m.recoverNetwork()
		}
	}
}

func (m *manager) recoverNetwork() {
	m.opMu.Lock()
	defer m.opMu.Unlock()
	if m.closing {
		return
	}
	m.mu.Lock()
	var pending []*instance
	for _, item := range m.instances {
		if item.desired && item.profile.NetworkReconnect && item.cmd == nil {
			pending = append(pending, item)
		}
	}
	m.mu.Unlock()
	for _, item := range pending {
		err := m.verify(item.profile)
		if err == nil {
			err = m.start(item.profile)
		}
		if err != nil {
			m.mu.Lock()
			item.state.State, item.state.Error = "failed", err.Error()
			item.desired = !strings.HasPrefix(err.Error(), "INVALID_TOML")
			m.mu.Unlock()
			m.addLog(item.profile, "error", "NETWORK_RECOVERY_FAILED")
		}
	}
	if len(pending) > 0 {
		if err := m.save(); err != nil {
			m.addLog(profile{Type: "frp"}, "error", "STATE_SAVE_FAILED")
		}
		m.notify()
	}
}
