# LEVVER VPN UTILITY — macOS

Native SwiftUI client for macOS 13+ (first CI target: Apple Silicon).

## Current preview scope
- Native macOS interface.
- Import of complete sing-box JSON configuration files.
- Local configuration storage in Application Support.
- Validation using the bundled sing-box executable.
- Start/stop of the sing-box process.

## Important limitations
This preview is not yet a signed/notarized App Store-ready release. A running sing-box process does not by itself prove that macOS installed all routes or that every configuration works. TUN permissions, DNS behavior, sleep/wake recovery, and real network traffic must be tested on a Mac. A production release should add a properly signed Network Extension / privileged helper, Keychain-backed secrets, profile management, diagnostics, and Apple notarization.

Only use profiles and VPN servers you own or are authorized to use.
