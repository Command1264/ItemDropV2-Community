# 指令與權限

最後更新：2026-08-12

Root command 是 `/itemdrop`，aliases 為 `/idrop`、`/drop`、`/itemdrops`、`/idrops`、`/drops`。
玩家與 console 都可執行；tab completion 會依 sender 的權限過濾。

| 指令 | 權限 | 說明 |
| --- | --- | --- |
| `/itemdrop help` | `itemdrop.commands.help` | 只列出 sender 可使用的子指令。 |
| `/itemdrop info`、`/itemdrop about` | `itemdrop.commands.info` | 顯示版本、edition、server、backend、persistence、Virtual Stacking 與語系摘要。 |
| `/itemdrop reload` | `itemdrop.commands.reload` | 完整驗證設定；成功後才替換執行中設定並刷新已載入物品。 |
| `/itemdrop toggle` | `itemdrop.commands.toggle` | 反轉 `general.enabled`。 |
| `/itemdrop toggle on|off` | `itemdrop.commands.toggle` | 明確指定狀態，參數不分大小寫。 |

所有子指令另需 `itemdrop.commands.basic`。`basic`、`help`、`info` 預設允許所有玩家；`reload`
與 `toggle` 預設只允許 operator。

拾取權限：

- `itemdrop.event.pickup`：允許一般拾取，預設所有玩家。
- `itemdrop.event.pickup.other`：繞過其他擁有者的保護，預設只允許 operator。
- `itemdrop.event.pickup.*`、`itemdrop.event.*`、`itemdrop.*`：對應的父權限。

非法子指令、非法 `toggle` 值、多餘參數或權限不足都不應改變設定。`reload` 若遇到無法安全
修復的 YAML，會保留目前執行中設定並向 command sender 說明原因。

所有管理指令回覆 player 或 console command sender 的一般訊息行，都以當前語系檔的
`command.prefix` 開頭。`info`／`about` 的 header／footer 例外把同一個 prefix 嵌入
置中分隔線，不在行首重複疊加。自訂 prefix 可在 reload 後即時生效；插件
啟動、`WARN` 與 `ERROR` log 不重複疊加此指令 prefix。

`info` 與 `about` 使用相同的九行資訊版面：header、version、edition、server
platform 與 Minecraft version、backend、persistence mode、Virtual Stacking runtime mode、
plugin language 與 Minecraft language、footer。Header／footer 來自 `command.info-header`／
`command.info-footer`，並以 `%prefix%` 重用包含 runtime plugin name 的 `command.prefix`；
欄位標籤、顏色與排版均來自語系 YAML。
既有檔案缺少新 key 時，會由 comment-preserving repair 補齊而不覆寫自訂值。
資訊版面不顯示絕對路徑、UUID 或 database 路徑。
Server 與 provider 等 runtime 欄位在展開 placeholder 前會轉為 bounded single-line
plain text，不讓換行、控制字元或顏色碼製造額外輸出行。
