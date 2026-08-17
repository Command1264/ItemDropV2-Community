# Community 原始碼發布方式

最後更新：2026-08-12

本 repository 是由私有開發 mono-repository 單向產生的 Community source snapshot。私有
repository 是唯一開發來源；公開 repository 不包含其 Git history，也不是手動維護的第二份
權威 source tree。

匯出使用固定 allowlist，只包含 Community 可獨立建置所需的共用／Community modules、Gradle
Wrapper、法律文件與 Community canonical 文件。發布流程不採「複製全部再刪除」的 denylist，
以免新增 private path 時 fail open。

每個 snapshot 都包含：

- `SOURCE-PROVENANCE.md`：來源 commit 與 clean／dirty 狀態。
- `SOURCE-MANIFEST.sha256`：匯出檔案的 exact SHA-256 清單。
- 獨立的 `check`、Community artifact assembly 及 JAR isolation gate。

正式發布只接受已知且 clean 的來源 commit。公開 source 不得包含 private edition implementation、
private tests／bug history／release operation、測試 server、local absolute path 或第二份 source
snapshot。產品 JAR 也不封裝 publication template、manifest 或 provenance。

私有來源另維護 machine-readable 文件去向清冊。每一份私有根目錄及 `docs/` Markdown 必須恰好
符合一條規則：改寫成指定的 Community canonical 文件，或因 bug history、測試證據、內部計畫、
release operation、private workflow 等理由保留私有。新增未分類的文件會讓 private `check`
失敗；因此「完整文件集」代表每份文件都有可驗證去向，而不是把私有內容機械複製到公開 tree。

第一方來源使用 PolyForm Perimeter License `1.0.1`，屬 Source Available 而非 OSI Open Source。
目前外部貢獻政策見根目錄的 [CONTRIBUTING.md](../CONTRIBUTING.md)。
