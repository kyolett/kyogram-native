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
| Rail people = recent DMs (railPeople.tsx) | done | `ui/KyogramRailView.java` |
| Server header (serverHeader.tsx) | done: folder name as the list's title | `ui/KyogramRailView.java` |
| User panel + status (userPanel.tsx, statusButton.tsx, presence.ts) | done: avatar + dot in the rail; online/idle/dnd/invisible wired into MessagesController, NotificationsController | `app/kyogram/ui/Presence.java` |
| Members panel (membersPanel.tsx) | done: chat ⋮ → Members (online first) | `ui/KyogramMembers.java` |
| Hidden chats + PIN (hidden.ts) | done: chat ⋮ → Hide chat; hold the Chats tab → Hidden chats | `app/kyogram/ui/Hidden.java`, `ui/KyogramHiddenActivity.java` |
| Auto-reply (autoReply.ts) | done: chat ⋮ → Auto-reply, messages | `app/kyogram/ui/AutoReply.java` |
| DroidShop lookup: purchases, invoice (droidshop.ts) | done: Care ⌕ | `KyogramCareActivity`, `Care.java` |
| New warranty ticket (sendDroidshopTicket) | done, with camera photos | same |
| Ticket ↔ Trello card (ticketTrello.ts) | done | same, `Trello.java` |
| Warranty → Trello card (createKyogramWarrantyCard) | done (+ report copied) | same |
| Zalo customer ↔ Trello (zaloOaTrello.ts) | done | `KyogramZaloView` |
| Card photos in task details | done | `KyogramTasksActivity` |
| Delete all wallpapers (wallpaperWipe.ts) | done: hold the Chats tab | `MainTabsActivity` |
| Drag chat to folder (dragToFolder.ts) | Telegram's own: hold a chat → Add to folder | |
| Report mark (reportMark.ts) | done: the warranty report is copied to paste | `KyogramCareActivity` |
| Web apps window (webApps.ts) | n/a on phone (opens in browser) | |
| Notifications with the app closed | done: Telegram's kept connection on by default (no Firebase needed) | `KyogramLook.keepConnected` |
