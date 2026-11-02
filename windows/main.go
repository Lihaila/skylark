package main

import (
	"embed"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/windows"
)

//go:embed all:frontend
var assets embed.FS

func main() {
	app := NewApp()
	err := wails.Run(&options.App{
		Title:            "Skylark",
		Width:            420,
		Height:           720,
		MinWidth:         380,
		MinHeight:        600,
		AssetServer:      &assetserver.Options{Assets: assets},
		BackgroundColour: &options.RGBA{R: 247, G: 248, B: 252, A: 255},
		OnStartup:        app.startup,
		OnShutdown:       app.shutdown,
		// Closing the window while connected only minimises it, so the VPN keeps running.
		OnBeforeClose: app.beforeClose,
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId:               "com.lihaila.skylark",
			OnSecondInstanceLaunch: app.secondInstance,
		},
		Bind: []interface{}{app},
		Windows: &windows.Options{
			Theme: windows.SystemDefault,
		},
	})
	if err != nil {
		println("Error:", err.Error())
	}
}
