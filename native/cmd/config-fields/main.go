package main

import (
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"reflect"
	"runtime/debug"
	"slices"
	"strconv"
	"strings"

	v1 "github.com/fatedier/frp/pkg/config/v1"
)

type field struct {
	Kind   string   `json:"kind"`
	Values []string `json:"values,omitempty"`
}

type catalog struct {
	constants  map[string]string
	enums      map[string][]string
	registries map[string][]string
}

func readCatalog(source string) catalog {
	c := catalog{map[string]string{}, map[string][]string{}, map[string][]string{}}
	files, err := filepath.Glob(filepath.Join(source, "pkg", "config", "v1", "*.go"))
	check(err)
	for _, path := range files {
		if strings.HasSuffix(path, "_test.go") {
			continue
		}
		file, err := parser.ParseFile(token.NewFileSet(), path, nil, 0)
		check(err)
		for _, decl := range file.Decls {
			group, ok := decl.(*ast.GenDecl)
			if !ok {
				continue
			}
			for _, spec := range group.Specs {
				value, ok := spec.(*ast.ValueSpec)
				if !ok {
					continue
				}
				for i, name := range value.Names {
					if i >= len(value.Values) {
						continue
					}
					switch expr := value.Values[i].(type) {
					case *ast.BasicLit:
						if group.Tok != token.CONST || expr.Kind != token.STRING {
							continue
						}
						literal, err := strconv.Unquote(expr.Value)
						check(err)
						c.constants[name.Name] = literal
						if typ, ok := value.Type.(*ast.Ident); ok {
							c.enums[typ.Name] = append(c.enums[typ.Name], literal)
						}
					case *ast.CompositeLit:
						if !strings.HasSuffix(name.Name, "TypeMap") {
							continue
						}
						for _, element := range expr.Elts {
							entry, ok := element.(*ast.KeyValueExpr)
							if !ok {
								panic("Unsupported frp registry: " + name.Name)
							}
							key, ok := entry.Key.(*ast.Ident)
							if !ok {
								panic("Unsupported frp registry key: " + name.Name)
							}
							c.registries[name.Name] = append(c.registries[name.Name], key.Name)
						}
					}
				}
			}
		}
	}
	return c
}

var registries = map[reflect.Type]string{
	reflect.TypeFor[v1.TypedProxyConfig]():          "proxyConfigTypeMap",
	reflect.TypeFor[v1.TypedVisitorConfig]():        "visitorConfigTypeMap",
	reflect.TypeFor[v1.TypedClientPluginOptions]():  "clientPluginOptionsTypeMap",
	reflect.TypeFor[v1.TypedVisitorPluginOptions](): "visitorPluginOptionsTypeMap",
}

func (c catalog) fields(root reflect.Type) map[string]field {
	result := map[string]field{}
	var walk func(reflect.Type, string)
	walk = func(t reflect.Type, path string) {
		for t.Kind() == reflect.Pointer {
			t = t.Elem()
		}
		if registry, ok := registries[t]; ok {
			keys := c.registries[registry]
			if len(keys) == 0 {
				panic("Missing frp registry: " + registry)
			}
			var values []string
			for _, key := range keys {
				name, ok := c.constants[key]
				if !ok {
					panic("Missing frp type constant: " + key)
				}
				value := reflect.New(t)
				data, err := json.Marshal(map[string]string{"type": name})
				check(err)
				check(json.Unmarshal(data, value.Interface()))
				found := false
				for i := 0; i < t.NumField(); i++ {
					item := value.Elem().Field(i)
					if item.Kind() == reflect.Interface && !item.IsNil() {
						walk(item.Elem().Type(), path)
						found = true
					}
				}
				if !found {
					panic("Missing frp config variant: " + name)
				}
				values = append(values, name)
			}
			result[path+".type"] = field{"string", values}
			return
		}
		kind := ""
		switch t.Kind() {
		case reflect.Struct:
			// Custom scalar types such as BandwidthQuantity serialize as strings.
			if value, ok := reflect.New(t).Interface().(json.Marshaler); ok {
				data, err := value.MarshalJSON()
				check(err)
				if len(data) > 0 && data[0] == '"' {
					kind = "string"
					break
				}
			}
			kind = "table"
			for i := 0; i < t.NumField(); i++ {
				f := t.Field(i)
				if !f.IsExported() {
					continue
				}
				name := strings.Split(f.Tag.Get("json"), ",")[0]
				if name == "-" {
					continue
				}
				if f.Anonymous && name == "" {
					walk(f.Type, path)
					continue
				}
				if name == "" {
					panic("Missing frp JSON field name: " + f.Name)
				}
				walk(f.Type, strings.TrimPrefix(path+"."+name, "."))
			}
		case reflect.Slice, reflect.Array:
			kind = "array"
			if t.Elem().Kind() == reflect.Struct {
				walk(t.Elem(), path)
				kind = "array-table"
			}
		case reflect.Map:
			kind = "table"
		case reflect.String:
			kind = "string"
		case reflect.Bool:
			kind = "boolean"
		case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
			reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Float32, reflect.Float64:
			kind = "number"
		default:
			panic(fmt.Sprintf("Unsupported frp field: %s (%s)", path, t))
		}
		if path != "" {
			result[path] = field{kind, c.enums[t.Name()]}
		}
	}
	// shortcut: variants share field suggestions; filter by type if this becomes distracting.
	walk(root, "")
	for path, item := range result {
		slices.Sort(item.Values)
		item.Values = slices.Compact(item.Values)
		result[path] = item
	}
	return result
}

func check(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	if len(os.Args) != 2 {
		panic("Usage: config-fields <frp source directory>")
	}
	info, ok := debug.ReadBuildInfo()
	if !ok {
		panic("Missing Go build information")
	}
	version := ""
	for _, dep := range info.Deps {
		if dep.Path == "github.com/fatedier/frp" {
			version = strings.TrimPrefix(dep.Version, "v")
		}
	}
	if version == "" {
		panic("Missing frp module version")
	}
	c := readCatalog(os.Args[1])
	encoder := json.NewEncoder(os.Stdout)
	check(encoder.Encode(map[string]any{
		"frpVersion": version,
		"frpc":       c.fields(reflect.TypeFor[v1.ClientConfig]()),
		"frps":       c.fields(reflect.TypeFor[v1.ServerConfig]()),
	}))
}
