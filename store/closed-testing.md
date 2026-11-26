# Reev closed test → production

Google's rule (new personal accounts): **12+ testers opted in for 14 consecutive days**, then apply for production in Play Console → Dashboard → "Apply for production". Testers who opt out before day 14 don't count, so recruit 15–20.

## Status, 26 Nov 2026
- **Release 4 (0.3.0)** uploaded to the Closed testing – Alpha track and **sent for review** (code 4, upload-signed, 178 countries). It carries five rounds of AI-QA fixes; 3 (0.2.0) stays live for testers until the review clears.
- **Google Group is open**: `alpha-testers-reev-app@googlegroups.com` is public, "Anyone on the web can join" (no approval step).
- **Feedback channel on the track**: https://github.com/asispan/reev/issues
- **Recruiting posts live** (26 Nov): r/AndroidClosedTesting `1y5qv91`, r/droidapptesters `1y5qw7g`, r/TestersCommunity `1y5qx9m`. Everyone who comments gets tested back.
- Tester log: `store/testers.csv`.

## 1. Console setup (done)
1. Group: "Anyone on the web can join" ✔ (Groups → Group settings → Who can join group).
2. Closed testing → Testers: group added ✔, feedback URL set ✔.
3. Latest build on the track ✔ (0.3.0 in review).
4. Opt-in links:
   - Web: https://play.google.com/apps/testing/com.asis.reev
   - Store: https://play.google.com/apps/details?id=com.asis.reev

## 2. Build and upload the next release
```bash
export REEV_KEYSTORE=$HOME/.hermes/private/reev/upload.jks REEV_KEY_ALIAS=reev-upload
export REEV_KEYSTORE_PASSWORD=$(op item get fdl5supinjj366wrpwwvruo34y --vault sudo-company --format json | python3 -c "import json,sys;print([f for f in json.load(sys.stdin)['fields'] if f['id']=='password'][0]['value'],end='')")
./tools/release.sh      # app/build/outputs/bundle/release/app-release.aab
```
Bump `versionCode`/`versionName` in `app/build.gradle.kts` first. `op item get --fields password` returns extra newlines for this item; read the JSON field instead.

## 3. Message to friends / colleagues (WhatsApp/LinkedIn)
> Hey! I built **Reev**, an open-source Android keyboard you can talk to. Dictation runs on the phone, nothing leaves the device.
> Google needs 12 people to test it for 2 weeks before it can go public. Could you help? It's 2 minutes:
> 1. Join: https://groups.google.com/g/alpha-testers-reev-app (use the Gmail on your Android phone)
> 2. Opt in: https://play.google.com/apps/testing/com.asis.reev
> 3. Install from Play, switch to Reev, and keep it installed for 14 days.
> Use it a couple of times and send me anything that annoys you. 🙏

## 4. Tester-exchange communities
- r/AndroidClosedTesting, r/droidapptesters, r/TestersCommunity: posted. Test others back (14 days, open their app a few times, comment with a screenshot in r/TestersCommunity).
- Keep `store/testers.csv` up to date. Tester count is visible in Console → Closed testing → Testers.

## 5. During the 14 days
- Ship at least one update to the closed track (Google looks for an active test, not a parked one).
- Ask testers for feedback around day 5 and day 10.

## 6. Production-access questionnaire (draft, fill after day 14)
- **How did you recruit testers?** Friends, colleagues, and Android closed-testing communities (r/AndroidClosedTesting, r/droidapptesters, r/TestersCommunity) via a Google Group.
- **How easy was it to recruit?** _(honest answer)_
- **Describe engagement:** _(n testers, sessions, what they used: typing, dictation, emoji)_
- **Feedback received & how you collected it:** Play track feedback link (GitHub issues), Reddit comments, direct messages. _(list top items)_
- **What changed based on testing:** 0.3.0 shipped before/with the test: Hinglish toggle, Settings rotation clipping, private-field mic note, History Back, 48 dp controls, 200%-font layout fixes.
- **Who is the app for:** People who want a private, open-source keyboard with on-device dictation.
- **What makes it ready for production:** _(crash-free sessions from Android vitals, fixes shipped, privacy policy live)_
- **Changes made to the app based on what you learned:** _(versions shipped during test)_
