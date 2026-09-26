# 요청 흐름 · `84a7d6a`

```mermaid
flowchart LR
  subgraph LEGEND["범례"]
    legend_added["노드 추가"]:::added
    legend_changed["노드 변경"]:::changed
  end
  subgraph F_com_minky_discordbot_HelpListener_onSlashCommandInteraction_1["HelpListener.onSlashCommandInteraction"]
    com_minky_discordbot_HelpListener_onSlashCommandInteraction_1["HelpListener.onSlashCommandInteraction"]:::added
  end
  subgraph ENTRY["진입"]
    com_minky_discordbot_Main_main_1["Main.main"]:::changed
  end
  subgraph INTERNAL["내부"]
    com_minky_discordbot_EnkephalinListener_restore_0["EnkephalinListener.restore"]
    com_minky_discordbot_IdentityCatalog_hasGameFiles_0["IdentityCatalog.hasGameFiles"]
    com_minky_discordbot_IdentityCatalog_start_0["IdentityCatalog.start"]
    com_minky_discordbot_MaintenanceAlert_restore_0["MaintenanceAlert.restore"]
    com_minky_discordbot_NoticeListener_cut_2["NoticeListener.cut"]
    com_minky_discordbot_NoticeListener_start_0["NoticeListener.start"]
  end
    com_minky_discordbot_HelpListener_onSlashCommandInteraction_1 --> com_minky_discordbot_NoticeListener_cut_2
    com_minky_discordbot_Main_main_1 --> com_minky_discordbot_EnkephalinListener_restore_0
    com_minky_discordbot_Main_main_1 --> com_minky_discordbot_IdentityCatalog_hasGameFiles_0
    com_minky_discordbot_Main_main_1 --> com_minky_discordbot_IdentityCatalog_start_0
    com_minky_discordbot_Main_main_1 --> com_minky_discordbot_MaintenanceAlert_restore_0
    com_minky_discordbot_Main_main_1 --> com_minky_discordbot_NoticeListener_start_0
  linkStyle 0 stroke:#2a2,stroke-width:2px
  classDef added fill:#dfd,stroke:#2a2
  classDef removed fill:#fdd,stroke:#d33,stroke-dasharray:4
  classDef changed fill:#fe8,stroke:#c90
```

변경과 무관한 노드 39개 생략

| 구분 | 대상 |
|---|---|
| 기능 추가 | HelpListener.onSlashCommandInteraction |
| 변경 | Main.main |
| 호출 추가 | HelpListener.onSlashCommandInteraction → NoticeListener.cut |
