# 掉落物壽命

最後更新：2026-08-12

掉落物壽命規則位於 `plugins/ItemDropV2/item-lifetime.yml`，目前使用 schema `1`：

```yaml
schema-version: 1
default-seconds: 300
materials:
  NETHER_STAR: 600
  COBBLESTONE: 60
  DIAMOND: -1
  ROTTEN_FLESH: 0
```

- `-1`：不因 ItemDropV2 lifetime 到期而消失。
- `0`：首次註冊時在 server main thread 立即移除。
- `1` 至 `Long.MAX_VALUE`：只在 server 運行且物品所在 chunk 已載入時累計秒數。
- Material key 不分大小寫，載入後正規化為大寫；Material override 優先於全域預設。

為維持同一 JAR 跨版本使用，語法合法但目前 server 尚不存在的 Material 會保留，只是不在該
runtime 匹配。非法 Material、正規化後重複、錯誤 schema 或小於 `-1` 的數值不會被猜測採用。
修復與備份契約見 `yaml-repair-and-backups.md`。

Embedded default 包含 `NETHER_STAR: 600`，維持原版 Nether Star 的十分鐘壽命。管理員明確設定
的值優先，不會被 repair 覆蓋。

## Reload 與凍結

Reload 後的新規則只套用於尚無明確 lifetime 的首次註冊物品；已保存 original／elapsed state 的
既有物品保持原進度。Chunk unload 時從 processing wheel 移除，重新載入才繼續；server offline
與 chunk unloaded 期間不扣秒。每次秒級更新會寫回 PDC；強制終止後能恢復到哪一秒仍取決於
Minecraft 最後完成的 world save，以及舊版端點的 journal durable state。

火、岩漿、仙人掌、虛空、指令移除、拾取與合併等原版 removal path 不屬於 lifetime clock；插件
不會因自訂 lifetime 而復活或補回這些物品。Material 原生免疫也不會被移除。

## 合併策略

`items.merge.lifetime-strategy` 控制兩個 Item Entity 合併後的 elapsed state：

- `average`：overflow-safe 平均，`.5` 向上取整，預設值。
- `maximum`：選擇兩者中較小的 elapsed；只有 original lifetime 相同時，才等同保留較長
  剩餘時間。
- `minimum`：選擇兩者中較大的 elapsed；只有 original lifetime 相同時，才等同保留較短
  剩餘時間。

兩個 Entity 等權計算，不依 stack amount 或 virtual amount 加權；source remainder 保留自己的
original／elapsed state。策略只更新 target elapsed，不改 target original lifetime。
