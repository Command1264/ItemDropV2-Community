# Minecraft 語言 Catalog

最後更新：2026-08-12

Community 以 `general.minecraft-language` 指定一個 server-wide Minecraft 語系，例如 `en_us`
或 `zh_tw`。自訂 display name 永遠優先；未命名物品才查詢語言 catalog。這不是每位玩家各自的
client locale 翻譯，完整顯示順序見[掉落物顯示與語言](display-and-language.md)。

## 官方資源與 cache

非 `en_us` 語系會從 Mojang version manifest 找到精確 server 版本的 asset index，再下載
content-addressed language object。Metadata URL 只接受 Mojang allowlist 中的 HTTPS host；
asset hash、宣告大小、實際大小與 2 MiB 上限都必須通過後才解析。

`en_us` 優先讀取 server classpath 的官方語言資源。若 catalog 不存在、網路失敗、hash 不符或
內容無效，插件保留最後一份合法 cache；沒有合法 cache 時使用 Material 英文 fallback。

Cache 位於 `plugins/ItemDropV2/minecraft-languages/`，檔名包含精確 Minecraft version 與 locale，
不會跨版本混用。寫入採同目錄 temporary file 與 atomic replacement。

## Lifecycle

網路、cache I/O 與 JSON parsing 在 plugin-owned async task 執行，不接觸 Bukkit Entity 或 World。
完成後才切回 main thread publish immutable catalog，並以有界 queue 刷新 loaded Item。Plugin
disable 會取消下載並阻止晚到結果繼續 publish 或修改 Entity。

Catalog 只保留合法的 `item.minecraft.*` 與 `block.minecraft.*` 文字 entry，並限制數量、key、
value 長度與控制字元。無效外部資料不會覆寫 config 或阻止 Material fallback。
