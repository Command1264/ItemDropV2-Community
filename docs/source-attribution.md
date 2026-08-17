# 掉落來源與認主範圍

最後更新：2026-08-12

ItemDropV2 只在能以公開 Bukkit/Paper event 安全辨識玩家貢獻者時認主。插件不掃描附近物品、
不依距離猜測 owner，也不清空後自行重生其他插件已修改的 drops。取消事件、自然 physics、
爆炸、活塞、dispenser、無玩家來源或 context 已過期時維持無 owner。

## 已支援來源

- 玩家破壞的一般方塊與 container contents。
- 仙人掌、甘蔗、竹子、海帶、藤蔓、大型垂滴葉與歌萊果等連鎖崩落。
- 鷹架、一般重力方塊、鐘乳石及支援版本的硫磺尖刺。
- 可追溯至玩家的 Projectile 擊破特定方塊或飾紋陶罐。
- 玩家直接傷害、玩家 Projectile、玩家馴服 Entity 的有效傷害貢獻。
- 釣魚、玩家剪取 Entity、船與礦車等載具及其 storage contents。
- 甜莓與洞穴藤蔓的玩家右鍵收成。

多方塊結構使用精確 world、座標、Material 與短效 bounded context 配對實際生成的 Item。這是
因為部分 server 版本不會為每個連鎖崩落方塊提供完整 drop event。Context 不會主動載入 chunk，
超過安全上限時整筆 fail closed，不建立部分認主。

## Entity 傷害策略

`items.ownership.entity.strategy` 支援：

- `highest-damage`：累積有效傷害最高者；並列時可有多位 owner。
- `first-hit`：第一位有效貢獻玩家。
- `final-hit`：最後一位有效貢獻玩家。

有效傷害不計入超過 Entity 當下生命值的 overkill。最低傷害百分比以死亡時最大生命值計算；
未達門檻便不認主。戰鬥紀錄有 timeout 與容量上限，server restart 後不保留。

## 安全邊界

Ownership 寫入由 plugin main-thread lifecycle 管理。等待寫入期間，必要時會短暫阻止目標 Item
先被合併而消失；成功、拒絕、最終失敗、逾時或 plugin disable 後都會解除。Blocked world
不寫入 ownership；PDC、UUID、Material 或事件資料不合法時不猜測補值。
