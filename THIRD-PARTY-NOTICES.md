# Third-Party Notices

ItemDropV2 Community 的第一方原始碼使用 PolyForm Perimeter License 1.0.1。下列第三方軟體
維持其各自授權；本清單不把 Community 授權套用至第三方作品。

| Dependency | 用途 | 授權／來源 |
| --- | --- | --- |
| Kotlin standard library `2.4.10` | relocated runtime | Apache License 2.0；<https://github.com/JetBrains/kotlin> |
| Gson `2.14.0` | JSON 處理，relocated runtime | Apache License 2.0；<https://github.com/google/gson> |
| bStats Bukkit `3.2.1` | 匿名 metrics，relocated runtime | MIT License；<https://github.com/Bastian/bStats> |
| SQLite JDBC `3.27.2.1` | 舊 Spigot journal durability | Apache License 2.0；<https://github.com/xerial/sqlite-jdbc> |
| Spigot API `1.14.4-R0.1-SNAPSHOT` | compile-only server interface | 由 SpigotMC 發布；不封裝於成品 |
| PlaceholderAPI `2.12.3` | compile-only optional integration | 由 PlaceholderAPI 發布；不封裝於成品 |

Gradle、Detekt、Spotless、ktlint 與 JUnit 只用於建置／測試，不會作為 ItemDropV2 Community
runtime class 封裝。正式發布前仍須以 resolved dependency 與 JAR 內容重新執行第三方授權稽核；
若 dependency 或封裝內容改變，本清單必須同步更新。
