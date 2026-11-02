//go:build !windows

package core

import (
	"os"
	"strings"
)

// DetectCountry on non-Windows builds (tests, development) reads the region from $LANG, e.g. en_US.UTF-8.
func DetectCountry() string {
	lang := os.Getenv("LANG")
	if i := strings.IndexAny(lang, "_-"); i >= 0 && len(lang) >= i+3 {
		return strings.ToUpper(lang[i+1 : i+3])
	}
	return ""
}
