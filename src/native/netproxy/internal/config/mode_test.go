package config

import (
	"slices"
	"testing"
)

func TestModesFollowNativeRulesAndDefault(t *testing.T) {
	for _, test := range []struct {
		content   string
		mode      string
		available []string
	}{
		{`{}`, "Rule", []string{"Rule"}},
		{`{"experimental":{"clash_api":{"default_mode":"Office"}},"route":{"rules":[{"clash_mode":["Direct","Proxy"]},{"type":"logical","rules":[{"clash_mode":"Office"},{"clash_mode":"Zeta"}]}]},"dns":{"rules":[{"type":"logical","rules":[{"clash_mode":["RuleAllowAds","Office"]}]}]}}`, "Office", []string{"Office", "Proxy", "RuleAllowAds", "Zeta", "Direct"}},
		{`{"experimental":{"clash_api":{"default_mode":""}},"dns":{"rules":[{"clash_mode":"Direct"}]}}`, "Rule", []string{"Rule", "Direct"}},
	} {
		result, err := ParseModes([]byte(test.content))
		if err != nil || result.Mode != test.mode || !slices.Equal(result.Available, test.available) {
			t.Fatalf("%s: %+v, %v", test.content, result, err)
		}
	}
}

func TestModesRejectMalformedJSON(t *testing.T) {
	for _, content := range []string{`null`, `[]`, `{"route":{"rules":[{"clash_mode":123}]}}`, `{"dns":{"rules":[{"clash_mode":[1]}]}}`, `{"route":{"rules":[{"type":"unsupported"}]}}`, `{"experimental":{"clash_api":{"default_mode":1}}}`, `{"route":{},"route":{}}`, "{\"extra\":\"\xff\"}"} {
		if _, err := ParseModes([]byte(content)); err == nil {
			t.Fatalf("接受了非法配置: %s", content)
		}
	}
}

func TestParseClashController(t *testing.T) {
	for content, want := range map[string]string{
		`{}`:                                "",
		`{"experimental":{"clash_api":{}}}`: "",
		`{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9999"}}}`:            "127.0.0.1:9999",
		`{"experimental":{"clash_api":{"external_controller":"0.0.0.0:9999","secret":"x"}}}`: "127.0.0.1:9999",
		`{"experimental":{"clash_api":{"external_controller":"[::]:9999"}}}`:                 "[::1]:9999",
	} {
		got, err := ParseClashController([]byte(content))
		if err != nil || got != want {
			t.Fatalf("%s: %q, %v", content, got, err)
		}
	}
}
