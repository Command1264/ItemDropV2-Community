# ItemDropV2 Community Agent 規則

本 repository 是 ItemDropV2 Community 的 Source Available 發布來源。開始工作前先讀取
`README.md`、`docs/README.md` 與任務相關文件，執行 `git status`，並保留既有未提交變更。

## 範圍與授權

- 第一方來源依 `LICENSE` 的 PolyForm Perimeter License 1.0.1 提供；這不是 OSI Open Source。
- 公開內容只涵蓋 Community。不得加入、還原或推測非公開 edition 的實作。
- Community 使用 Bukkit View，且只以 Legacy Drain 安全讀取及逐步消耗既有 virtual carrier；
  不建立新的 virtual stack、不持續合併，也不自動展開成大量 Item Entity。
- 目前不接受包含程式碼或文件版權的 Pull Request；提交方式見 `CONTRIBUTING.md`。

## 工程規則

- First-party JVM source 只使用 Kotlin；不得新增 `.java`、NMS、CraftBukkit 或 Paper-only API。
- Gradle Wrapper 是唯一建置入口。建置使用 JDK 25，產物維持 Java 8 bytecode。
- 維持 SRMVC 依賴方向：Controller 呼叫 Service，Service 只依賴 domain Model 與 Repository／View
  port；Bukkit、YAML、PDC 與檔案實作留在 platform module，bootstrap 只組裝依賴。
- Bukkit 的 World、Entity、Inventory、event 與 scheduler 操作只在 server main thread 執行。
- 驗證所有 command、YAML、PDC、版本字串與檔案路徑；明確處理錯誤，不吞 exception。
- 不提交 secret、server JAR、玩家資料、診斷報告、build output 或本機路徑。
- 行為、相容性、設定或建置方式改變時，同一變更更新對應的公開文件。

## 驗證與完成條件

優先以 RED → GREEN → REFACTOR 修改行為。提交前至少執行：

```text
./gradlew check
./gradlew assembleItemDropCommunity
```

Windows 可改用 `gradlew.bat`。`check` 必須通過 formatting、Detekt、module tests、文件連結與
公開邊界檢查；Community JAR 必須維持 Java 8、唯一 Community View／capability provider，且不含
非公開 implementation reference。詳細建置方式見 `docs/building.md`，架構邊界見
`docs/architecture.md`。
