# 掉落物顯示與語言

最後更新：2026-08-12

ItemDropV2 Community 使用 Bukkit 顯示 backend，在 server 端解析物品名稱，再把完成的文字名稱
套用到 Item Entity。`general.minecraft-language` 決定原版物品名稱使用的 Minecraft 語系；
`general.language` 則控制指令、警告與插件訊息，兩者互不取代。

Community 不提供依每位 client locale 個別翻譯的 Paper 顯示功能。不同語言的玩家在同一 server
會看見相同的 server-side 物品名稱。

## 名稱來源與優先順序

1. 合法的 ItemStack custom display name。
2. 已載入的 Minecraft 官方語言 catalog。
3. 穩定的 Material 英文 fallback，例如 `DIAMOND_SWORD` 顯示為 `Diamond Sword`。

空白、過長或含控制字元的 custom name 不會直接寫入 Entity；插件會採 fallback 並留下有界
診斷。Catalog 尚未取得、下載失敗或缺少 translation key 時，也會安全使用 Material fallback，
不會因此阻止 server 啟動。

## 名稱格式

`items.display-name-format.single` 用於單件物品，`items.display-name-format.multi` 用於多件物品。
兩者都必須含 `%item_display_name%`。常用內建 placeholder：

- `%amount%`：Entity 代表的實際數量。
- `%owner%`、`%owner_count%`、`%other_owner_count%`：目前顯示的擁有者與人數。
- `%protection_remaining%`：剩餘拾取保護時間。
- `%lifetime_elapsed%`、`%lifetime_remaining%`：已存在與剩餘壽命。

時間欄位依最高非零單位顯示；永久與未知狀態使用語言檔的對應文字。完整外部 placeholder 行為
見 [PlaceholderAPI](placeholderapi.md)。

## Refresh 行為

新物品生成、合法合併、chunk 載入、catalog 更新及 `/itemdrop reload` 後，已載入物品會透過
有界 queue 重新呈現。Chunk 已卸載、Entity 已消失或單筆更新失敗時會安全略過，不會在單一
tick 掃描所有世界。

`general.enabled: false` 只關閉名稱顯示與刷新；它不會關閉 ownership、pickup protection、
lifetime 或資料持久化。
