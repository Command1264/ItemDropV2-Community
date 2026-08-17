# PlaceholderAPI

最後更新：2026-08-12

PlaceholderAPI 是 optional soft dependency。未安裝時 ItemDropV2 Community 仍可正常啟用、顯示
與停用；外部 placeholder 會原樣保留。Community 不內嵌 PlaceholderAPI runtime，也不註冊自己
的 expansion。

外部 placeholder 可用於 `items.display-name-format.single`、`multi` 及 ownership display
prefix。ItemDropV2 會先展開內建 token，再把完整文字交給 PlaceholderAPI，避免
`%player_name%` 等名稱與內建 owner token 互相搶先解析。

外部 token 必須符合 `%identifier_parameters%`，identifier 與 parameters 都不可為空。若
PlaceholderAPI 呼叫或 linkage 失敗，插件會保留原文字並產生 bounded warning，不會中止掉落物
處理流程。

目前編譯契約固定使用 PlaceholderAPI `2.12.3`。`plugin.yml` 只把它列為 soft dependency，
Community JAR 不得包含 `me.clip.placeholderapi` 的 class definitions。
