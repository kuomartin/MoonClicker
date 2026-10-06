# 使用 VS Code 腳本工作台遠端開發

## 安裝擴充套件

1. 從 [Releases](https://github.com/kuomartin/MoonClicker/releases) 下載最新的 `moonclicker-script-workbench.vsix`。
2. 在 VS Code 執行 **Extensions: Install from VSIX...** 指令，選擇下載的檔案完成安裝。

## 連線裝置

1. 在 App 的 `設定` → `關於` 連按版本號 7 下開啟開發人員選項，再到 `開發人員選項` 開啟 `腳本工作台`，並開啟配對模式。
2. 點擊側邊欄 **MoonClicker Scripts** -> **Connect to Device**：VS Code 會透過 mDNS 自動搜尋同網段內的裝置，選取即可配對；找不到裝置時也可以手動輸入裝置 IP 與 PIN 碼。

## 連線後可以做什麼

* **F5 執行、Shift+F5 停止**：在 `main.lua` 編輯器內按 F5 於裝置上執行目前的腳本並即時查看日誌，Shift+F5 停止。
* **Workbench 面板**：點狀態列的連線狀態或側邊欄標題列的按鈕開啟。包含低延遲 H.264 即時畫面、框選模板、測試模板與 OCR；預設開啟上次看的顯示器，可在面板內切換，實體螢幕尚未啟動鏡像時面板內會顯示啟動按鈕。
* **LuaLS 型別補全**：自動掛載型別提示 stub，撰寫腳本時有語法提示與檢查。

## 排除檔案

在 Script Folder 根目錄放一個 `.moonclickerignore`，列在裡面的檔案在存檔、新增、改名、刪除時都不會同步到裝置。語法與 `.gitignore` 相同，例如：

```gitignore
# 原始素材
*.psd
drafts/
!drafts/keep.png
```

`.git/`、`.vscode/`、`.luarc.json` 與 `.moonclickerignore` 本身預設就會排除，可以用 `!` 否定。

排除只作用在從 VS Code 推上裝置的方向：裝置上的檔案照樣會同步回 VS Code。已經在裝置上的檔案，之後才加進排除規則時不會從裝置刪除，需要的話請在 App 或檔案管理員手動刪除。
