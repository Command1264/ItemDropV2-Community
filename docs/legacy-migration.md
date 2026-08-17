# 舊版設定與資料遷移

最後更新：2026-08-12

ItemDropV2 可辨識舊 `ItemDropV2` 小寫設定，以及更早 `ItemDrop` 的 `General`／`Item_Hologram`
設定。只有來源 schema 唯一、型別與值域都能安全驗證時才自動遷移；混合、未知或模糊結構會
保留原檔並安全拒絕。

## 設定檔流程

1. 唯讀解析來源並辨識 schema。
2. 在 plugin data directory 建立不覆寫既有檔案的 timestamp backup。
3. 將已知 key、enum、locale、placeholder 與 permission-related policy 轉成 schema `1`。
4. 以 temporary file 與 atomic replacement 提交候選檔，再重新解析驗證。
5. 成功後記錄來源、backup 檔名與被忽略的過時 key；失敗時維持舊執行中設定。

備份時間使用 server 電腦本地時間與 UTC offset，格式為
`yyyy-MM-dd-HH-mm-ss-UTC±HH-mm`。無法安全延續的 `items.async`、舊 updater、彩蛋 command 與
舊 thread-unsafe 行為不會復活。缺 key／註解、非法值與 YAML parse recovery 的現行規則見
[YAML 修復與備份](yaml-repair-and-backups.md)。

Command root `itemdrop`、主要 aliases、PlaceholderAPI soft dependency 與穩定 permission 範圍
會保留；CommandLib 不是 dependency。

## PDC 遷移

舊 namespace `itemdropv2` 的 `age`、`amount`、`owner` 與 `ownertime` 只在 type、UUID、range 及
欄位組合合法時讀取。合法舊 state 會升級為 current entity schema；損壞、partial 或未知新版
schema 不會被舊 reader 覆蓋。新的動態 state 只寫 Entity PDC，避免 ItemStack metadata 破壞
原生合併資格。

Spigot／Paper `1.14`、`1.14.1` 的磁碟恢復另由 SQLite journal 保護，見
[PDC 與舊版 Journal](data-and-persistence.md)。`1.13.2`（含）以下不在目前支援宣告內。
