# Offline license activation

PDI checks its license at startup, entirely offline: no server, no network call, at activation or ever after.
A license is **activated for one machine**. The activation file is signed by Hitachi Vantara and names that
machine's fingerprint, so a copy on another machine doesn't work. Hitachi Vantara keeps a ledger of
activations, so one license key can't be activated on a second machine until the first activation is released.

## The three codes

| Code | Prefix | Made by | What it is |
|---|---|---|---|
| License key | `HELIX1-` | Hitachi Vantara (`license-issuer issue`) | What the customer buys: license id, customer, edition, term (expiry), signed. Doesn't run PDI by itself. |
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

Customers can also answer it themselves on the [license portal](#the-license-portal): paste the request (or upload
its file), get the activation code back at once.

At Hitachi Vantara, answering a request is one command:
```
license-issuer activate --private-key <key.der> --ledger <ledger.tsv> HELIXREQ-...      # --out activation.helixact
```

**Testing** (development key only): `license-issuer/dev-license.sh key [customer] [term]` prints a license key,
`license-issuer/dev-license.sh renew <key> [term]` renews one, and
`license-issuer/dev-license.sh answer <request code | activation-request.txt>` prints the activation code.

## Terms and renewal

A license runs for a **term**: 1 to 10 years, or **perpetual**. A year is a calendar year (UTC): a one-year
license issued on 19 October 2026 expires on 19 October 2027. The key carries the expiry date itself, so keys
issued before terms existed, and trials of a number of days (`--expires-in-days N`), verify the same way.
```
license-issuer issue --private-key <key.der> --ledger <ledger.tsv> --customer "Acme Corp" --edition ee --term 1y
```
`--term` is required (`1y`, `2y`, ..., or `perpetual`), so no license is perpetual by accident.

**Before expiry**, PDI warns for the last 30 days: `license status` prints a warning, Spoon shows it at startup,
and Pan, Kitchen and Carte log it.

**After expiry**, an installed activation keeps PDI running for a **14-day grace period**, so scheduled work doesn't
stop while a renewal is on its way: every start warns that the license has expired and when the grace period ends
(Pan, Kitchen and Carte log it as an error). Then PDI refuses to start, like any other license problem. The grace
period is for activations only: an expired license key can't be activated on a new machine (only renewed).

**Renewing** keeps the license id, customer and edition, and adds the new term to the current expiry (renewing
early loses nothing; an expired license is renewed from today). Renewing for `perpetual` makes it perpetual.
```
license-issuer renew --private-key <key.der> --ledger <ledger.tsv> --term 1y <HELIX1-... | file> [--out <dir>]
```
It prints the renewed license key (for activating machines from now on) and, for every machine the license is
activated on, a new activation code: the same activation, signed again with the new expiry. The customer installs
it without sending a new request: `./license.sh install <code | file>`, or drop the `.helixact` file into the PDI
folder (installed at the next start, even while the old activation is still valid), or, once expired, paste it in
Spoon's activation window. The ledger records the renewal as a `renewed` line.

## Moving a license to another machine

```
./license.sh deactivate               # on the old machine: removes the activation, prints HELIXREL-...
license-issuer release --ledger <ledger.tsv> HELIXREL-...                      # Hitachi Vantara
```
Then activate the new machine as above. On the [license portal](#the-license-portal) the customer pastes the release
code on the license's page instead. If the old machine is gone (hardware failure), Hitachi Vantara releases
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

## Instance limits: N PDI processes at once

A license can allow a number of **simultaneous PDI processes**, for example 300 containers. Each PDI JVM counts as
one: each Pan or Kitchen run, each Carte server, each Spoon, each Pentaho Server. `license` commands don't count.
The design, with the stories and the third tier (a license server, not built yet), is in
[instance-limits.md](instance-limits.md).
```
license-issuer issue ... --term 1y --instances 300 [--counting declared | folder] [--over-limit refuse | warn]
license-issuer change --private-key K --ledger L --instances 500 <HELIX1-... | file> [--out DIR]
```
- **`--counting declared`** (trust): the number is in the license, and `license status` and the startup log show it.
  Nothing counts the processes.
- **`--counting folder`** (deterrence, the default): every PDI process holds one of N slots in a folder they all
  share, while it runs. With all N taken, the next one is refused (`--over-limit refuse`, the default), or starts
  with an error in its log and is recorded as over the limit (`--over-limit warn`).

The limit is signed into the key and its activations, so a customer can't change it. A renewal keeps it unless it
is given again. `change` raises or lowers it and keeps the expiry. Like a renewal, `change` prints a new key and a
new activation code for each active activation. Dropping that code into the PDI folder installs it, even while the
old one is valid. A key with a limit needs this PDI or later: older builds refuse it ("Unsupported license payload
version: 2"). Keys without a limit are unchanged.

**The folder:** `HELIX_LICENSE_SLOTS` (or `-Dhelix.license.slots`).
- **For an activation bound to a machine**, it defaults to `~/.kettle/.helix-slots`, which counts the processes on
  that machine.
- **For an unbound activation (containers)**, it is required. Without it PDI refuses to start and says so.

Mount one folder into every container:
```
# Docker: one named volume (or bind mount) shared by every PDI container
docker run -e HELIX_LICENSE="$(cat acme.helixact)" -e HELIX_LICENSE_SLOTS=/pdi-slots \
  -v pdi-slots:/pdi-slots pdi:9.4 ./kitchen.sh -file=/jobs/nightly.kjb

# Kubernetes: a ReadWriteMany volume (EFS, Azure Files, Filestore, CephFS, NFS) mounted in every PDI pod
env:
  - { name: HELIX_LICENSE, valueFrom: { secretKeyRef: { name: pdi-license, key: activation } } }
  - { name: HELIX_LICENSE_SLOTS, value: /pdi-slots }
  - { name: HELIX_LICENSE_WAIT, value: "300" }    # optional: wait up to 5 minutes for a free slot
volumeMounts: [ { name: pdi-slots, mountPath: /pdi-slots } ]
volumes: [ { name: pdi-slots, persistentVolumeClaim: { claimName: pdi-slots } } ]   # accessModes: [ReadWriteMany]
```
- **`HELIX_LICENSE_WAIT=<seconds>`** (or `-Dhelix.license.wait`): with every slot taken, wait that long for one to
  free up instead of failing at once. Useful when a scheduler starts bursts of jobs. The default is 0.
- **`./license.sh usage`** shows who holds the slots now (host, pid, since, heartbeat), and for each month the most
  processes that ran at once, the starts, and the starts over the limit. It reads the folder without taking a
  slot.

**How slots work:**
- **A slot is an exclusive lock on `<folder>/<license id>/slot-NNNN`**, held while the process runs, so a crash
  never leaks one. The operating system releases the lock when the process ends, however it ends. On NFSv4 or SMB,
  the file server releases it when the client's lease expires, typically within 90 seconds.
- **The process writes a heartbeat into its slot every 20 seconds.** Some folders accept locks without enforcing them
  between machines (NFS mounted with `nolock` or `local_lock`). In such a folder, a slot that is unlocked but names a
  process elsewhere with a heartbeat under a minute old is treated as taken, and the refusal says why.
- **Crashes on another machine:** a slot whose process crashed there stays taken until its heartbeat is a minute
  old. A crash on the same host is recognised at once (the pid is gone).
- **Clocks:** hosts need clocks within about a minute of each other.
- **Records:** starts and stops go to `usage-YYYY-MM.tsv` next to the slots. One process at a time also records how
  many are running.

This is deterrence, not enforcement. Processes given different folders count separately, and deleting the folder
frees every slot. The records live in the customer's folder: they are evidence for the renewal conversation, not
proof.

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
  issue    --private-key K --ledger L --customer C [--edition ce] --term <1y | 2y | ... | perpetual>
  issue    --private-key K --ledger L --customer C [--edition ce] --expires-in-days N          (a trial)
           issue and renew also take --instances N [--counting declared | folder] [--over-limit refuse | warn]
  renew    --private-key K --ledger L --term <1y | ... | perpetual> <HELIX1-... | file> [--out DIR]
  change   --private-key K --ledger L --instances <N | none> <HELIX1-... | file> [--out DIR]
  activate --private-key K --ledger L <HELIXREQ-... | file>
  activate --private-key K --ledger L --unbound <HELIX1-... | file>
  release  --ledger L <HELIXREL-... | file>
  release  --ledger L --activation-id ID --reason "..."
  list     --ledger L [license id]
```
The ledger is a tab-separated text file, only ever appended to (under a file lock):
`time, event (issued | activated | released | renewed), license id, activation id, customer, edition, binding, note`.
A license can be activated again only when all its activations are released, or by the same machine (the old
activation is then recorded as replaced). Back it up like any other business record.

## The license portal

The customer-facing site for all of the above, in its own repository (`rmansoor/pdi-license-portal`, a Spring Boot
application in Docker with Postgres). It uses `license-issuer`'s `Issuer` and `Ledger`, so the portal and the command
line can share one ledger file. People sign in through any OpenID Connect provider (Keycloak, Microsoft Entra ID,
Okta, Auth0, ...); administrators are a group or app role there.

**Customers** request a license (edition, term, what it's for), activate machines (paste the `HELIXREQ-` request,
get the `HELIXACT-` code or a `.helixact` file), release them with the `HELIXREL-` code, and request renewals. They
see their organization's licenses only. Colleagues join an organization through a verified email domain the
administrators add, or when their own request is approved.

**Administrators** approve or reject requests (issuing the key to a new or existing organization), renew, release
an activation without its code (machine gone, with a reason), issue unbound activations for containers, and see
every license, the activity log and the ledger.

**Email** (any SMTP server):

| Who | About what |
|---|---|
| Administrators | each new request and renewal request; which were approved automatically, or why not; each activation and release (can be turned off); once a day, which customers were reminded about expiry |
| The requester | the decision, with the reason for a rejection |
| The organization's members | licenses issued to it, renewals (with the new activation codes to install), and expiry reminders 60, 30 and 7 days before and when the grace period starts |

**REST API** (`/api/v1`): everything above as JSON, for scripts and automation. A user creates a token on the
portal's **API tokens** page (shown once, stored hashed, revocable, expiring after 30 days, 90 days, a year or never)
and sends it as `Authorization: Bearer pdil_...`. A customer's token acts as that customer; an administrator can
give a token administrator rights for `/api/v1/admin/**`.

| Call | |
|---|---|
| `GET /api/v1/licenses`, `/licenses/{id}` | the organization's licenses; one with its key and activations |
| `POST /api/v1/requests`, `/licenses/{id}/renewal` | a license or renewal request; the answer says whether it was approved at once |
| `POST /api/v1/activations` | `{"request": "HELIXREQ-...", "machine"}`: the activation code |
| `POST /api/v1/licenses/{id}/release` | `{"releaseCode": "HELIXREL-..."}` |
| `GET /api/v1/admin/requests` | pending requests, each with what auto-approval made of it |
| `POST /api/v1/admin/requests/{id}/approve`, `/reject` | decide a request |
| `GET /api/v1/admin/licenses?expiringWithinDays=30` | licenses expiring soon (and those in their grace period) |
| `POST /api/v1/admin/licenses/{id}/renew`, `/release` | renew; release without a code |
| `GET /api/v1/admin/events?since=<time>` | the activity log since a time, oldest first, for polling |

A script that activates the machine it runs on:
```
./license.sh request "$LICENSE_KEY" --out activation-request.txt
jq -n --rawfile r activation-request.txt --arg m "$(hostname)" '{request: $r, machine: $m}' \
  | curl -sf -H "Authorization: Bearer $PDI_TOKEN" -H 'Content-Type: application/json' -d @- \
      https://licensing.example.com/api/v1/activations \
  | jq -r .code > activation.helixact
./license.sh install activation.helixact
```

**Auto-approval** (off by default): a request is approved the moment it's sent when every rule holds, and otherwise
waits for an administrator, whose email and request page list the rules that held it back. The rules:
- the requester's email address is verified by the identity provider;
- the edition is one of a list (default `ce`), and the term one of a list (default `1y`; renewals too);
- the requester belongs to an organization that would then hold at most N unexpired licenses (default 3). New
  customers only with a separate switch, and then only at a company email domain no organization has;
- at most N requests from them in 24 hours (default 3), and none rejected in the last 30 days;
- the purpose is more than a few words;
- renewals of the requester's own organization's licenses qualify too (can be turned off).

The approver is recorded as *Auto-approval* in the activity log and on the request.

The portal's `README.md` lists every API call and setting; `deploy/README.md` puts it on one VM (Oracle Cloud,
AWS or Azure) with HTTPS, Keycloak or Entra External ID, email and nightly backups.

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
- One activation per license key. An instance limit counts PDI processes per license (above), but only through a
  shared folder, which the customer controls. A license server that enforces it (tier 3 of
  [instance-limits.md](instance-limits.md)) and per-execution checks are not implemented.
