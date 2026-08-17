# 診斷與疑難排解

最後更新：2026-08-12

## 啟用診斷報告

ItemDropV2 的 first-party `WARN`／`ERROR` 會經統一診斷 gateway。需要本機報告時，在 server
JVM 啟動參數加入：

```text
-Ditemdropv2.diagnostic-reports=true
```

報告位於：

- `plugins/ItemDropV2/reports/warn/`
- `plugins/ItemDropV2/reports/error/`

WARN 最多保留 100 份，ERROR 最多保留 50 份。檔名使用 server 所在電腦的本地時間與 UTC
offset，精確到秒；相同秒內以序號區分。Console 會把原因與報告相對路徑分行輸出，每行保留
ItemDropV2 prefix。

## 回報前

1. 記錄 ItemDropV2 版本與 Git commit、server implementation／build、Minecraft 與 Java 版本。
2. 使用最少的其他插件重現，並確認設定已由 `/itemdrop reload` 成功載入。
3. 移除 token、IP、玩家 UUID／名稱、world seed、絕對路徑及其他敏感資料。
4. 一般錯誤依 `../CONTRIBUTING.md` 回報；安全問題依 `../SECURITY.md` 私密回報。

診斷報告可能包含 sanitized exception、設定欄位名稱與 bounded platform context，但不應包含
完整設定內容或玩家資料。分享前仍應由管理員人工檢查。
