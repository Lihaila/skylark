package core

import (
	"syscall"
	"unsafe"
)

var procGetUserDefaultGeoName = syscall.NewLazyDLL("kernel32.dll").NewProc("GetUserDefaultGeoName")

// DetectCountry returns the Windows "Country or region" setting as an ISO code ("" if unknown).
func DetectCountry() string {
	if procGetUserDefaultGeoName.Find() != nil {
		return ""
	}
	buf := make([]uint16, 16)
	n, _, _ := procGetUserDefaultGeoName.Call(uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
	if n == 0 {
		return ""
	}
	code := syscall.UTF16ToString(buf)
	if len(code) != 2 {
		return "" // numeric UN M.49 regions like "001" (World)
	}
	return code
}
