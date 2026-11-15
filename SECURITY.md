# Security policy

Reev is a keyboard: it sits between the user and every field that takes credentials, so reports about it
matter. Thank you for looking.

## Reporting a vulnerability

Email **security@reev.download**, or open a [private security advisory](https://github.com/asispan/reev/security/advisories/new).
Please don't open a public issue for anything exploitable.

Include: affected version or commit, the Android version and device, and steps to reproduce. A proof of
concept helps but isn't required.

## What to expect

- Acknowledgement within 5 working days.
- An assessment and a fix timeline within 10 working days of the acknowledgement.
- Credit in the release notes unless you'd rather stay anonymous.
- No bounty scheme yet; this is a single-developer project.

## Scope

In scope: the keyboard service and its views, audio capture and the speech engines, API key storage, model
download and verification, the settings screens, and the release/build configuration.

Out of scope: the cloud speech providers themselves (report to the provider), and anything that needs root
or a custom ROM to trigger unless it leads to key or transcript disclosure.

## For banks and app-protection vendors

There is a short brief at <https://reev.download/security.html> covering permissions, credential-field
behaviour, the signing certificate and the push-to-talk design.
