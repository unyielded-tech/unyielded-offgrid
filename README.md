# UNYIELDED // OFFGRID

**NO CLOUD. NO COMPROMISE.**

Private, offline communication between nearby Android devices.  
No internet. No servers. No accounts. No phone numbers.

## Version 0.3.0 — V3 Security Release

### What it does
- Discover nearby Android phones over classic Bluetooth
- Establish a secure RFCOMM session
- Exchange messages encrypted with **AES-256-GCM**
- Ephemeral **ECDH (P-256)** key exchange + **HKDF-SHA-256**
- Human-readable security fingerprint for in-person verification
- Premium dark UI designed for privacy-first use

### Security model (honest)
| Guarantee | Status |
|-----------|--------|
| Confidentiality against passive eavesdroppers | ✅ |
| Integrity / authenticity of messages | ✅ |
| Detection of active MITM | ✅ only when fingerprints are compared |
| Long-term identity / authentication | ❌ (ephemeral only) |
| Perfect forward secrecy beyond the session | ❌ |
| Protection against compromised devices | ❌ |

**Always compare the fingerprint face-to-face before trusting the channel.**

### Build
Push to `main` or run the workflow manually.  
Download the `unyielded-offgrid-debug-apk` artifact.

Built by **UNYIELDED**.
