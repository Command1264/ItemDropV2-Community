# 架構與 Community 邊界

最後更新：2026-08-12

ItemDropV2 採 Service、Repository、Model、View、Controller（SRMVC）分層：

- `core`：純 Kotlin domain model、ports 與 service，不依賴 Bukkit。
- `platform/bukkit-common`：event／command controller、YAML、PDC、檔案與 Bukkit adapter。
- `platform/view-bukkit`：使用 Bukkit API 的 server-side 名稱顯示。
- `platform/view-community`：Community 唯一 presentation provider。
- `capability/virtual-stacking-api`：virtual state 與 lifecycle contract。
- `capability/virtual-stacking-community`：Community 的 Legacy Drain provider。
- `plugin-bootstrap`：唯一 JavaPlugin entrypoint 與 dependency composition。
- `distribution/community`：組裝 Community JAR。

主要依賴方向是 `Controller → Service → Repository／View port`。Domain service 不直接操作
Bukkit；bootstrap 不承載業務規則。每個 Community JAR 恰好包含一個 presentation provider
及一個 virtual-stacking capability provider，零個或多個都視為 packaging error。

Community 的 Legacy Drain 是相容性安全層：它可以讀取既有 virtual amount 並讓原有 carrier
逐步消耗，但不建立 replacement child、不主動 merge、不自動 materialize。當跨 world 或
inventory 的 durable 結果不明時採 fail closed，不猜測補發或扣除數量。

公開來源刻意不包含非公開 edition 的 provider、distribution、Paper-only presentation 或
virtual stack creation implementation。`check` 會同時驗證來源路徑、架構 reference 與成品
constant pool，避免邊界只存在於文件。
