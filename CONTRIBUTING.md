# Contributing to MoonClicker / 貢獻指南

感謝您對 MoonClicker 的關注與支持！本專案歡迎所有 Bug 回報、新功能建議與 Pull Request。
為了確保程式碼品質與專案穩定性，請在貢獻前閱讀以下開發與協作規範。

---

## 🌿 分支策略 (Branching Strategy)

- **`master`**：主分支，隨時保持可編譯、測試通過與可發布狀態。
- **Feature / Bugfix 分支**：
  - 新功能：`feature/<feature-name>` 或 `feat/<feature-name>`
  - 錯誤修復：`fix/<bug-name>`
  - 重構與雜項：`refactor/<name>`、`chore/<name>`

所有重大更動皆需透過 Pull Request 合併至 `master`，禁止直接 push 至 `master`。

---

## 📝 Commit Message 規範 (Conventional Commits)

提交訊息請遵循 [Conventional Commits](https://www.conventionalcommits.org/) 規範，格式如下：

```
<type>(<scope>): <short summary>

[optional body]
```

### 常用類型 (Types)：
- **`feat`**：新增功能（例如：`feat(workbench): add websocket authentication`）
- **`fix`**：修復錯誤（例如：`fix(engine): resolve memory leak in vision matcher`）
- **`refactor`**：重構程式碼（既不修復錯誤也不新增功能）
- **`docs`**：文件修改（例如：`docs: update lua-api.md`）
- **`test`**：新增或修改測試案例
- **`chore`**：建置流程、相依性更新或輔助工具變更

---

## 🧪 本地測試與驗證 (Local Verification)

在發起 PR 前，請務必在本地完成各模組的單元測試與驗證：

### 1. Android 模組測試
```bash
./gradlew test
```

### 2. VS Code 擴充套件測試
```bash
cd vscode-extension
npm ci
npm run generate
npm test
```

---

## 🚀 發起 Pull Request (PR Workflow)

1. **Fork 並建立專用分支**：從最新 `master` 切出您的分支。
2. **實作並維持小步提交**：保持 commit 粒度清晰、意圖明確。
3. **通過本地測試**：確保 `./gradlew test` 與 `npm test` 均為綠燈。
4. **提交 PR**：
   - 標題清楚簡潔，並在說明中描述修改動機與具體變更。
   - 若關聯特定 Issue，請在描述中加上 `Fixes #<issue_number>`。
5. **等待 CI 驗證與 Code Review**：PR CI 會自動驗證 Android 與 VS Code 單元測試。

---

## 💬 交流與討論

- 若有使用問題或想法交流，歡迎前往 [GitHub Discussions](https://github.com/kuomartin/ReLC/discussions) 發帖。
- 若發現特定錯誤，請使用 [Bug Report Template](https://github.com/kuomartin/ReLC/issues/new/choose) 提出 Issue。
