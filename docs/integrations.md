# 第三方整合狀態

最後更新：2026-08-12

## 已提供

PlaceholderAPI 是目前正式的 optional integration。未安裝時 ItemDropV2 仍可啟用；已安裝時
只有合法 token 才交給 PlaceholderAPI。完整契約見 [PlaceholderAPI](placeholderapi.md)。

bStats 是匿名 metrics dependency，不是另一個 server plugin，也不提供遊戲行為整合。隱私與
opt-out 見 [bStats 與隱私](metrics-and-privacy.md)。

## 尚未宣告支援

目前沒有 production adapter、dependency、config 或相容性宣告可支援 mcMMO、Residence、
Timber、WorldGuard、Lands、Towny 或 GriefPrevention。安裝這些插件時，ItemDropV2 只依 Bukkit
實際送出的標準 event 運作；不得把研究中的候選行為視為已完成整合。

未來整合必須由公開、同步且能精確識別 Player、來源與真實 Item 的 API／event 驅動。以下方式
不會被接受：依附近玩家猜 owner、反射第三方 private queue、在 async callback 操作 Bukkit
物件，或只因 plugin 顯示名稱相同就啟用 adapter。

第三方插件若自行大量破壞方塊但不送出足夠的公開來源資訊，相關物品可能維持無 owner；這比
錯誤認主或重複產生物品更安全。正式支援必須另有版本 fingerprint、failure isolation、contract
tests 與真實 server matrix。
