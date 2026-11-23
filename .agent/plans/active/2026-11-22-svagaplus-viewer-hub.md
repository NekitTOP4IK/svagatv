# Svaga+ in the app: native viewer sign-in and Viewer Hub

## Goal

A ThystTV/SvagaTV user gets everything the Svaga+ browser extension and the
Svaga+ website viewer pages (`/viewer/settings`, `/viewer/social-rating`) offer,
without leaving the app:

- one native Svaga+ viewer sign-in (the same viewer account and viewer JWT the
  extension and site use), offered right after Twitch sign-in;
- native Telegram linking (needed for Tribute subscriptions);
- a native **Viewer Hub** screen: nickname style per channel with live chat
  preview, collectible badge choice, subscriptions and credits, colour slots,
  social-rating boards;
- the remaining extension features (info banners, new-collectible notices).

## Non-goals

- No pairing/device-code flow for Svaga+ (the TV device code in `LoginActivity`
  is only Xtra's Twitch sign-in and stays as is).
- No Twitch-token exchange endpoint: the viewer uses the identical viewer flow
  as the extension (`authorize-url` -> Twitch code -> `POST /api/auth/twitch/viewer`).
- No change to Twitch sign-in, player, playback, or upstream Xtra code paths
  beyond an optional "connect Svaga+" prompt after a successful Twitch sign-in.
- No breaking change to `POST /api/auth/twitch/viewer` or `GET /api/viewer/me`
  (the extension and site depend on them).
- Community presets, suggestions and banner polls are out of the first pass
  (see Decisions).

## Current context

App (`app/src/main/java/com/github/andreyasadchy/xtra/`), uncommitted on `master`
as of 2026-11-22 (33 modified files + new `svagaplus/` package, ~2.9k lines):

- `svagaplus/SvagaPlusApi.kt`: snapshot (ETag), batch badges, badge card,
  social status/rating/vote, aliases, `getViewerMe`, `getTwitchAuthorizeUrl`,
  `linkViewerTwitch`. Default backend `https://svagaplus.com`, overridable.
- `svagaplus/SvagaSocketClient.kt`: `join_channel`/`leave_channel`,
  handles `badge_update`, `social_rating_update`, `badge_grants_updated`.
- `svagaplus/SvagaAuthManager.kt` + `SvagaAuthDialog.kt`: sign-in loads the whole
  website `/viewer-connect` in a WebView, then either catches `?code=` on the
  redirect or scrapes `localStorage.viewer_token`. Fallback: paste a token.
  Token stored in `tokenPrefs()`; 401 on `/viewer/me` clears it.
- Rendering: `ui/view/NamePaintSpan.kt`, `svagaplus/SvagaCssStyleParser.kt`,
  `svagaplus/SvagaFontManager.kt`; badge card `ui/chat/SvagaBadgeCardDialog.kt`;
  vote +/- in `ui/chat/MessageClickedDialog.kt`; settings
  `ui/settings/SvagaSettingsFragment.kt` + `res/xml/svaga_preferences.xml`
  (account, paste token, feature toggles, alias manage/import/export, backend URL).
- Extension parity already reached: badges, styles, fonts, aliases, badge card,
  voting, live socket updates. Missing: info banners
  (`GET /api/extension/banners`, `subscribe_banners`/`extension_banners`),
  unseen collectible grant notices.

Server (`D:\dev\Other projects\SvagaPlus Server`, Flask, separate repo):

- Viewer auth: `GET /api/auth/twitch/authorize-url?redirect_uri&state`
  (`backend/routes/twitch_auth.py:548`), `POST /api/auth/twitch/viewer
  {twitch_code, redirect_uri}` (`:466`) -> viewer JWT (`type=viewer`, 30 days,
  no refresh, no server logout). Redirect URIs must be in
  `TWITCH_ALLOWED_REDIRECT_URIS`; `<backend>/viewer-connect` already is.
- `viewer_jwt_required` in `backend/routes/viewer_colors.py:25`.
- Without `telegram_user_id`, `/api/viewer/channels` and
  `/api/viewer/subscriptions` return empty lists (`viewer_colors.py:248`, `:395`).
- Telegram: `POST /api/extension/link-initiate` (viewer JWT) -> signed 10-min
  token for `t.me/<bot>?start=link_<token>`; unlink via
  `POST /api/extension/viewer/unlink-request` + bot confirmation.
- Viewer Hub endpoints (all viewer JWT unless noted):
  - `GET /api/viewer/me`
  - `GET /api/viewer/subscriptions`, `GET /api/viewer/credits`
  - `GET /api/viewer/channels`; `GET/PUT/DELETE /api/viewer/channel/<ch>/color`
    (PUT 20/h; body `name_color|name_gradient|preset_id|font_preset_id|
    name_effects|clear_color|style_source`, validated in
    `backend/services/viewer_color_mutation.py`)
  - `GET /api/viewer/presets`, `GET /api/viewer/font-presets` (public)
  - `GET /api/viewer/color-slots`, `PUT/DELETE /api/viewer/color-slots/<1..10>` (60/h)
  - `GET /api/viewer/collectible-badges`, `PUT /api/viewer/collectible-badge-selection`,
    `GET .../unseen`, `POST .../<grant_id>/seen`
  - Social boards (public): `GET /api/social/channels`,
    `/api/v3/social/channels/<ch>/periods`, `.../board?period=`,
    `.../board/players/<login>`
- Rate limits are keyed by client IP (`backend/extensions.py`), which mobile
  CGNAT shares between many users.
- `X-SvagaPlus-Client` is parsed only as `ext/x.y.z (chrome|firefox)`
  (`backend/utils/ext_auth_log.py:21`).

## Files likely involved

App:
- `app/src/main/java/com/github/andreyasadchy/xtra/svagaplus/*`
- new `app/src/main/java/com/github/andreyasadchy/xtra/ui/svaga/` (Viewer Hub)
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/SvagaSettingsFragment.kt`
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/login/LoginActivity.kt` (prompt only)
- `app/src/main/java/com/github/andreyasadchy/xtra/ui/chat/MessageClickedDialog.kt` (entry point)
- `app/src/main/res/{layout,xml,navigation,values,values-ru}/*`
- `app/src/test/java/com/github/andreyasadchy/xtra/svagaplus/*`

Server (separate PRs in its own repo):
- `backend/routes/extension.py`, `backend/utils/ext_auth_log.py`,
  `backend/extensions.py`, `backend/routes/viewer_colors.py`, tests.

## Risks

- Chat rendering regressions (style preview shares `NamePaintSpan`/parser).
- Floating chat readability if new overlays/banners appear over video.
- WebView sign-in edge cases: expired Twitch cookies, consent screen, back press.
- Telegram linking when the Telegram app is not installed or the user returns
  without finishing.
- Server rate limits by IP for mobile users.
- The large uncommitted Svaga+ diff on `master` makes review and rollback hard.

Risk level: medium (no player lifecycle change; chat/UI and auth touched).

## Human approval

Required before implementation: yes

Reason: spans two repositories, changes the auth UX, adds a new top-level screen.

## Implementation steps

0. Move the current uncommitted Svaga+ work off `master` onto a feature branch
   and commit it as the baseline (maintainer decision).

0b. Dev build and test infrastructure (app + CI PR, before step 1).
   - New `dev` build type: `.dev` suffix, R8 like release, signed with a
     stable dev key from GitHub Secrets (never committed). Fix
     `debug-build.yml`, which generates a new keystore per run (APKs from
     different runs cannot update each other).
   - CI on push to `dev` publishes a GitHub pre-release `dev-<run>` with a
     growing `versionCode`; `UpdateUtils` in dev builds accepts pre-releases,
     release builds ignore them. Needs the SvagaTV repo (`origin` is still
     `tzii/ThystTV`; the updater already points at `olesha-entertainment/SvagaTV`).
   - Local loop: Wi-Fi/USB `adb` + `installDebug`; local server via
     `adb reverse tcp:5170 tcp:5170` and backend `http://localhost:5170`
     (Vite proxies `/api`, `/socket.io`, `/cdn` to 5016; that redirect is in the
     default allow-list); debug-only cleartext for `localhost`.
   - Dev menu in a `src/devtools/` source set used only by `debug` and `dev`
     (release gets no-op stubs). Minimum: backend presets (Prod/Staging/Local/
     custom, token per backend), "DEV · <backend>" chip, Svaga+ account tools
     (decoded JWT, break token, paste token, clear Twitch cookies / site
     storage), Chucker with `Authorization` redacted. Later: fixture scenarios
     via OkHttp interceptor, socket event log + injected events, style test
     bench over all server presets, feature flags, cache reset, shareable log.
   - Staging is the default backend for dev builds; confirm its
     `TWITCH_ALLOWED_REDIRECT_URIS` and Twitch console redirect.

1. Native viewer sign-in (app PR, no server change).
   - `SvagaAuthManager.startSignIn()`: generate `state`, call
     `getTwitchAuthorizeUrl(redirect=<backend>/viewer-connect, state)`, verify the
     returned URL's `redirect_uri` and host `id.twitch.tv`.
   - Replace `SvagaAuthDialog`'s website load with the Twitch authorize URL only.
     Intercept the redirect in `shouldOverrideUrlLoading`, check `state` and
     `error`, never render the website, call `linkViewerTwitch`, then `/viewer/me`.
     Drop `localStorage` scraping. The WebView shares Twitch cookies with the
     app's Twitch sign-in, so an already-authorized user usually returns at once.
   - Guard against concurrent sign-ins; one in-flight attempt at a time.
   - After a successful Twitch sign-in, show a one-tap "Connect Svaga+" prompt
     (dismissible, remembered).
   - Any 401 from an authenticated Svaga+ call clears the account and shows a
     "reconnect" action (extension audit 3.8 found this missing there).
   - Send `X-SvagaPlus-Client: android/<versionName>` on every request.
   - Keep paste-token as a hidden/advanced fallback.

2. Telegram linking through the existing bot (app PR, no server change).
   - Linking already works on the website through the existing Telegram bot;
     no new bot or scheme. Users who already linked on the site or via the
     extension skip this step (`/viewer/me` returns `telegram_linked=true`).
   - Chosen (maintainer): when `/viewer/me` says `is_linked=false`, show a red
     "Telegram не привязан" banner (Viewer Hub, account sheet, Svaga+
     settings; never over video). Its button opens the existing WebView dialog
     on `<backend>/viewer-connect`.
   - The site shows its Telegram step only right after its own Twitch code
     exchange (`ViewerConnectPage.tsx` sets `telegramStep` there); with a token
     already in `localStorage` it shows "Войти через Twitch" again, and
     `/viewer/settings` has no link button. So the site runs its own Twitch
     sign-in: shared Twitch cookies + no `force_verify` make Twitch redirect at
     once, landing on the Telegram step. In this mode the app must NOT
     intercept the code (the site exchanges it; avoids the double-exchange race).
   - Enable `javaScriptCanOpenWindowsAutomatically` (the site calls
     `window.open(t.me…)` after an async request); keep forwarding `t.me` to the
     Telegram app. Close the dialog when the app's own `/viewer/me` poll sees
     `is_linked=true`; `/viewer/settings` navigation is a secondary signal.
     Clear the backend origin's WebView storage on logout.
   - Fallback if the site flow changes: the app calls `link-initiate` itself
     and opens the same bot link (bot username from per-backend app config).
   - App: unlink via `unlink-request` with confirmation polling (or the site).
   - Server (independent, optional): single-use link token,
     `hmac.compare_digest` for `X-Bot-Secret`, parse `android/x.y.z`.

3. Viewer Hub screen (app PRs, one per tab, in this order).
   - Shared `SvagaViewerRepository` (Bearer calls, in-memory + disk cache,
     optimistic updates with rollback, rate-limit-aware errors).
   - Entry points: Settings -> Svaga+, the account row, and "My style here" on
     the user's own message in `MessageClickedDialog`.
   - a. Style: channel list from `/viewer/channels`; editor for colour, gradient,
     preset, font preset, effects; live preview rendered with the same
     `NamePaintSpan`/`SvagaCssStyleParser`/`SvagaFontManager` as chat, on both a
     normal chat background and the video-overlay background; respect
     `style_unlocks`, `viewer_colors_enabled`, `color_banned`; explicit Save
     (PUT is 20/h); colour slots load/save.
   - b. Badges: collectible grid, select/clear, rarity tags (reuse badge card).
   - c. Subscriptions: channels, streak/months, active/banned, tier progress,
     credits.
   - d. Social rating: channel picker, periods, board, player detail.
   - Compact and wide (two-pane) layouts; D-pad focus for TV.

4. Extension remainders (app PR).
   - Info banners from `/api/extension/banners` + `subscribe_banners`, shown in
     the Hub/settings, not over video.
   - New collectible notice from `.../unseen` + `badge_grants_updated`, mark seen.

5. Server hardening for mobile (server PR, can go in parallel with 3).
   - Key viewer-JWT endpoint limits by JWT identity instead of IP.
   - Optional `GET /api/viewer/bootstrap` aggregating me/channels/collectibles/
     color-slots to cut round trips on open.

## Verification

Automated checks:

- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew test` (unit tests for auth state handling, redirect/state
      checks, repository parsing, optimistic update rollback)
- [ ] `./gradlew lintDebug` (UI/resources)
- [ ] Server: backend pytest for changed routes

Human QA required:

- [ ] Fresh install: Twitch sign-in -> Connect Svaga+ prompt -> connected in one tap
- [ ] Svaga+ sign-in with Twitch cookies expired; with consent screen; back press
- [ ] Telegram linking with Telegram installed and not installed; returning
      without finishing; unlink
- [ ] Expired/revoked viewer JWT -> reconnect prompt, no crash
- [ ] Style change saved in Hub appears in live chat on the same channel
- [ ] Preview matches chat rendering, including floating chat over video
- [ ] Collectible selection shows in chat; new collectible notice
- [ ] Compact phone, landscape, tablet/wide, TV D-pad
- [ ] Live stream, VoD, stream switching, minimize/restore unaffected

Human QA completed:

- [ ] (none yet)

## Progress log

- 2026-11-22: Created plan after surveying the app, the Svaga+ extension and
  server. Decided on the identical viewer flow (no pairing, no token exchange).
- 2026-11-22: Wrote a detailed variant-by-variant design review (tech, mockups,
  pros/cons, trade-offs), kept outside the repo as a private artifact:
  https://claude.ai/artifact/1aqnDH5697cj2HASjejPwh. Not yet approved by the
  maintainer. New findings: `SvagaAuthDialog` lets the site exchange the same
  one-time Twitch code the app intercepts (`shouldOverrideUrlLoading` returns
  `false`), so one exchange fails; `SvagaCssStyleParser` lacks
  `conic-gradient`, which the server accepts.
- 2026-11-23: Maintainer accepted the design review as the base. Telegram
  linking switched to the red banner + site WebView variant. Added section 13
  (testing and dev build) to the review and step 0b here. Open before
  implementation: own GitHub repo for SvagaTV, dev key in Secrets, staging
  Twitch redirect, and the AGENTS.md commit-author rule (still ThystTV's `tzii`).
- 2026-11-23: Solo developer and tester. Repo will be public (GitHub
  `olesha-entertainment/SvagaTV`, matching `UpdateUtils`). Public: source,
  release builds, optional dev pre-releases; all default to prod
  `https://svagaplus.com`. The staging URL never enters git or CI APKs: it
  lives only in `local.properties` (`svaga.stagingUrl`) and reaches
  `BuildConfig` in local builds; the dev-menu "Staging" preset is hidden when
  it is empty. Staging stays open (no nginx gate) for now. If it is ever
  locked: use a secret header/cookie/query gate, not `auth_basic` (it collides
  with `Authorization: Bearer`), return 403 not 401 (the app logs out on 401),
  and keep webhooks, `/api/health` and `/cdn/` open.

## Decisions

- Decision: use the same viewer OAuth flow as the extension, with only the
  Twitch authorize page in the app WebView and redirect `<backend>/viewer-connect`.
  Reason: maintainer wants identical viewer auth; no server auth change; the
  redirect is already allow-listed; shared Twitch cookies make it near one-tap.
  Alternatives considered: Twitch-token exchange via `oauth2/validate`
  (rejected: new trust surface), viewer pairing like the timer (rejected: not
  needed), Custom Tab + App Link redirect (possible later; needs
  `assetlinks.json` on the backend domain).
- Decision: Telegram linking goes through the bot deep link, not Telegram Login
  OIDC in a WebView.
  Reason: native Telegram app handoff, no third-party login page in WebView.
- Decision: community presets, suggestions and polls are deferred.
  Reason: lower value; the site keeps them; revisit natively after the Hub.

## Final PR summary draft

Summary:
Tests:
Human QA:
Risks:
