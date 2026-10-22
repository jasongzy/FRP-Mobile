package main

import (
	"os/exec"
	"reflect"
	"slices"
	"strings"
	"testing"

	v1 "github.com/fatedier/frp/pkg/config/v1"
)

func TestUpstreamFields(t *testing.T) {
	source, err := exec.Command("go", "list", "-m", "-f", "{{.Dir}}", "github.com/fatedier/frp").Output()
	if err != nil {
		t.Fatal(err)
	}
	c := readCatalog(strings.TrimSpace(string(source)))
	client := c.fields(reflect.TypeFor[v1.ClientConfig]())
	server := c.fields(reflect.TypeFor[v1.ServerConfig]())
	for path, kind := range map[string]string{
		"serverAddr": "string", "serverPort": "number", "loginFailExit": "boolean",
		"transport.tls.trustedCaFile": "string", "proxies": "array-table",
		"proxies.transport.bandwidthLimit": "string", "proxies.plugin.localAddr": "string",
		"visitors.plugin.destinationIP": "string", "metadatas": "table",
	} {
		if client[path].Kind != kind {
			t.Errorf("%s: got %+v, want %s", path, client[path], kind)
		}
	}
	for path, value := range map[string]string{
		"proxies.type": "tcp", "visitors.type": "stcp", "proxies.plugin.type": "http_proxy", "auth.method": "oidc",
	} {
		if !slices.Contains(client[path].Values, value) {
			t.Errorf("%s: missing %s", path, value)
		}
	}
	if server["bindPort"].Kind != "number" || server["allowPorts"].Kind != "array-table" {
		t.Fatalf("Missing server fields: %+v", server)
	}
	if _, ok := server["serverAddr"]; ok {
		t.Fatal("Client field leaked into server schema")
	}
}
