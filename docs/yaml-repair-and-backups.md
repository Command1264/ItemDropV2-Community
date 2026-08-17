# YAML 修復與備份

最後更新：2026-08-14

ItemDropV2 管理的 `config.yml`、`item-lifetime.yml` 與 `languages/*.yml` 共用保留註解的 YAML
修復框架。目標是補回已知 key、註解及可安全判定的非法值，同時保留管理員註解、未知欄位、
順序、BOM 及一致的 LF／CRLF newline。

## 可以自動處理

- 缺檔：由 embedded raw template 建立。
- 已知 schema 缺少 key／subtree：依 template 補回值與其註解。
- 已核准的舊 key：移至 canonical key，保留可歸屬的註解。
- `item-lifetime.yml` 的非法 `default-seconds`：回復 embedded default。
- 合法且唯一的 Material key 若數值非法：使用同一份文件已驗證的 `default-seconds`。
- Malformed YAML：先建立 create-new recovery backup，再用 raw template 重建。

只補缺少 key 時不建立 `.bak`；非法值、malformed YAML 或 legacy migration 需要保留原始資料時會
建立不覆寫既有檔案的備份。候選文件必須先寫入同目錄 temporary file、重新驗證，再以 atomic
replacement 或同檔案系統的失敗保護替換。

## Fail closed

未知 schema、duplicate path、大小寫正規化後重複的 Material、非法 Material 名稱、混合 newline、
unsupported YAML construct 或無法唯一對應的錯誤都不會被猜測修復。啟動期間會安全停用；
`/itemdrop reload` 會保留原 runtime settings，並向 command sender 說明原因。

修復通知只包含 sanitized 檔名、logical path、備份檔名與操作建議，不輸出整份設定或絕對路徑。

缺檔或缺key所使用的raw template同時提供繁中與英文。首次建立由server電腦country code選擇；
既有設定則由`general.language`選擇repair註解語系，避免更換電腦地區後意外替換管理員內容。

## 排版

受管理 mapping 使用兩個空格縮排；同一 parent 的 sibling key 之間保留一個空白行，parent 與第一個
child 之間不插入空白行。Sequence 與 block scalar 內容不因排版 policy 改寫。有效且不需修復的
reload 不會只為重新排版而寫回檔案；重複修復應維持 byte-idempotent。
