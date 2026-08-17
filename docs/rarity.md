# 物品稀有度顏色

最後更新：2026-08-12

`items.rarity-display.enabled` 預設為 `true`。啟用時，掉落物名稱依 Minecraft rarity 顯示：

| Rarity | 顏色 |
| --- | --- |
| `COMMON` | 白色 |
| `UNCOMMON` | 黃色 |
| `RARE` | 淺藍色 |
| `EPIC` | 淺紫色 |

顏色優先順序為：custom display name 的明確顏色、ItemStack rarity、名稱 template 顏色。因此
已設定綠色的自訂名稱不會被 rarity 覆蓋；只有粗體、斜體或 reset 並不算明確顏色。

Community 使用同一個 Java 8 JAR 支援多個 Minecraft 版本。當 runtime 有安全的公開 API 時會
優先讀取 ItemStack rarity；舊版或缺少對等 API 的 server 則使用版本化 Material catalog。
Enchanted item 會依原版規則提升 rarity。未知或尚未驗證的新 Material 安全視為 `COMMON`，
不會猜測顏色。

修改開關後執行 `/itemdrop reload`，已載入物品會隨 refresh queue 更新。舊 key
`items.item-name-rarity-display` 可遷移至 `items.rarity-display.enabled`；新舊 key 同時存在時
以新 key 為準。
