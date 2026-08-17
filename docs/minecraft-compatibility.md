# Minecraft 相容性

最後更新：2026-08-12

ItemDropV2 Community 的主要目標範圍是官方 Spigot／Paper `1.14` 至 `26.2`。同一個版本區間
不代表每個 patch、fork 與未來版本都自動獲得已驗證狀態；未知 fork 或不合法版本會安全停用。

Community 在所有支援端點固定使用 Bukkit View 與 server-side Minecraft language catalog，
不包含 Paper client-side translation backend。JAR 維持 Java 8 bytecode，但 server 的 Java
runtime 必須符合該 Minecraft／server build 自身的要求；例如現代端點可能需要 Java 17、21
或 25。

## 重要舊版邊界

- Minecraft `1.14`／`1.14.1` 的 Entity PDC 在卸載重載後可能遺失，因此這兩版使用 SQLite
  journal 相容層；`1.14.2+` 使用原生 Entity PDC recovery。詳細資料邊界見
  `data-and-persistence.md`。
- Armor Stand equipment 的舊版修補只適用於 `1.14`／`1.14.1`；`1.14.2+` 不套用。
- 舊版 Villager 的原生拾取路徑不一定提供可取消的 `EntityPickupItemEvent`，因此不能在所有
  舊 server build 上保證插件能攔截 Villager 拾取；玩家拾取保護不受此限制。
- `1.13.2`（含）以下不是目前主要支援範圍，未經實機驗證不得宣稱相容。

安裝前請保留 world 與 plugin data 備份，並在與正式環境相同的 server implementation、
Minecraft patch、Java 版本及其他插件組合上驗證。
