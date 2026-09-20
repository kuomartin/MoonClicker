# 使用 VS Code Workbench 遠端開發

## 安裝擴充套件

1. 從 [Releases](https://github.com/kuomartin/ReLC/releases) 下載最新的 `moonclicker-script-workbench.vsix`。
2. 在 VS Code 執行 **Extensions: Install from VSIX...** 指令，選擇下載的檔案完成安裝。

## 連線裝置

1. 在 App `設定` 頁面開啟 `Script Workbench`，並開啟配對模式。
2. 點擊側邊欄 **MoonClicker Scripts** -> **Connect to Device**：VS Code 會透過 mDNS 自動搜尋同網段內的裝置，選取即可配對；找不到裝置時也可以手動輸入裝置 IP 與 PIN 碼。

## 連線後可以做什麼

* **F5 一鍵 Push & Run**：直接把目前腳本推送到裝置並執行，即時查看日誌輸出。
* **Realtime Mirror**：低延遲 H.264 即時螢幕鏡射，方便邊看畫面邊除錯或裁切模板座標。
* **LuaLS 型別補全**：自動掛載型別提示 stub，撰寫腳本時有語法提示與檢查。
