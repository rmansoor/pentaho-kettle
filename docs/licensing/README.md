# Offline license key — MVP

Answers exactly one question, entirely offline, with no server call at any point:
**has this installation been given a key that Hitachi Vantara actually issued?**

No seats, no machine binding, no trial, no per-execution re-check, no UI — those are
deliberately deferred to later phases. See the design discussion this shipped from
for the full roadmap.

## How it works

- `pentaho-kettle:kettle-license` is a new reactor module (`license/`), a dependency
  of `kettle-engine`.
- A license key is a self-contained, ECDSA P-256–signed token: `LicensePayload`
  (license id, customer, edition, issued/expiry) + signature, base64url-encoded
  with an `HELIX1-` prefix. Verification checks the signature against a public key
  **embedded in the jar** (`license/src/main/resources/.../helix-license-public-key.der`)
  and the expiry — no network, no lookup.
- `KettleEnvironment.init()` — the one bootstrap chokepoint shared by `Spoon`,
  `Carte`, `Pan`, and `Kitchen` — calls `LicenseEnforcement.requireLicensed()`
  right after `KettleClientEnvironment.init()`. No valid key installed = the
  process refuses to start, for all four entry points, from this one hook.

## ⚠️ Before any customer-facing build

The embedded public key is a **development placeholder**, generated for this
repo, with the matching private key committed *only* under `license/src/test/resources`
for unit tests. It must be replaced with the real Hitachi Vantara signing key's
public half before anything customer-facing ships. The private half of that real
key must never be committed anywhere — it lives with whoever runs the issuing
tool, offline.

## Using it

Install a key:
```
license install HELIX1-...
```
Check status:
```
license status
```
Remove a key:
```
license remove
```
The key lives at `~/.kettle/.helix-license` (or wherever `KETTLE_HOME` points).
`-Dhelix.license.key=<key>` overrides the file, for scripted/containerized use.

Issuing a key (internal only — never ships to customers, needs the real private
key which never leaves wherever it's held):
```
license-issuer --private-key <path to PKCS8 DER EC private key> \
                --customer "Acme Corp" --edition ce \
                [--expires-in-days 365 | --perpetual]
```

## What's deliberately not here yet

Seat counts / concurrent-client limits, machine-fingerprint binding, activation
and deactivation against a license server, the 30-day auto-trial, deployment
licenses for containerized Carte, per-execution (not just per-startup)
re-validation, and a UI. All of these were scoped in the fuller design and layer
on top of this primitive without requiring a redesign of the key format or the
verification path.
