# Offline license activation

PDI checks its license at startup, entirely offline: no server, no network call, at activation or ever after.
A license is **activated for one machine**. The activation file is signed by Hitachi Vantara and names that
machine's fingerprint, so a copy on another machine doesn't work. Hitachi Vantara keeps a ledger of
activations, so one license key can't be activated on a second machine until the first activation is released.

## The three codes

| Code | Prefix | Made by | What it is |
|---|---|---|---|
| License key | `HELIX1-` | Hitachi Vantara (`license-issuer issue`) | What the customer buys: license id, customer, edition, expiry, signed. Doesn't run PDI by itself. |
| Activation request | `HELIXREQ-` | The customer's machine (`license request`) | The license key plus this machine's fingerprint. Unsigned, with a checksum. |
| Activation | `HELIXACT-` | Hitachi Vantara (`license-issuer activate`) | The license, bound to the requesting machine's fingerprint, signed. What PDI checks at startup. |

Plus a **release code** (`HELIXREL-`), printed by `license deactivate` when a customer moves the license.

The codes are about 300 characters: they fit in an email, and line breaks a mail client adds are ignored. Every
command also accepts a file holding the code.

## Activating a machine

The customer receives a license key (`HELIX1-...`) and never sees anything else of the machinery.

**On a desktop:** start Spoon. When the machine isn't licensed, Spoon opens *Activate Pentaho Data Integration*:
1. Paste the license key (or open the file it came in), **Next**.
2. The window shows the activation request: **Copy** or **Save to file...**, and send it to Hitachi Vantara from
   any computer. Paste the activation code that comes back (or open its file), **Activate**: Spoon starts.

The window can be closed while waiting for the answer (**Finish later**): the request is kept, and the next start
opens on step 2.

**On a server:** `./license.sh activate` (`License.bat activate`) asks the same two questions in the terminal, and
can likewise be stopped after the first and run again later. Or drop the activation file (`*.helixact`) into the
PDI folder (or `~/.kettle`): PDI installs it at its next start and renames it `*.helixact.installed`.

For scripts, the two steps are also separate commands: `license request <key> [--out file]`, then
`license install <code | file>`.

At Hitachi Vantara, answering a request is one command:
```
license-issuer activate --private-key <key.der> --ledger <ledger.tsv> HELIXREQ-...      # --out activation.helixact
```

**Testing** (development key only): `license-issuer/dev-license.sh key` prints a license key, and
`license-issuer/dev-license.sh answer <request code | activation-request.txt>` prints the activation code.

## Moving a license to another machine

```
./license.sh deactivate               # on the old machine: removes the activation, prints HELIXREL-...
license-issuer release --ledger <ledger.tsv> HELIXREL-...                      # Hitachi Vantara
```
Then activate the new machine as above. If the old machine is gone (hardware failure), Hitachi Vantara releases
the activation itself: `license-issuer release --ledger <ledger.tsv> --activation-id <id> --reason "..."`.

A release can't be verified offline: a copy of the old machine could keep its activation. The release code is a
record for the ledger and for support, not proof.

## The machine fingerprint

Collected by PDI with no network access and no native code, and only ever sent or stored hashed (SHA-256, salted
with the license id, so two licenses on one machine have unrelated fingerprints):

| Fact | Linux | Windows | macOS |
|---|---|---|---|
| Machine id | `/etc/machine-id` | `MachineGuid` (registry) | `IOPlatformUUID` |
| Network cards | MAC addresses of physical cards; loopback, virtual, VPN, container and randomized addresses are left out | same | same |
| Host name | without its domain, any case | same | same |
| OS family | linux / windows / mac | | |

The activation matches when the OS family is the same and:
- the machine id is the same, plus a shared network card **or** the same host name (a replaced network card, or a
  renamed host, is fine; a machine id alone could come from a cloned disk image);
- or, on a machine without a readable machine id: a shared network card **and** the same host name.

`license request` refuses on a machine with no stable identity (no machine id and no hardware network card, as
in a container): an activation bound to it could never be checked.

## Containers

Containers get a new identity on every start. They use an **unbound activation**, which only Hitachi Vantara can
issue, and which uses up the license key like any other activation:
```
license-issuer activate --unbound --private-key <key.der> --ledger <ledger.tsv> HELIX1-...
```
Pass it to the container as a file (`HELIX_LICENSE_FILE=/path/in/container`) or as the code itself
(`HELIX_LICENSE=HELIXACT-...`).

## Where PDI looks for the license

First found wins:
1. system property `-Dhelix.license.key=<code>`, or environment variable `HELIX_LICENSE`
2. the file named by system property `-Dhelix.license.file=<path>`, or environment variable `HELIX_LICENSE_FILE`
3. `~/.kettle/.helix-license` (or `$KETTLE_HOME/.kettle/.helix-license`), where `license install` writes

For a time-limited license, PDI also records the latest time it was checked at (`.helix-license.seen`, next to
the license file). If the clock is later set back by more than a day, PDI refuses to start until it is corrected.

## The issuing tool and ledger

`license-issuer/` (`kettle-license-issuer` jar) is internal: it is never part of a PDI distribution
(`qa/cve-remediation/verify_dist.py` check D7 enforces this), and it needs the private signing key.
```
java -jar kettle-license-issuer-<version>.jar <command> ...      # kettle-license-<version>.jar next to it
  issue    --private-key K --ledger L --customer C [--edition ce] [--expires-in-days N]
  activate --private-key K --ledger L <HELIXREQ-... | file>
  activate --private-key K --ledger L --unbound <HELIX1-... | file>
  release  --ledger L <HELIXREL-... | file>
  release  --ledger L --activation-id ID --reason "..."
  list     --ledger L [license id]
```
The ledger is a tab-separated text file, only ever appended to (under a file lock):
`time, event (issued | activated | released), license id, activation id, customer, edition, binding, note`.
A license can be activated again only when all its activations are released, or by the same machine (the old
activation is then recorded as replaced). Back it up like any other business record.

## ⚠️ Before any customer-facing build

The embedded public key (`license/src/main/resources/.../helix-license-public-key.der`) is a **development
placeholder**; its private half is committed under `license/src/test/resources` for tests. Anyone with this
repository can issue licenses for it. Before shipping:
1. Generate the real key pair (EC P-256) where the private key will live, and never commit the private half.
2. Replace `helix-license-public-key.der` with the real public key (X.509 DER).
3. The development activations stop working: Kettle's unit tests (`pdi.test.license.argLine` in the root pom)
   and the QA kit (`qa/cve-remediation/license/`) need a test-only arrangement then, for example unit tests
   verifying against the development key through `LicenseVerifier(PublicKey)`, and a QA activation issued with
   the real key kept outside the repository (`PDI_QA_LICENSE`).

## Limits

- Offline licensing deters; it doesn't prevent. PDI is Java: a determined user can patch the check out of the
  bytecode, or build PDI CE (Apache 2.0) from source without it.
- One activation per license key. Seats, trials and per-execution checks are not implemented.
