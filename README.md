# ItemDropV2 Community

ItemDropV2 Community 是支援 Spigot／Paper `1.14+` 的 Minecraft 掉落物管理插件。Community
提供掉落物名稱、數量、稀有度、擁有權、拾取保護、來源判定、合併、壽命、PDC、世界排除、
PlaceholderAPI 與管理指令，並使用 Bukkit server-side language catalog 顯示物品名稱。

## 下載與平台頁面

- [GitHub Release](https://github.com/Command1264/ItemDropV2-Community/releases/tag/v1.0.0)：Community `1.0.0` 的正式下載與公開原始碼。
- [Spigot](https://www.spigotmc.org/resources/itemdropv2-community.138160/)：Community 的 Spigot 上傳／發布頁面。
- [PaperMC Hangar](https://hangar.papermc.io/Command1/ItemDropV2)：Community 的 Hangar 上傳／發布頁面。

## Source Available 授權

本 repository 公開原始碼供閱讀、稽核、學習、允許用途的修改與散布，但它不是 OSI 定義的
Open Source software。第一方 Community 原始碼使用
[PolyForm Perimeter License 1.0.1](LICENSE)：不得利用本軟體向他人提供與 ItemDropV2 競爭的
產品，即使競爭產品免費、改用其他程式語言、平台或介面亦同。

一般 Minecraft 伺服器安裝、營運、接受贊助或販售伺服器內服務，不會僅因此成為 ItemDropV2
的競爭產品。授權原文仍是最終依據；若預定重新發布、整合進其他產品或提供衍生服務，請先
自行取得適當的法律意見。

第三方 dependency 與其各自授權不受 PolyForm Perimeter 變更，詳見
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。名稱與標誌使用規則見
[TRADEMARKS.md](TRADEMARKS.md)。

使用、建置、設定、相容性與架構文件由[公開文件索引](docs/README.md)開始；安全問題請依
[安全政策](SECURITY.md)私密回報。

公開 snapshot 的來源、manifest 與隔離方式見
[Community 原始碼發布方式](docs/source-publication.md)。

## Community 公開來源邊界

這是由私有開發 mono-repository 透過 allowlist 產生的 Community-only source publication。
公開內容包含 Community 可獨立建置所需的共用 domain、Bukkit adapter、Bukkit View、
bootstrap、Legacy Drain compatibility provider 與 Community distribution。

Community 為了安全讀取既有資料，會保留 virtual carrier schema 與 bounded drain 等相容契約；
這些契約不會建立新的 virtual stack。公開來源不包含 Paper client-side translation backend、
virtual stack creation／持續合併／scheduler implementation、付費 edition provider 或其正式
distribution。每次匯出都會以來源及 JAR 隔離 gate 阻止這些 implementation 進入公開內容。

## 建置需求

- Gradle Wrapper `9.6.1`；請使用 repository 內的 Wrapper。
- 建置 JDK `25`。
- 產物維持 Java 8 bytecode；實際 server Java 版本依 Minecraft server 而定。
- 不需要、也不得把 CraftBukkit、NMS、server JAR 或 BuildTools source 放入 repository。

Windows：

~~~powershell
.\gradlew.bat check
.\gradlew.bat assembleItemDropCommunity
~~~

Linux／macOS：

~~~bash
./gradlew check
./gradlew assembleItemDropCommunity
~~~

成品位於 `build/distributions/ItemDropV2-Community-<version>.jar`。`check` 會同時驗證 Kotlin
格式、Detekt、unit／contract tests、Java 8 class major、唯一 Community providers，以及私有
edition／Paper-only class 與 reference 沒有洩漏。

## 貢獻狀態

目前公開 repository 先作為可稽核、可自行建置的來源發布，不接受含程式碼或文件版權的
Pull Request。原因是 Community 與非公開 edition 共用部分第一方程式碼，正式接受外部貢獻前
必須先發布清楚的 Contributor License Agreement。問題回報與不含第三方私有資料的重現步驟
仍可透過 repository 的 Issue workflow 提交。詳見 [CONTRIBUTING.md](CONTRIBUTING.md)。
