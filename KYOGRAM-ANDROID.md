# Kyogram for Android (vivo X Fold) — feature port tracker

Every desktop Kyogram feature (kyolett/kyogram, `src/kyogram/*`, `desktop/*`) and where it stands in this
native app (TMessagesProj_AppStandalone, app id `app.kyogram`). Build: `gh workflow run kyogram.yml -R
kyolett/kyogram-native --ref master -f app=standalone`.

| Feature (desktop file) | Android | Where |
| --- | --- | --- |
| Kyogram icon, name | done | `TMessagesProj_AppStandalone/src/main/res/mipmap-*` |
| Unfold frost (iPhone Duo) | done | `ui/Components/KyogramFoldReveal.java` |
| Liquid glass look (glass.ts, look.ts) | done: Kyogram Liquid theme, Telegram Liquid Glass + chat blur on, glass colours behind the chat list | `app/kyogram/ui/KyogramLook.java`, `DialogsActivity` |
| Shared glass / springs / pill | done | `app/kyogram/ui/{Style,Spring,GlassFrame,ChatListView,MenuView,SegmentView}` |
| Shift tasks (shiftTasks.tsx, trello.ts) | done | `ui/KyogramTasksActivity.java`, `app/kyogram/ui/Trello.java` |
| Care tickets (droidshop.ts) | done: list, search, filter, ticket page, status, notes | `ui/KyogramCareActivity.java`, `app/kyogram/ui/Care.java` |
| Kyogram x OA (zaloOa.ts, desktop/zaloOa.js) | done: sign-in, chats, messages, reply, polling | `ui/KyogramZaloView.java`, `app/kyogram/ui/ZaloOa.java` |
| Discord rail (look.ts, railPeople.tsx) | done: pinned chats + folders | `ui/KyogramRailView.java`, `LaunchActivity` |
| Rail people = recent DMs (railPeople.tsx) | todo | |
| Server header (serverHeader.tsx) | todo | |
| User panel + status (userPanel.tsx, statusButton.tsx, presence.ts) | todo | |
| Members panel (membersPanel.tsx) | todo | |
| Hidden chats + PIN (hidden.ts) | todo | |
| Auto-reply (autoReply.ts) | todo | |
| DroidShop lookup: purchases, invoice (droidshop.ts) | todo | |
| New warranty ticket (sendDroidshopTicket) | todo | |
| Ticket ↔ Trello card (ticketTrello.ts) | todo | |
| Warranty → Trello card (createKyogramWarrantyCard) | todo | |
| Zalo customer ↔ Trello (zaloOaTrello.ts) | todo | |
| Card photos in task details | todo | |
| Delete all wallpapers (wallpaperWipe.ts) | todo | |
| Drag chat to folder (dragToFolder.ts) | todo | |
| Report mark (reportMark.ts) | todo | |
| Web apps window (webApps.ts) | n/a on phone (opens in browser) | |
| Notifications with the app closed | todo: Firebase project | |
