# 掉落物合併

最後更新：2026-08-12

未受 ItemDropV2 追蹤的 Item 保留 server 原生合併。已追蹤物品只有在 state 相容時才允許合併：

- 兩件物品都沒有 owner。
- 兩件物品的 primary owner 與完整 eligible owner 清單完全相同。

只有一件有 owner、owner 清單不同、資料損壞或 schema 未知時會拒絕合併。這些本來就不相容的
嘗試不會產生一般警告；真正的 storage、transaction 或資料錯誤仍會留下有界診斷。

合法合併在 server main thread 以受控 transaction 完成。每次最多搬移至 Material 原生
`maxStackSize`；source 未搬完時保留 remainder 及其原 state。Target 寫入失敗時會還原原生
stack 數量並保留 source，不會只更新畫面而遺失 ownership 或 lifetime。

## Ownership protection strategy

`items.merge.ownership-strategy` 支援：

- `average`：兩者剩餘保護秒數向上取整平均，預設值。
- `maximum`：使用較大的剩餘秒數。
- `minimum`：使用較小的剩餘秒數。
- `reset`：使用 reload 後目前有效的 `items.ownership.protection-seconds`；若為 `0`，合併後
  清除 ownership。

Reload 只影響之後發生的 transaction，不回頭改寫既有 Item。Lifetime 的三種策略見
[掉落物壽命](item-lifetime.md)。

## Community legacy carrier

Community 不建立 virtual stack，也不讓既有 virtual carrier 重新參與 virtual merge。由其他
合法 edition 留下的 carrier 會保持單一 Entity，透過 bounded pickup 逐步消耗，直到拾取完成或
自然消失；不會在降級或關閉設定時展開成大量 Entity。
