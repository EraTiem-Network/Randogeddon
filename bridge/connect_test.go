package main

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestFillConnectSetsTheSlotsNameAndPassword(t *testing.T) {
	frame := `[{"cmd":"Connect","game":"A Link to the Past","name":"whatever","password":"","uuid":"u1",` +
		`"version":{"major":0,"minor":6,"build":8,"class":"Version"},"items_handling":7,"tags":["AP"],"slot_data":true}]`
	out, changed := fillConnect([]byte(frame), "Alice-LttP", "s3cret")
	if !changed {
		t.Fatal("not changed")
	}
	var packets []map[string]any
	if err := json.Unmarshal(out, &packets); err != nil {
		t.Fatal(err)
	}
	p := packets[0]
	if p["name"] != "Alice-LttP" || p["password"] != "s3cret" {
		t.Fatalf("name %v, password %v", p["name"], p["password"])
	}
	// Everything else stays
	if p["game"] != "A Link to the Past" || p["items_handling"] != 7.0 || !reflect.DeepEqual(p["tags"], []any{"AP"}) {
		t.Fatalf("changed more: %s", out)
	}
}

func TestFillConnectReadsTheFrameAsTheServerDoes(t *testing.T) {
	// An escaped command is still a Connect; a second Connect in the same frame too
	frame := `[{"cmd":"Connect","name":"x"},{"cmd":"Say","text":"hi"},{"cmd":"Connect","name":"y","password":"z"}]`
	out, changed := fillConnect([]byte(frame), "Slot", "pw")
	if !changed {
		t.Fatal("not changed")
	}
	var packets []map[string]any
	_ = json.Unmarshal(out, &packets)
	if packets[0]["name"] != "Slot" || packets[2]["name"] != "Slot" || packets[2]["password"] != "pw" {
		t.Fatalf("%s", out)
	}
	if packets[1]["text"] != "hi" || packets[1]["name"] != nil {
		t.Fatalf("the Say changed: %s", out)
	}
}

func TestFillConnectLeavesOtherFramesAlone(t *testing.T) {
	for _, frame := range []string{
		`[{"cmd":"Sync"}]`,
		`[{"cmd":"Say","text":"Connect"}]`,
		`{"cmd":"Connect"}`, // not an array
		`not json`,
		`[{"cmd":"Connect"}] [{"cmd":"Connect"}]`, // two values
	} {
		out, changed := fillConnect([]byte(frame), "Slot", "pw")
		if changed || string(out) != frame {
			t.Errorf("%s became %s", frame, out)
		}
	}
}

func TestFillConnectKeepsNumbersAndCharacters(t *testing.T) {
	frame := `[{"cmd":"Connect","name":"x","big":12345678901234567890,"text":"<&>"}]`
	out, _ := fillConnect([]byte(frame), "Slot", "pw")
	if !strings.Contains(string(out), `"big":12345678901234567890`) || !strings.Contains(string(out), `"text":"<&>"`) {
		t.Fatalf("%s", out)
	}
}

func TestServerReply(t *testing.T) {
	if connected, _ := serverReply([]byte(`[{"cmd":"Connected","slot":1}]`)); !connected {
		t.Error("Connected not seen")
	}
	if _, refused := serverReply([]byte(`[{"cmd":"ConnectionRefused","errors":["InvalidPassword"]}]`)); !reflect.DeepEqual(refused, []string{"InvalidPassword"}) {
		t.Errorf("refused: %v", refused)
	}
	if connected, refused := serverReply([]byte(`[{"cmd":"PrintJSON"}]`)); connected || refused != nil {
		t.Error("something seen in a PrintJSON")
	}
}
