# Licensing many PDI processes at once (containers and cloud)

Design, stories and conditions of satisfaction for licenses that allow a number of **simultaneous PDI
processes**: "up to 300 at once", in containers, in the cloud or on ordinary machines. Activation and validation
stay offline: no PDI process ever contacts Hitachi Vantara.

Status, 2026-10-25:
- **Tiers 1 and 2** are implemented in `kettle-license` and `kettle-license-issuer` on 9.4-jdk21-cve and 10.1-jdk21-cve. The license portal
  (TR-5) is done too, in `pdi-license-portal` a28edc5.
- **Verified:** 125 unit tests, including real separate JVMs sharing a folder. Also end to end with Kitchen from PDI
  9.4.0.0-349 and the new jars, against a 2-process license:
  - two Kitchens ran and a third was refused (exit 7);
  - with `HELIX_LICENSE_WAIT` the third waited and ran;
  - under a soft limit the extra process ran and logged an error.
- **Tier 3** is a design only.

## The problem

Today a license key is activated once:
- **On an ordinary machine**, the activation is bound to that machine's fingerprint.
- **In a container**, it is *unbound*, because containers have no stable identity.

Every PDI process checks its activation by itself at startup (`KettleEnvironment.init()`): the signature, the
expiry and the machine. That check is offline and takes milliseconds, so 300 containers already validate
individually. What nothing does is **count** them. An unbound activation runs in 3 containers or in 3,000, and
the key doesn't say how many the customer bought.

A process that checks its license alone can't know how many others are running. Enforcing a number needs
something the processes share and can count against. That leads to three tiers, each building on the one before:

| Tier | What counts the processes | What stops process 301 | Effort for the customer | Can it be bypassed? |
|---|---|---|---|---|
| 1. Trust | nothing; the number is in the license | nothing; it's a contract term | none | yes, freely |
| 2. Deterrence | slot files in a folder all processes share | no free slot: refused, or a warning (the key says which) | mount one shared folder | yes, deliberately: give processes different folders |
| 3. Enforcement | a license server in the customer's network | the server hands out at most N leases | run one small service | not without patching PDI |

All three use the same license format. The key says how many processes are allowed and which tier counts them.
Because that is signed, a customer can't move to a cheaper tier by changing configuration.

## What counts as one process

One PDI JVM that initializes the Kettle environment: each Pan or Kitchen run, each Carte server, each Spoon, and
each Pentaho Server that embeds PDI. A Carte server running 50 transformations is one process.
`license status`, `license usage` and the other license commands don't count.

## The license format (all tiers)

The license payload gets a version 2 that adds an **instance limit**:

| Field | Size | Meaning |
|---|---|---|
| `maxInstances` | u32 | how many PDI processes may run at once with this license (at least 1) |
| `counting` | u8 | 0 = declared (tier 1), 1 = shared folder (tier 2), 2 = license server (tier 3) |
| `flags` | u8 | bit 0: soft limit (over the limit: start with a warning instead of refusing) |

- **A license without a limit is still written as version 1**, byte for byte. Every key and activation issued so
  far keeps its signature and still verifies.
- **A license with a limit is version 2.** A PDI build older than this change refuses it ("Unsupported license
  payload version: 2"), so customers with limited licenses need this build or later.
- **A counting method this PDI doesn't implement is refused**, with a message saying a newer PDI is needed. That
  is how tier-2 builds will treat tier-3 licenses.

The limit is part of the license, so activations carry it as well, and a renewal keeps it unless the issuer
changes it.

---

## Tier 1: Trust

The license states the number, and PDI shows it, but nothing counts. This suits customers who are audited by
contract, and gives sales a number to sell before any counting is in place.

**Behaviour:**
- `license status` prints `instances: up to 300 at once (declared, not counted)`.
- Pan, Kitchen and Carte log the same at startup.
- Nothing else changes: the activation works in any number of processes, exactly as today.

### Stories

**TR-1. Issue a license with an instance limit.** *Implemented.*
As Hitachi Vantara, I want to issue a license allowing N simultaneous PDI processes, so the number the customer
bought is part of the signed license.
- [x] `license-issuer issue ... --instances 300` issues a version-2 key with that limit.
  - `--counting declared | folder` chooses tier 1 or 2. The default is `folder`.
  - `--over-limit refuse | warn` chooses a hard or soft limit. The default is `refuse`.
  - `--counting server` is refused until tier 3 exists.
- [x] Without `--instances`, the key is version 1, byte for byte, as before.
- [x] The ledger's `issued` line notes the limit, for example `instances: up to 300 at once, counted in a shared folder (over
  the limit: refused)`.
- [x] `Issuer.issue(customer, edition, term, limit)` does the same for programs (the portal).
- [x] `--instances 0` and negative numbers are refused.

**TR-2. PDI verifies licenses with a limit, and still accepts every license without one.** *Implemented.*
As a customer, I want my existing license to keep working, and a license with a limit to work in this PDI.
- [x] Version-1 keys and activations, bound and unbound, verify exactly as before (tests use existing codes).
- [x] Version-2 keys and activations verify; any change to the limit breaks the signature.
- [x] A counting method this PDI doesn't know is refused, saying a newer PDI is needed.
- [x] A version-2 payload without a limit is rejected as malformed (it can't be issued).

**TR-3. Show the limit.** *Implemented.*
As a customer, I want to see how many processes my license allows, so I can plan capacity.
- [x] `license status` prints the limit and how it is counted.
- [x] Pan, Kitchen and Carte log it at startup (basic log level).
- [x] Licenses without a limit print nothing new.

**TR-4. Renewal keeps the limit, and the issuer can change it.** *Implemented.*
As Hitachi Vantara, I want a renewal to keep the customer's limit, and to raise or lower the limit when the
customer buys more or fewer processes, without waiting for the renewal date.
- [x] `license-issuer renew` keeps the limit. `renew --instances 500` (and `--counting`, `--over-limit`) changes it.
- [x] `license-issuer change --instances 500 <key>` changes the limit and keeps the expiry. The customer gets a
  new key and, for each active activation, a new activation code to install: no new request is needed.
- [x] The ledger records the change (`renewed` with the old and the new limit in the note).
- [x] `Issuer.renew(key, term, limit)` and `Issuer.change(key, limit)` for programs.

**TR-5. The portal issues and shows limits.** *Implemented (pdi-license-portal a28edc5, 5 new tests).*
As an administrator on the license portal, I want to set the limit when I approve a request or renew, and
customers want to see it.
- [x] The request form asks how many PDI processes will run at once (optional; empty means no limit).
- [x] Approve, direct issue and renewal let the administrator set the limit, the counting method and hard or
  soft. The license's page also changes it mid-term, keeping the expiry.
- [x] License pages, emails and `GET /api/v1/licenses/{id}` show the limit.
  `POST /api/v1/admin/licenses/{id}/instances` changes it.
- [x] Auto-approval never issues a limit above a configured maximum (`PORTAL_AUTO_APPROVE_MAX_INSTANCES`);
  above it, the request waits for an administrator, who sees why.
- [x] Flyway migration V5 adds the columns; existing licenses show "no limit".

---

## Tier 2: Deterrence (a shared folder)

Every PDI process takes one of N **slots** in a folder they all share, and keeps it while it runs. With every slot
taken, the next process is refused, or starts with a warning if the license has a soft limit.

### How a slot is taken

The folder is `HELIX_LICENSE_SLOTS` (environment variable) or `-Dhelix.license.slots` (system property). The
slots are `<folder>/<license id>/slot-0001` ... `slot-0300`.

1. **A process tries the slots in random order**, so 300 processes starting together don't all queue on slot 1.
   For each slot it asks the operating system for an exclusive lock on the file (`FileChannel.tryLock()`).
2. **A slot whose lock it gets is its slot**, unless the slot names another process with a fresh heartbeat (see
   the next section). It writes its record into the slot: process id, host, pid, start time and heartbeat time.
   A background thread refreshes the heartbeat every 20 seconds.
3. **The lock is held for the life of the JVM.** On a clean exit, a shutdown hook clears the record and releases
   the lock. If the process is killed (`kill -9`, OOM killer, the node dies), the operating system or the file
   server releases the lock, so a crash never leaks a slot.
   - On a local disk this happens at once.
   - On NFSv4 or SMB it happens when the client's lease expires, typically within 90 seconds.
4. **If every slot is taken:**
   - *Hard limit:* the process waits up to `HELIX_LICENSE_WAIT` seconds (default 0) for a slot to free up, trying
     again every few seconds, and then refuses to start. The message names the folder, the limit and
     `license usage`.
   - *Soft limit:* the process starts at once, logs an error that it is over the limit, and the start is recorded
     as over the limit.

### Folders where locks don't work across machines

Some shared filesystems accept locks without enforcing them between machines: NFS mounted with `nolock`,
`local_lock=all`, and some FUSE filesystems. Every process would then "get" slot 1, and the folder would count
nothing. The heartbeat catches this:
- A slot whose lock is free but whose record names *another* process with a heartbeat less than 60 seconds old is
  treated as taken.
- The refusal, or the soft-limit warning, then says that file locks in that folder don't seem to work across
  machines.

Three consequences:
- **A process that crashed on another machine keeps its slot for up to 60 seconds**, until its heartbeat goes
  stale. The other slots are unaffected.
- **A crash on the same host is recognised at once:** the record names this host and a pid that is gone, or this
  process's own pid (a container restarted, running as pid 1 again).
- **Hosts need clocks within about a minute of each other.** Every Kubernetes node and cloud VM keeps time with NTP.

### Where the folder is

| Activation | Folder |
|---|---|
| bound to a machine | `HELIX_LICENSE_SLOTS` if set, else `<Kettle folder>/.helix-slots` (local: it counts processes on that machine) |
| unbound (containers) | `HELIX_LICENSE_SLOTS`, which is **required**: without it the process refuses to start, saying so. A default inside a container would be private to that container and count nothing |

**In Kubernetes** the folder is a `ReadWriteMany` volume (EFS, Azure Files, Filestore, CephFS, NFS) mounted into
every PDI pod. **With Docker** it's a named volume or a bind mount of one host folder, shared by every container.

### Usage records

Each start and each clean stop appends a line to `<folder>/<license id>/usage-YYYY-MM.tsv`:
`time, start | stop | over, slot, process id, host, pid`.

One process at a time also acts as the sampler: whichever holds a lock on `sampler.lock`, handed on when it ends.
At each heartbeat it counts the records with a fresh heartbeat, including processes over a soft limit. It writes
`time, sample, count` when the number changes, and at least every 15 minutes. Crashed processes drop out of the
count once their heartbeat is stale, so no stop line is needed.

`license usage` prints:
- the processes running now: slot, host, pid, since and last heartbeat. They are read from the records without
  taking locks, so it never interferes with starting processes;
- for each month on record, the peak (the highest sample), the number of starts, and the starts over the limit.

The records are for the customer's own capacity planning and for the true-up conversation at renewal. They are
in the customer's folder, so they are evidence, not proof.

### What tier 2 does not stop

- **Giving processes different folders.** Each folder counts separately. The design makes this a deliberate act
  (configuring a different folder), not an accident.
- **Deleting the folder** frees every slot. Running processes keep their locks on the deleted files, and new
  processes create fresh ones. This is equally deliberate.
- **Patching PDI.** That is out of scope for every tier.

### Stories

**DT-1. Count processes in a shared folder.** *Implemented.*
As Hitachi Vantara, I want each PDI process of a folder-counted license to hold one of N slots while it runs, so
at most N run at once.
- [x] `KettleEnvironment.init()` (Pan, Kitchen, Carte, Spoon, Pentaho Server) takes a slot after the license
  check. License commands don't.
- [x] One slot per JVM: taking it twice is a no-op.
- [x] The slot is released on a clean exit (shutdown hook), and by the OS or file server when the process dies.
- [x] With N slots taken, process N+1 is refused with a message naming the limit, the folder and `license usage`.
- [x] The slot files of two licenses in one folder don't interfere (one subfolder per license id).
- [x] A test starts N+1 separate JVMs on one folder: N run and one is refused; after one exits, a new one starts;
  after one is killed (`kill -9`), a new one takes its slot.
- [x] Licenses without a limit, and declared limits, never touch a folder.

**DT-2. Configure the folder.** *Implemented.*
As a customer running PDI in containers, I want to point every container at one shared folder.
- [x] `HELIX_LICENSE_SLOTS` or `-Dhelix.license.slots` sets the folder; it's created if missing.
- [x] Bound activations default to `<Kettle folder>/.helix-slots`.
- [x] An unbound folder-counted activation without a folder refuses to start, naming `HELIX_LICENSE_SLOTS`.
- [x] An unusable folder (missing mount, no permission, read-only) refuses to start under a hard limit, naming the
  folder and the error. Under a soft limit it starts with a warning.

**DT-3. Hard or soft limit, and waiting for a slot.** *Implemented.*
As a customer with a scheduler that launches bursts of jobs, I want a job to wait for a slot instead of
failing, and as Hitachi Vantara I want to choose per customer whether going over the limit is refused.
- [x] `HELIX_LICENSE_WAIT=<seconds>` (or `-Dhelix.license.wait`) waits for a free slot, trying every 2 to 5
  seconds; 0 (the default) doesn't wait.
- [x] Soft limit: with no free slot, the process starts, logs an error, and its start is recorded as `over`.
- [x] Hard limit: with no free slot after the wait, `KettleEnvironment.init()` fails with a `LicenseException`,
  and Pan and Kitchen exit non-zero.

**DT-4. Detect folders where locks don't work across machines.** *Implemented.*
As Hitachi Vantara, I want a folder with broken locking to be noticed, not to quietly count nothing.
- [x] Each slot's record carries a heartbeat refreshed every 20 seconds.
- [x] A slot whose lock is free but whose record names another process with a heartbeat under 60 seconds old is
  treated as taken, unless that process is on this host and has ended.
- [x] The refusal, or the warning, says that locks in the folder don't seem to work across machines and names the
  likely causes (`nolock`, `local_lock`).

**DT-5. See usage.** *Implemented.*
As a customer, I want to see who holds slots now and how many processes ran at the peak, to plan capacity and
to prepare for renewal.
- [x] `license usage` lists the slots in use: slot, host, pid, since, last heartbeat. It takes no locks.
- [x] It prints, per month on record, the peak number of simultaneous processes (sampled), the starts and the starts
  over the limit.
- [x] Without a folder-counted license, it says there is nothing to count.

**DT-6. Many simultaneous starts are safe.** *Implemented.*
As a customer starting 300 processes at once, I want no start to fail because of another one.
- [x] The clock-check file (`.helix-license.seen`) is written to a temporary file and moved into place
  atomically, so concurrent starts can't leave it half-written.
- [x] Slot order is random, and a slot taken between "free" and "locked" is simply skipped.
- [x] A usage line is appended under a lock on the usage file, so lines don't interleave.

**DT-7. Documentation and examples.** *Implemented.*
As a customer, I want to know how to set this up in Docker and Kubernetes.
- [x] `docs/licensing/README.md` covers instance limits, the folder, the wait, `license usage`, Docker and
  Kubernetes (a `ReadWriteMany` volume) examples, and the limits of tier 2.

---

## Tier 3: Enforcement (a license server), design only

A small **license server** runs in the customer's network. It is activated offline, like any machine, and hands out
**leases**: at most N at once. Each PDI process needs a lease to start and keeps renewing it. Nothing contacts
Hitachi Vantara.

### Trust chain

The danger is a fake server that hands out unlimited leases. So the leases are verifiable offline:
1. **The server generates its own key pair at installation.** Its activation request (`HELIXSRVREQ-`) carries the
   machine fingerprint and the server's public key.
2. **Hitachi Vantara signs a server activation.** It contains the license (with `counting = server` and N), the
   server's fingerprint and the server's public key.
3. **Every lease is signed by the server's key.** PDI verifies the whole chain:
   - the embedded public key → the server activation (genuine, unexpired, `counting = server`);
   - the server key → the lease (genuine, for this process's own random id, unexpired).
   - A server that wasn't activated by Hitachi Vantara can't produce a lease PDI accepts. Nor can a server
     whose activation was for another license.

### Protocol

HTTPS inside the customer's network: the server's own certificate, or one the customer provides. PDI is given
`HELIX_LICENSE_SERVER=https://pdi-license.internal:8443`. An optional client token stops rogue clients from using
up seats.

| Call | Answer |
|---|---|
| `POST /v1/leases` `{licenseId, instance, host, pid}` | 201: the lease (about 10 minutes) and the server activation. 409 when all N are out, with `Retry-After` |
| `PUT /v1/leases/{instance}` | the renewed lease; PDI renews every 3 minutes |
| `DELETE /v1/leases/{instance}` | released (clean exit) |
| `GET /v1/usage` | leases now; peaks per day; refusals |

### When the server can't be reached: 24 hours

The server activation carries a signed **outage allowance**, 24 hours by default (set per customer when it is
issued). Within it, PDI keeps working; after it, new processes stop starting. Running jobs are never stopped
mid-run.

- **Running processes** keep running when renewing fails, logging an error at each failed renewal. When the
  allowance has passed since their last renewal:
  - Pan and Kitchen finish the job they are running;
  - Carte keeps running what it has, refuses new transformations and jobs, and accepts them again once the server
    answers.
- **New processes use the outage pass.** While the server is healthy, it keeps a signed **outage pass** in the
  shared slot folder (tier 2's `HELIX_LICENSE_SLOTS`) and refreshes it every minute. The pass says: license X, at
  most N processes, valid until 24 hours after the server last answered. A process that can't reach the server:
  1. verifies the pass with the same chain as a lease (embedded key → server activation → server key);
  2. takes a slot in the folder, exactly as in tier 2;
  3. starts, logging an error that it runs on the outage pass and until when.
  - So during an outage the limit is still counted, at tier-2 strength. Blocking the server on purpose gains
    nothing: the folder still counts, and after 24 hours nothing new starts.
  - After the pass has expired, a new process is refused. The message names the server, when it last answered, and
    the folder.
- **Without a shared folder** there is nothing to count against during an outage. A hard limit then refuses new
  processes at once, and a soft limit lets them start uncounted, with errors, for the 24 hours. Mounting a slot
  folder is therefore recommended for every server-counted deployment.
- **The server keeps its lease table on disk.** After a restart it honours the leases that haven't expired, so it
  never hands out N more.
- **Clocks:** the pass and leases carry the server's time. PDI allows 2 minutes of skew, and its clock-rollback check
  (`.helix-license.seen`) applies to passes too.

### Copies of the server

Nothing that stays fully offline can stop a copy outright: whatever a server stores, a customer can duplicate. The
design makes a copy short-lived and detectable, in three layers.

1. **The server is bound to where it runs.**
   - On a VM, the activation carries the machine fingerprint, as any bound activation.
   - In Kubernetes, it also carries the cluster's identity: the UID of the `kube-system` namespace, which is stable
     for the life of a cluster, read through the API with a read-only role.
   - A volume copied to another VM or cluster refuses to start without a new activation. A second copy in the same
     cluster isn't caught by this layer.
2. **One server per folder.** The server holds an exclusive lock on `server.lock` in the shared slot folder for as
   long as it runs, like tier 2's sampler. A second server for the same license and folder refuses to start, naming
   the host that holds the lock. Using another folder is a deliberate act, as in tier 2.
3. **Server activations expire, and renewing them continues a chain.** This is the layer that catches a copy for
   certain.
   - A server activation is valid for a **period**: 90 days by default, from 30 to 365 per customer.
   - **Renewing works like activation:** the server writes a renewal request, the customer sends it (portal,
     API or file) and installs the code that comes back. Air-gapped customers move one file per period.
     Connected customers automate it with a scheduled call to the portal API.
   - **Each request carries the head of a hash chain** of what the server did since its last renewal: monthly lease
     peaks and refusals, its restarts, and the previous head.
   - **The issuer accepts only a continuation of the chain it last recorded.** A copied server forks the chain.
     The first fork to renew wins, and the other can never renew: it stops issuing leases when its period ends,
     after the 14-day grace period. The ledger records the fork, for an audit conversation with the customer.
   - **The chain is also the usage report:** the peaks per month reach Hitachi Vantara with each renewal, signed by
     the server key.

**What is left:** a copy that runs for up to one period plus grace without ever renewing. It is caught at the next
renewal, and a shorter period shortens the window. Only an online check-in or hardware-held keys (TPM, cloud KMS)
would close it; both are out of scope, and the first breaks the offline requirement.

### Stories

**EN-1. Activate a license server.**
- [ ] `license-server init` generates the key pair (private key file mode 600) and prints a `HELIXSRVREQ-` request.
  The request carries the fingerprint, the cluster identity when in Kubernetes, the public key, and the chain's
  first head.
- [ ] `license-issuer activate` (and the portal) answer it with a server activation, signed. It carries
  `counting = server`, N, the outage allowance (default 24 hours), the period (default 90 days), the server key and
  the bindings.
- [ ] A server activation on another machine or cluster is refused, saying which binding differs.

**EN-2. Hand out at most N leases.**
- [ ] The server issues a lease only while fewer than N are unexpired. Otherwise it answers 409 with
  `Retry-After`.
- [ ] Leases are signed by the server key, name the process's random id, and expire 10 minutes after issue or
  renewal.
- [ ] The lease table survives a restart: no more than N leases are ever valid at once.
- [ ] Load test: 300 simultaneous `POST`s take under 2 seconds in total, and exactly N succeed.

**EN-3. PDI takes, renews and returns a lease.**
- [ ] With `counting = server`, `KettleEnvironment.init()` requires `HELIX_LICENSE_SERVER` and a lease. It
  verifies the chain from the embedded key to the lease, and checks that the lease names its own id.
- [ ] A daemon thread renews every 3 minutes. A clean exit releases the lease.
- [ ] With every lease taken, `HELIX_LICENSE_WAIT` waits as in tier 2. Then the start is refused (hard) or
  allowed with an error (soft).
- [ ] A lease made for another process, a replayed one, or one from a server not activated for this license is
  refused (tests for each).

**EN-4. 24 hours without the server.**
- [ ] Running processes keep running when renewing fails, logging an error each time. Past the allowance, Carte
  refuses new work until the server answers again; Pan and Kitchen finish their job.
- [ ] The server writes a signed outage pass into the slot folder every minute, valid until the allowance after its
  last answer.
- [ ] A new process that can't reach the server verifies the pass, takes a folder slot as in tier 2, and starts
  with an error naming the deadline. With an expired, forged or other license's pass, it is refused.
- [ ] Without a folder: refused at once under a hard limit; uncounted with errors for the allowance under a soft
  one.
- [ ] Test: stop the server, start N processes on the pass (N+1 is refused), move the clock past 24 hours, and a
  new start is refused.

**EN-5. Copies of the server are refused or caught.**
- [ ] The server holds `server.lock` in the slot folder. A second server for the license and folder refuses to
  start, naming the holder.
- [ ] The server activation is bound to the cluster identity in Kubernetes; a copy in another cluster refuses to
  start.
- [ ] Server activations expire after their period, with 14 days of grace. `license-server renew` writes the
  renewal request with the chain head and monthly peaks, and `license-server install` takes the new code.
- [ ] The issuer accepts a renewal only if it continues the last recorded chain. A fork is refused, recorded in
  the ledger (`forked`), and shown to administrators on the portal.
- [ ] Test: copy a server's volume, renew one copy, then try to renew the other: refused as a fork. The refused
  copy stops issuing leases at its period's end plus grace.

**EN-6. Packaged to run.**
- [ ] A Docker image and a Helm chart (a StatefulSet with a persistent volume, and a read-only role to read the
  `kube-system` namespace) for the server. A systemd unit for VMs.
- [ ] A health endpoint and Prometheus metrics: leases in use, refusals, renew failures, outage pass expiry,
  activation expiry.

**EN-7. The portal issues and renews server activations.**
- [ ] Administrators answer a `HELIXSRVREQ-` like an activation request, choosing the allowance and the period.
- [ ] Renewal requests are answered automatically when they continue the chain. Forks wait for an administrator.
- [ ] `POST /api/v1/server-activations/renewals` lets a connected server renew itself on a schedule.
- [ ] Customers see their servers, the next renewal date and the monthly peaks on the license page. Reminders go
  out 14 and 3 days before a server activation expires.

**EN-8. Security review before release.**
- [ ] TLS and client-token configuration are reviewed; leases and outage passes can't be replayed across
  processes, licenses or servers.
- [ ] Clock-skew tolerance between server and clients is set (2 minutes) and documented.

---

## Decisions

| # | Question | Proposed |
|---|---|---|
| 1 | Hard or soft limit by default | hard (`refuse`); soft per customer with `--over-limit warn` |
| 2 | Default wait for a slot or lease | 0 seconds; customers with schedulers set `HELIX_LICENSE_WAIT` |
| 3 | Does Spoon count? | yes: it's a PDI process like any other |
| 4 | Tier 3: starting during a server outage | decided 2026-10-25: 24 hours, new processes counted in the slot folder on a signed outage pass; after that, new processes refused and running ones finish |
| 5 | Tier 3: copies of the server volume | decided 2026-10-25: bound to VM or cluster, one server per folder, and server activations that expire (90 days by default) and renew as a hash chain, so a copy forks and is refused |
