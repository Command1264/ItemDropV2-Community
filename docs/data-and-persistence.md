# PDC 與舊版 Journal

最後更新：2026-08-12

ItemDropV2 使用 Entity Persistent Data Container（PDC）保存掉落物的 schema、revision、擁有者、
壽命及必要的顯示與 virtual amount 狀態。讀取時會驗證型別、範圍、UUID、revision、Material
fingerprint 與 schema；不合法或未知資料會 fail closed，不會猜測套用或無聲覆寫。

## Minecraft 版本邊界

- 官方 Spigot／Paper `1.14`、`1.14.1`：Entity PDC 從 world disk 重建時不可靠，使用
  `ENTITY_PDC_WITH_JOURNAL`。Entity loaded 期間仍以 PDC 為 runtime authority，SQLite journal
  提供 durable recovery。
- `1.14.2+`：使用原生 `ENTITY_PDC` recovery，預設不建立 journal。
- `1.13.2`（含）以下：目前 artifact 不宣稱支援；journal 本身不能解決 PDC API class linkage。
- 未知 fork 或能力不足：在 listener 註冊前安全拒絕，不猜測相容性。

這個安全層沒有可關閉的 config。受影響端點若 SQLite driver、schema 或 database 無法安全
初始化，插件不會帶著可能遺失 state 的功能繼續啟用。

## SQLite 儲存

Journal 位於 plugin data directory 的 `state-journal/`，每個 world 使用獨立的
`item-state.db`。Identity 由 world UUID 與 entity UUID 組成；位置只用於縮小掃描範圍，不會用
「附近物品」猜測身分。資料庫使用 foreign keys、WAL、FULL synchronous、bounded busy timeout
與單一 lifecycle-owned writer。

Journal 只恢復 world 中仍存在且 identity／fingerprint 相符的 Item Entity，不會重建已不存在的
物品，也不取代 world、player data 或 plugin data 備份。Plugin-controlled removal 會寫入較新
tombstone；單純長時間沒看見、world 未載入或 chunk 未載入不會觸發刪除。

需要人工檢查時，先完整停止 server，再以 SQLite CLI 或 DB Browser for SQLite 唯讀開啟
`item-state.db`。不要在 server 運行時修改資料庫、WAL 或 schema，也不要刪除不確定用途的 row。

## Crash 與限制

正常停服會等待 bounded flush。突然終止只能恢復最後一筆完成 durable commit 的 record；Minecraft
world save、玩家 inventory 與 SQLite 不共享 transaction manager，因此不宣稱跨檔案
exactly-once 或 ACID。I/O failure、queue saturation、corrupt database 或 revision conflict 會進入
degraded／隔離狀態並留下 bounded 診斷，不會自動展開 virtual carrier 或猜測補發數量。
