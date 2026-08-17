# 設定檔

最後更新：2026-08-14

主要設定位於 `plugins/ItemDropV2/config.yml`。插件會驗證 schema、型別、範圍與已知 enum；缺少
的受管理 key／註解可由內建 template 補回。需要改寫時會先備份並以失敗保護方式替換；無法
安全解析或修復時，啟動會安全停用，`/itemdrop reload` 則維持舊的執行中設定。

完整的修復、備份與排版規則見 `yaml-repair-and-backups.md`；逐 Material lifetime 另見
`item-lifetime.md`。名稱、稀有度、ownership、pickup 與 merge 的行為說明分別見
`display-and-language.md`、`rarity.md`、`ownership-and-pickup.md` 與 `merging.md`。

## Root keys

- `schema-version`：設定格式版本；未知版本不會被猜測或覆寫。
- `general`：顯示開關、插件語系、Minecraft server-side 語系與排除世界。
- `items`：處理預算、顯示、合併、擁有權、拾取與名稱格式。

## 常用設定

| Key | 預設 | 說明 |
| --- | --- | --- |
| `general.enabled` | `true` | 是否顯示及刷新 ItemDropV2 掉落物名稱。 |
| `general.language` | 地區決定 | 指令與提示語系；TW／CN 預設 `zh_tw`，其他地區預設 `en_us`。 |
| `general.minecraft-language` | 地區決定 | Bukkit server-side 翻譯語系；與首次建立的插件語系採相同預設。 |
| `general.blocked-worlds` | 範例清單 | 精確比對；列入的世界不套用 ItemDropV2。 |
| `items.processing.maximum-items-per-tick` | `256` | 每 tick 的 Item 處理相位容量。 |
| `items.rarity-display.enabled` | `true` | 依原生 rarity 顯示顏色；自訂名稱顏色優先。 |
| `items.merge.lifetime-strategy` | `average` | 合併壽命：`average`、`maximum`、`minimum`。 |
| `items.merge.ownership-strategy` | `average` | 合併保護：前三種策略或 `reset`。 |
| `items.ownership.enabled` | `true` | 記錄合資格掉落物的擁有者。 |
| `items.ownership.protection-seconds` | `30` | 擁有者拾取保護秒數；`0` 表示不保護。 |
| `items.ownership.allow-hopper-pickup` | `false` | 是否允許漏斗搬運仍受保護的物品。 |
| `items.ownership.creative-no-capacity-pickup` | `destroy` | Creative 無容量時使用 `destroy` 或 `deny`。 |
| `items.display-name-format.single` | template | 單件掉落物名稱格式。 |
| `items.display-name-format.multi` | template | 多件掉落物名稱格式。 |

`items.ownership.entity.strategy` 可用 `highest-damage`、`first-hit`、`final-hit`；最低傷害百分比與
combat timeout 由同一節設定。拒絕拾取提示可選 `action-bar` 或 `chat`。

## 首次建立的註解語系

第一次建立設定時只讀取 server 電腦的 country code：`TW`／`CN` 使用繁體中文註解，其他、
空白或未知值使用英文註解。這項判定不使用 IP、GeoIP或玩家位置，並同時套用至
`config.yml`與`item-lifetime.yml`。兩種 template的key、順序及非語系值相同。

既有 `config.yml` 不會因作業系統地區改變而被整份改寫；後續缺key修復以目前
`general.language`選擇註解，`zh_tw`使用繁中，`en_us`與自訂locale使用英文。插件語系與
Minecraft語言ID皆不分大小寫。內建插件語系為`en_us`、`zh_tw`；Minecraft ID依server版本而異，
可參考 [Minecraft Wiki Language](https://minecraft.wiki/w/Language) 或
[中文語言清單](https://zh.minecraft.wiki/w/%E8%AF%AD%E8%A8%80)。

## Community 與 virtual stacking

Community 首次建立與缺key修復所用的config template不包含`items.virtual-stacking`，避免顯示
無法在Community啟用的Pro-only功能。若data directory先前由Pro使用，既有
`items.virtual-stacking` subtree仍會原樣保留，但Community不解析、不修復也不套用其中的值。

Community 不建立新的 virtual carrier，也不讓既有 carrier 重新參與 virtual merge。既有
carrier會維持單一Item Entity，透過bounded pickup逐步消耗，直到拾取完成或自然消失；降級時
不會突然展開成大量Entity。手動加入或保留`items.virtual-stacking.enabled: true`也不會解鎖
virtual stack creation。

## 顯示 placeholder

內建 placeholder 包含 `%item_display_name%`、`%amount%`、`%owner%`、`%owner_count%`、
`%other_owner_count%`、`%additional_owner_count%`、`%protection_remaining%`、
`%lifetime_elapsed%`、`%lifetime_remaining%`。外部 token 的格式與失敗語意見
`placeholderapi.md`。
