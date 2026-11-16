# UI work brief

This file is the shared contract for everyone building screens. Read it fully before writing code.

## Project

Personal multi-account mail client for one Galaxy phone. Kotlin, Jetpack Compose, Material 3 Expressive (material3 1.5.0-rc01), Material You dynamic color, Pretendard font. Package `app.dustbird.mail`. Modules: `:core` (JVM, protocol and rules, already tested) and `:app` (Android). Every account uses IMAP and SMTP. One user. All screen text is Korean and lives in string resources.

The data layer, sync, send, storage and notifications are DONE. You build screens on top of them. Do not edit anything outside your owned files (listed in your section). If an API you need is missing, work around it using public APIs (the `AppContainer` exposes `db` DAOs) and describe the gap in your final report. Never edit these shared files: `ui/nav/*`, `ui/theme/*`, `ui/AppLocals.kt`, `data/*`, `sync/*`, `core/*`, `MainActivity.kt`, `AppContainer.kt`, build files, `gradle/libs.versions.toml`, `res/values/strings.xml`, `AndroidManifest.xml`.

## Hard rules from the project owner

- No comments in code of any kind (no KDoc, no explanatory, no section, no commented-out code). Exceptions: license headers and framework-required directives.
- Identifiers, file names in English. Screen text, error messages in natural Korean (honorific polite style, not translationese). Strings go into your own resource file `res/values/strings_<feature>.xml` (names prefixed by your feature). Never hardcode Korean in Kotlin.
- No emoji anywhere (code, strings, resources, docs, commit messages).
- Never put a real name, e-mail address, account name or personal data in code, tests, docs or examples. Use `user@example.com` style values.
- Commits: small, message describes only the work. No mention of tools, assistants, generation, and no `Co-Authored-By` line.
- Do not add dependencies, fonts or libraries. Available: Compose BOM 2026.09.00 (ui, foundation), material3 1.5.0-rc01, material-icons-core, material3 adaptive (adaptive, adaptive-layout, adaptive-navigation 1.3.0), navigation-compose, lifecycle viewmodel/runtime compose, coroutines, WorkManager, DataStore, Room. If you believe another dependency is needed, do not add it; say so in the report.
- Follow the Ponytail principle (use the ponytail skill if available): the smallest complete change, no speculative abstractions, no unused code, no wrapper-for-wrapper layers. Trivial code needs no test. New non-trivial logic gets a test or a self-check.
- Do not call real external servers and never send real mail. Tests use an in-memory Room database and fake data only.

## Design direction (chosen, do not drift)

Editorial and utilitarian: restrained, content-first, fast to scan. Reading mail is the job.

- List rows are plain rows separated by thin dividers, NOT cards. No nested cards. No card around every block.
- Hierarchy through type weight and size using Pretendard (variable weight 100..900 via `FontWeight`). Unread sender names are heavy (about 700), read ones regular (about 450). Use `MaterialTheme.typography` styles, including the Emphasized variants where it helps.
- Color comes from the dynamic scheme (`MaterialTheme.colorScheme`). One accent only (primary). Account and identity distinction: always a text label, plus an accent from `accountAccent(index)` in `ui/theme/AccountColors.kt` (container and content colors). Never color alone.
- Use Material 3 Expressive on purpose, not as decoration: shape morphing or emphasized motion on meaningful state changes (selecting, sending, completing), `ButtonGroup` / `ToggleButton` / `SplitButton` for real choices, `LoadingIndicator` for waiting, `FloatingToolbar` or `FlexibleBottomAppBar` where actions cluster. Rounded corners alone are not Expressive. Use `MaterialTheme.motionScheme` for animation specs. All expressive APIs need `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- Forbidden: purple, indigo, violet or blurple gradients, any gradient text, glassmorphism, glows or neon, heavy soft shadows, a left colored border stripe on rows or callouts, pastel icon tiles, 3-up feature card grids, badge or pill spam, stat rows, numbered 01/02/03 markers, kicker text above every heading, monospace for ordinary text (monospace only for literal technical values such as server host names), Inter/Roboto/Open Sans/Arial/system-ui/Space Grotesk (the theme already uses Pretendard; never set another family).
- Touch targets at least 48dp. Text in `sp` only, never fixed pixel text, layouts must survive 200 percent font scale (use `Modifier.heightIn(min = ...)`, wrap or ellipsize, never clip). Every interactive element has a meaningful `contentDescription` or text. State is never conveyed by color alone. Do not run decorative infinite animations. Respect reduced motion: when `Settings.Global.ANIMATOR_DURATION_SCALE` is 0, skip non-essential animation.
- When there is only one account, hide account-distinguishing UI (account chips, account sections) to keep the screen simple. Identity (received address) labels still show.
- Do not use service logos or trademark images anywhere. Service names are plain text.
- Layout is adaptive: phone portrait, large phone, and unfolded foldable (use `currentWindowAdaptiveInfo()` and window size classes; the mailbox screen uses list-detail on expanded width).

## How to wire a screen

- The entry composables already exist as stubs in `ui/<feature>/`. Keep their exact public signatures; replace the bodies. `ui/nav/MailApp.kt` already calls them.
- Obtain data with `val container = LocalAppContainer.current` and a ViewModel: `appViewModel { container -> MyViewModel(container) }` (see `ui/AppLocals.kt`). Collect flows with `collectAsStateWithLifecycle()`.
- Compose previews may use fake data inline (previews and tests only).

## Data API you can use

`AppContainer` (all public): `accounts: AccountRepository`, `mail: MailRepository`, `drafts: DraftRepository`, `send: SendService`, `sync: SyncService`, `settings: SettingsRepository`, `credentials: CredentialStore`, `db: MailDatabase`, `connector: ImapConnector`, `smtpSender: SmtpSender`, `imapAccess`, `scope: CoroutineScope` (app lifetime, use it for work that must outlive a screen).

`AccountRepository` (`data/repo/AccountRepository.kt`):
- flows: `accounts: Flow<List<MailAccount>>`, `identities: Flow<List<MailIdentity>>`, `accountsWithIdentities: Flow<List<AccountWithIdentities>>`, `appDefaultIdentityId: Flow<Long?>`
- `suspend addAccount(NewAccount, secret, stages): AddAccountResult` (Added(accountId) or Rejected(Rejection)); creates the first identity from the login address automatically
- `suspend updateAccount(accountId, displayName, label, username, imap, smtp, syncMode, notificationsEnabled): Rejection?`
- `suspend reauthenticate(accountId, secret, stages)`, `markNeedsReauth`, `updateStages`, `removeAccount(accountId, force): RemoveAccountResult` (Removed or Blocked(unsettledMessages, drafts); ask the user, then call again with force = true), `reorderAccounts(orderedIds)`
- identities: `addIdentity(accountId, IdentityInput)`, `updateIdentity(identityId, IdentityInput)` -> `IdentityResult` (Saved or Rejected(Rejection)); `removeIdentity(identityId): RuleResult<IdentityRemoval>` (drafts of that identity move to the fallback identity; tell the user how many); `reorderIdentities`, `setAccountDefaultIdentity`, `setAppDefaultIdentity`, `setVerification`
- `Rejection`: INVALID_ADDRESS, DUPLICATE_ADDRESS, DUPLICATE_LABEL, BLANK_LABEL, LAST_IDENTITY, UNKNOWN_IDENTITY, DUPLICATE_MAILBOX. Map each to a clear Korean message.

Core models (`core/model/Model.kt`): `MailAccount` (id, providerId, displayName, label, username, imap, smtp, authMethod, sortOrder, syncMode, notificationsEnabled, needsReauth, stages: ConnectionStages), `MailIdentity` (id, accountId, address, displayName, signature, label, sortOrder, isAccountDefault, verification: UNVERIFIED/VERIFIED/FAILED), `ServerEndpoint(host, port, security: TLS|STARTTLS)`, `ConnectionStages(imapAuth, smtpAuth, send, receive, sentCopy)` each `StageState` UNKNOWN/OK/FAILED, `SyncMode` MANUAL/PERIODIC/IDLE, `FolderRole`, `EmailAddress(address, name)`.

Providers (`core/provider/ProviderProfile.kt`): `Providers.ICLOUD`, `GMAIL`, `NAVER`, `CUSTOM`, `Providers.all`, `Providers.byId(id)`, `Providers.usernameFor(profile, address)`. A profile has `imap`/`smtp` endpoints (null for custom), `usernameStyle`, `serverSavesSentCopy`, `verification` (how far the settings are confirmed). Service names to show as plain text: iCloud 메일, Gmail, 네이버 메일, 직접 설정.

Connection checks (`core/net`): `ConnectionChecks.imap(JakartaImapConnector, endpoint, Credentials(username, secret)): CheckResult` and `ConnectionChecks.smtp(endpoint, credentials)`; both blocking, run them on `Dispatchers.IO`. `CheckResult.Ok` or `Failed(failure: ConnectFailure, detail)`; `ConnectFailure` = AUTH, TLS (certificate or host name problem, or the server will not do TLS), UNREACHABLE, SERVICE_DISABLED (server side IMAP/SMTP switched off), OTHER. Show these as distinct messages.

Sync: `container.sync.syncAll()`, `syncAccount(id): SyncOutcome` (Synced, NeedsReauth, Failed(failure)), `loadOlder(accountId, role): Int`, `confirmRole(accountId, role, path)`, `state: StateFlow<Map<Long, AccountSyncState>>` (Idle, Syncing, NeedsReauth, Error(failure)). Folders of an account: `container.db.folders().observeForAccount(accountId)` gives `FolderEntity(path, delimiter, attributes, role, roleConfirmed, ...)`. Resolve roles with `FolderRoles.resolve(infos, confirmedMap)` which returns `RoleResolution` per `FolderRole`: `Assigned(path, source)`, `NeedsConfirmation(candidates)`, `Missing`.

Mail (`MailRepository`): `messages(scope: MailboxScope, limit): Flow<List<MessageSummary>>` (scope `Unified` or `Account(accountId, role)`), `detail(id): Flow<MessageDetail?>`, `unreadCounts(): Flow<Map<accountId, Int>>`, `suspend body(id): BodyResult` (Loaded(text, html, attachments) or Failed), `setSeen(id, seen)`, `allowImages(id)`, `replySeed(id, replyAll, attribution): DraftSeed?`, `forwardSeed(id, header): DraftSeed?`. `MessageSummary.match: RecipientMatch` is `Matched(identityIds)` or `Unknown` (show the matched identity labels, or a text for unknown; never guess). HTML must go through `HtmlSanitizer.sanitize(html, allowRemoteImages)` (core/html) which returns `SanitizedHtml(body, blockedRemoteImages)`.

Drafts and send: `DraftRepository.save(DraftContent): Long`, `load(id)`, `syncToServer(id): Boolean`, `delete(id)`, `drafts: Flow`. `SendService.enqueue(draftId): EnqueueResult` (Queued(outboxId), NoRecipients, InvalidAddress(address), DraftMissing), `processAccount(accountId)`, outbox `items: Flow<List<OutboxEntity>>`, user actions `retry(id)`, `resend(id)`, `checkUnknown(id)`, `confirmSent(id)`, `retrySave(id)`, `discard(id)`.
`OutboxEntity.state` is `SendState`: DRAFT, QUEUED, SENDING, ACCEPTED, SAVED, FAILED, UNKNOWN, SAVE_FAILED. `OutboxEntity.failure` codes: `NETWORK`, `SAVE_NO_FOLDER`, `SAVE_NOT_BY_SERVER`, `SAVE_ERROR`, or `<KIND>` / `<KIND>|<server code>` where KIND is AUTH, TLS, SENDER, RECIPIENT, SERVER. Translate each to a plain Korean explanation with a suggested action.

## Verification you can do

You work in your own git worktree. Set `export ANDROID_HOME=$HOME/Library/Android/sdk` in each shell. Build check: `./gradlew :app:compileDebugKotlin` and `./gradlew :app:compileDebugAndroidTestKotlin`. Core tests: `./gradlew :core:test`. Do NOT run `connectedDebugAndroidTest` or touch the emulator: there is one shared emulator and the project owner runs device tests after merging. Write Compose UI tests in `app/src/androidTest/kotlin/app/dustbird/mail/ui/<feature>/` that build `AppContainer(context, db = Room.inMemoryDatabaseBuilder(...))`, seed data through DAOs and repositories, and use `createComposeRule()`. They must compile; the owner will run them.

## Deliverables and report

Commit your work in the worktree branch in small commits. Final message to the owner, under 40 lines: branch name, list of files created or changed, string resource file names, what each screen does and which states it handles, tests written, API gaps or things you could not do, and anything the owner must double check. Report honestly what you did not verify.
