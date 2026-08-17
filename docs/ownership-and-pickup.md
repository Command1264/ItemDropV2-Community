# 擁有權與拾取保護

最後更新：2026-08-12

ItemDropV2 只為可辨識且符合設定的玩家來源建立 ownership。擁有權可包含一位 primary owner
及有順序的 eligible owner 清單；顯示名稱可輪播多人 owner，但實際拾取資格不以當下顯示的
名字判定。

## 玩家拾取

1. `general.blocked-worlds` 中的世界維持原版行為。
2. 沒有 ItemDropV2 state 的一般物品維持原版行為。
3. 玩家必須具備 `itemdrop.event.pickup`。
4. 無 owner 或玩家位於 eligible owner 清單時允許拾取。
5. `itemdrop.event.pickup.other` 可繞過其他玩家仍有效的保護。
6. 其他玩家、非玩家 Entity 與預設 inventory 不得取得仍受保護的物品。

Owner protection 到期後會清除 ownership，物品恢復無 owner 的拾取與合併規則。`general.enabled`
只控制名稱顯示，不會停用本保護。

## Inventory 與 Creative

`items.ownership.allow-hopper-pickup: true` 只允許漏斗類 inventory 取得受保護物品，不會授予
玩家或其他 Entity 額外權限。

`items.ownership.creative-no-capacity-pickup` 只在 Creative storage 完全沒有容量時生效：

- `destroy`：依 Creative 語意消耗該物品。
- `deny`：不修改 inventory、Entity 或 PDC。

只要背包仍有空格，或存在相似且未滿的 stack，就使用一般 partial pickup，不套用此選項。
舊 key `creative-full-inventory-pickup` 會先備份再遷移；新 key 無效時 fail closed，不會退回讀取
舊值。

## 拒絕提示

拒絕提示可選 `action-bar` 或 `chat`，並由 `pickup-warning-cooldown-seconds` 控制重複頻率。
非玩家 Entity 與 inventory 被拒絕時不傳送玩家訊息。損壞、未知 schema 或資料讀取失敗時，
當次拾取會安全拒絕並留下有界診斷，避免 ownership 被無聲繞過。
