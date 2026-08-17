# bStats 與隱私

最後更新：2026-08-12

ItemDropV2 使用 bStats Bukkit `3.2.1` 的匿名標準統計，service ID 為 `32797`。只有插件 backend、
設定、controller、command 與 task 全部成功啟用後才建立一個 metrics session；初始化失敗不會
停用 ItemDropV2，正常 disable 時會關閉 session。

ItemDropV2 不註冊 custom chart。bStats 標準資料可能包含匿名 server UUID、server software、
Minecraft/Bukkit version、online mode、玩家總數、Java／作業系統資訊、CPU core 數及插件版本。
ItemDropV2 不加入玩家 UUID／名稱、IP、物品、世界、指令、config、PDC 或 server path。

Server 管理員可在 `plugins/bStats/config.yml` 將 `enabled` 設為 `false`。ItemDropV2 不會移除、
覆寫或繞過 bStats 的全域 opt-out。Dependency 已 relocate 到 JAR 內部 namespace，不需要另外
安裝 bStats plugin。

官方資料見 [bStats](https://bstats.org/) 與
[bStats Metrics source](https://github.com/Bastian/bStats-Metrics)。
