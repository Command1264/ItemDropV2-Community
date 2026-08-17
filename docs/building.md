# 建置與驗證

最後更新：2026-08-12

## 需求

- JDK 25 用於執行 Gradle；Community JAR 仍輸出 Java 8 bytecode。
- 使用 repository 內的 Gradle Wrapper 9.6.1。
- 不需要 CraftBukkit、NMS、server JAR 或 BuildTools source。

Windows：

```powershell
.\gradlew.bat check
.\gradlew.bat assembleItemDropCommunity
```

Linux／macOS：

```bash
./gradlew check
./gradlew assembleItemDropCommunity
```

成品位於 `build/distributions/ItemDropV2-Community-<version>.jar`。

## `check` 的涵蓋範圍

`check` 會執行 Kotlin formatting、Detekt、所有 Community module tests、公開文件連結與內容
隔離、來源模組邊界及 JAR inspection。JAR 必須維持 Java 8 class major、恰好一個 Community
View provider 與一個 Legacy Drain capability provider，也不得包含 Paper-only 或非公開 edition
的 class／constant-pool reference。

Gradle task 成功不等於所有 Minecraft 版本都已完成真實 server 驗證。版本支援與代表矩陣見
`minecraft-compatibility.md`。
