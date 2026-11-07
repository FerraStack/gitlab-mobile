# Notifications

GitLab has no push service that third-party apps can subscribe to: there is no FCM integration and no per-device registration API. GitLab Mobile therefore polls each signed-in account itself and keeps per-account cursors, so an event is announced once even when checks are late or overlap.

## When the app checks

| Mode | How often | Limits |
| --- | --- | --- |
| App open | Every minute (skipped while instant notifications run) | Only while the app is visible |
| Background (default) | WorkManager periodic work, every 15 minutes at best | Doze, App Standby Buckets (Rare: up to 24 h), Battery Saver, Data Saver and manufacturers' battery managers can defer it for hours |
| Instant notifications (opt-in) | Every 60, 90 or 120 s with the screen on; 5 minutes with it off; 15 minutes in Doze | A silent ongoing notification while it runs; uses more battery |

Every check reads the account's to-dos and its assigned, review-requested and authored merge requests (`GitLabNotificationMonitor.poll`). The notification shows the event's own time.

## Instant notifications

Turn on under **Manage accounts → Notifications → Instant notifications**. A foreground service (`InstantNotificationService`) then runs while at least one account is signed in.

- **Light checks first.** Each round makes one request per account: `GET /api/v4/user_counts` with the last `ETag` as `If-None-Match`. A `304` or identical counts means nothing new. Servers without that endpoint get `GET /api/v4/todos?state=pending&per_page=1`, compared by its total and newest id. The full check runs only when something changed, and at least every 10 minutes, because a merged or closed merge request changes no count.
- **Backoff.** Errors double the interval up to 15 minutes. `429` waits at least as long as the server's `Retry-After` and skips the full check. An expired token goes to the full check, which refreshes OAuth tokens or reports the error in diagnostics.
- **Screen off and Doze.** A sleeping CPU doesn't run timers, so with the screen off the service sets an allow-while-idle alarm (Android fires these at most about every 9 minutes in Doze). A partial wake lock is held only for the duration of a check, at most 2 minutes.
- **Restarts.** The service restarts after a reboot (`BOOT_COMPLETED`) and an app update (`MY_PACKAGE_REPLACED`), and whenever the app is opened. If Android stops it and refuses a background restart (Android 12+), WorkManager keeps checking and diagnostics says the service isn't running.
- It stops itself when the last account signs out or the setting is turned off, including from the **Turn off** action on its notification.

## Battery helper

**Notifications → Battery** shows whether Android optimizes the app's battery use. **Allow** asks Android to stop (the system's own dialog, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Below that are steps for the phone maker's own battery manager (Xiaomi Autostart, Samsung Never sleeping apps, Huawei App launch and others) and a link to that maker's page on [dontkillmyapp.com](https://dontkillmyapp.com/). When instant notifications are turned on while the app is still optimized, a one-time card offers the same.

The direct request is controlled by `BuildConfig.REQUEST_BATTERY_EXEMPTION` (true in the GitHub build). A Play flavor sets it to false and removes the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission; the button then opens the system's battery optimization list instead.

## Diagnostics

**Notifications → Diagnostics** shows what holds notifications back on this phone: notification permission and categories, the App Standby Bucket, battery optimization, background restriction, Data Saver, Battery Saver, the next run WorkManager plans and the reason Android last stopped it, each account's last result, and a local log of recent checks (background, instant and in-app kept apart). **Check now** runs a check immediately.

## Google Play

The app ships through GitHub Releases, where a `specialUse` foreground service needs no review. A Google Play build would need changes:

- Play requires a declaration and justification for every foreground service type, and `specialUse` is reviewed case by case. Polling a server for notifications is the use case Play directs to Firebase Cloud Messaging, so expect the declaration to be rejected.
- `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is restricted on Play to apps whose core function needs it; a Play build should only link to the system battery settings.
- The Play-friendly design is push through FCM. GitLab can't send to FCM directly, so it needs a relay: a small service that receives GitLab webhooks or system hooks (or polls on the user's behalf) and sends FCM messages to registered devices. Self-hosted users would have to run or trust that relay, a different trust model from today's "tokens never leave the device".

Until then, a Play flavor should leave instant notifications out and rely on WorkManager and the diagnostics screen.
