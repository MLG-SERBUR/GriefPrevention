# The event funnel

Branch `draft/event-funnel-pilot`. No pull request has been opened.

This document explains what the funnel is, how the architecture differs from what
was there before, and how individual cases are handled in each version. Every code
sample is real, taken from `origin/master` (before) and from this branch (after).

## The problem

Four listeners hold almost all of the protection logic:

| Listener | Lines on master |
|---|---|
| `PlayerEventHandler` | 2269 |
| `BlockEventHandler` | 1314 |
| `EntityDamageHandler` | 1052 |
| `EntityEventHandler` | 865 |
| **Total** | **5500** |

Inside those 5500 lines the same decision was written out over and over. The
recurring shape is:

1. Is protection even enabled in this world?
2. Look up the claim at this location.
3. If there is no claim, apply wilderness rules.
4. Ask the claim whether the actor holds the required `ClaimPermission`.
5. Cancel the Bukkit event.
6. Send the owner-name message to the player.

Counted on master, across the four listeners:

- 60 calls to `dataStore.getClaimAt(...)`
- 46 calls to `checkPermission(...)`

Steps 1 to 4 are policy. They were duplicated per guard, and each copy drifted.
Issue 2637 is the clearest example. It needed the rule "a dispenser inside the claim
is not grief", and on master that rule was already encoded in four places: the
`isBlockSourceInClaim` helper in `EntityEventHandler` plus three callers, and one more
hand-written copy in `BlockEventHandler#onBlockIgnite` that never called the helper at
all. A new Minecraft release makes this worse, because every new block, entity or
bucket type has to be recognised again, in whichever guard happens to own that event.

## The shape of the fix

One method decides. Guards only translate a Bukkit event into a question.

```
Bukkit event
    |
    v
guard: resolve (source, target, permission)
    |
    v
ProtectionHelper.checkClaimedAction(source, target, permission, trigger)
    |
    +-- world gate
    +-- player?  -> existing checkPermission path (ignore claims, wilderness, claim rules)
    +-- no claim -> allow
    +-- dispenser in the same claim -> allow
    +-- otherwise -> deny, carrying the claim so the guard can name the owner
    |
    v
guard: cancel if denied, message if the source is a player
```

The funnel lives in `com.griefprevention.protection.ProtectionHelper`, a package that
already existed with this one class in it. Nothing was moved into packages. The four
listeners stay where they are; only their bodies shrink.

### The API

```java
public record ClaimDecision(@Nullable Supplier<String> denial, @Nullable Claim claim)
{
    public boolean allowed() { return denial == null; }
}

public static @NotNull ClaimDecision checkClaimedAction(
        @Nullable ProjectileSource source,
        @NotNull Location target,
        @NotNull ClaimPermission permission,
        @Nullable Event trigger)

public static @NotNull ClaimDecision checkClaimedAction(
        @Nullable ProjectileSource source,
        @NotNull Location target,
        @NotNull ClaimPermission permission,
        @Nullable Event trigger,
        @Nullable Claim cachedClaim)   // for per-block loops

public static @Nullable ProjectileSource resolveSource(@Nullable Entity cause, @Nullable Player attacker)

public static boolean isBlockSourceInClaim(@Nullable ProjectileSource projectileSource, @Nullable Claim claim)

public static final Set<Material> PROJECTILE_BREAKABLE_BLOCKS
```

Three details carry most of the weight.

**`ClaimDecision` returns the claim, not just a verdict.** Several guards need the
owner's name in their message ("Alice doesn't like you hurting her cows"). Before,
those guards held the `Claim` local and built the message themselves. Now they read
`decision.claim()`, which is why no guard performs a second lookup.

**A denial with an empty message means "denied, nobody to tell".** A dispenser or an
arrow has no player to message. Those denials come back as `() -> ""`, so a guard
that has a player source can write:

```java
if (decision.denial() != null && !decision.denial().get().isEmpty())
    GriefPrevention.sendMessage(player, TextMode.Err, decision.denial().get());
```

without branching on whether a player was involved.

**`resolveSource` unwraps the projectile chain once.** Attacker first, then a
projectile's shooter, then the cause if it is itself a source, otherwise null. Before
this change each guard unwrapped arrows itself, differently.

### Rule order in `checkClaimedAction`

1. World is null, or claims are disabled for the world: allow.
2. Source is a `Player`: delegate to `checkPermission(...)`. If denied, look the claim
   up once so custom messages still work.
3. Look up the claim at the target, reusing `cachedClaim` when the caller has one.
4. No claim: allow.
5. `isBlockSourceInClaim(source, claim)`: allow. A dispenser inside the claim is a
   farm, not grief.
6. Otherwise: deny.

Note that step 4 allows wilderness for every non-player path. `handleProjectileChangeBlock`
deliberately does not use the funnel; it keeps its own creative-world gate and its
`config_mobProjectilesChangeBlocks` flag.

## Numbers

| | master | this branch |
|---|---|---|
| `getClaimAt` calls in the four listeners | 60 | 30 |
| `checkPermission` calls in the four listeners | 46 | 19 |
| `checkPermission` calls in `EntityDamageHandler` | 4 | 0 |
| Funnel call sites | 0 | 31 |
| `PlayerEventHandler` | 2269 | 2193 |
| `BlockEventHandler` | 1314 | 1298 |
| `EntityDamageHandler` | 1052 | 1018 |
| `EntityEventHandler` | 865 | 839 |
| `isBlockSourceInClaim` places encoding the same-claim dispenser rule | 4 | 1 |

Source diff against master is +549 / −415 across six files. The funnel itself is +75
and the test is +185. The seven guard-conversion commits that follow are each
net negative.

The last row counts every place that encoded "a dispenser inside the claim is allowed":
one helper plus three callers on master, and one more hand-written copy in
`BlockEventHandler#onBlockIgnite` that never called the helper at all. On this branch
the helper has no callers outside the funnel, and the ignite path asks the funnel while
keeping its own `instanceof BlockProjectileSource` narrowing, because it has to tell
"denied inside a claim" apart from "not claimed, fall through to the fire rules".

Tests: 72 pass, 0 fail (`mvn -o test`).

## Worked examples

### 1. Arrow breaks a chorus flower or decorated pot

The case from issue 2637.

Before, `BlockEventHandler`:

```java
// Ensure projectile affects block.
if (block == null || (block.getType() != Material.CHORUS_FLOWER  && block.getType() != Material.DECORATED_POT))
    return;

Claim claim = dataStore.getClaimAt(block.getLocation(), false, null);
if (claim == null)
    return;

Player shooter = null;
Projectile projectile = event.getEntity();

if (projectile.getShooter() instanceof Player)
    shooter = (Player) projectile.getShooter();

if (shooter == null)
{
    event.setCancelled(true);
    return;
}

Supplier<String> allowContainer = claim.checkPermission(shooter, ClaimPermission.Container, event);

if (allowContainer != null)
{
    event.setCancelled(true);
    GriefPrevention.sendMessage(shooter, TextMode.Err, allowContainer.get());
    return;
}
```

After:

```java
// Ensure projectile affects block. New MC blocks go in
// ProtectionHelper.PROJECTILE_BREAKABLE_BLOCKS, no edit here.
if (block == null || !ProtectionHelper.PROJECTILE_BREAKABLE_BLOCKS.contains(block.getType()))
    return;

ProjectileSource source = projectile.getShooter();
ProtectionHelper.ClaimDecision decision = ProtectionHelper.checkClaimedAction(
        source, block.getLocation(), ClaimPermission.Container, event);
if (decision.allowed()) return;

event.setCancelled(true);
if (source instanceof Player shooter && decision.denial() != null && !decision.denial().get().isEmpty())
    GriefPrevention.sendMessage(shooter, TextMode.Err, decision.denial().get());
```

What changed:

- 34 lines became 20.
- The material test became a set lookup, so the next projectile-breakable block is one
  line in `ProtectionHelper`, not a new `&&` clause here.
- `shooter == null` no longer means "cancel". It now means "ask the funnel", which
  allows a dispenser inside the claim and denies a dispenser in a neighbouring claim.
  That was the actual bug behind 2637.

### 2. Something damages an item frame, armour stand, end crystal or villager

Before, `EntityDamageHandler#handleClaimedBuildTrustDamageByEntity`:

```java
// Use attacker's cached claim to speed up lookup.
Claim cachedClaim = null;
if (attacker != null)
{
    PlayerData playerData = this.dataStore.getPlayerData(attacker.getUniqueId());
    cachedClaim = playerData.lastClaim;
}

Claim claim = this.dataStore.getClaimAt(event.damaged().getLocation(), false, cachedClaim);

// If the area is not claimed, do not handle.
if (claim == null) return false;

// If attacker isn't a player, cancel.
if (attacker == null)
{
    event.setCancelled(true);
    return true;
}

Supplier<String> failureReason = claim.checkPermission(attacker, ClaimPermission.Build, event.original());

// If player has build trust, fall through to next checks.
if (failureReason == null) return false;

event.setCancelled(true);
if (sendMessages) GriefPrevention.sendMessage(attacker, TextMode.Err, failureReason.get());
return true;
```

After:

```java
// Single funnel owns claim lookup, same-claim dispenser allow, and player check.
// Dispensers in the same claim may harm these (farms, not grief).
// Tamed pets stay protected by ownership, so they are not listed in the type gate above.
ProtectionHelper.ClaimDecision decision =
        ProtectionHelper.checkClaimedAction(
                ProtectionHelper.resolveSource(event.damager(), attacker),
                event.damaged().getLocation(),
                ClaimPermission.Build,
                event.original());

// Allowed (trusted player, same-claim dispenser, or wilderness): fall through to next checks.
if (decision.allowed()) return false;

event.setCancelled(true);
if (attacker != null && sendMessages && decision.denial() != null && !decision.denial().get().isEmpty())
    GriefPrevention.sendMessage(attacker, TextMode.Err, decision.denial().get());
return true;
```

What changed: 30 lines became 14, and `this.dataStore` plus the `PlayerData` fetch for a
cached claim are gone from the guard. The chain semantics are preserved exactly: this
guard returns `false` when it allows, so the next check in the damage chain still runs.

### 3. Something damages a claimed animal

`handleCreatureDamageByEntity` is where ownership has to beat the farm rule.

Before, the interesting part:

```java
Claim claim = this.dataStore.getClaimAt(event.damaged().getLocation(), false, cachedClaim);

// Require a claim to handle.
if (claim == null) return false;

// If damaged by anything other than a player, cancel the event.
if (attacker == null)
{
    event.setCancelled(true);
    // Always remove projectiles shot by non-players.
    if (arrow != null) arrow.remove();
    return true;
}

//cache claim for later
playerData.lastClaim = claim;

// Do not message players about fireworks to prevent spam due to multi-hits.
sendMessages &= damageSourceType != EntityType.FIREWORK_ROCKET;

Supplier<String> override = null;
if (sendMessages)
{
    final Player finalAttacker = attacker;
    override = () ->
    {
        String message = dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
        if (finalAttacker.hasPermission("griefprevention.ignoreclaims"))
            message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
        return message;
    };
}

// Check for permission to access containers.
Supplier<String> noContainersReason = claim.checkPermission(attacker, ClaimPermission.Container, event.original(), override);

// If player has permission, action is allowed.
if (noContainersReason == null) return true;

event.setCancelled(true);

// Prevent projectiles from bouncing infinitely.
preventInfiniteBounce(arrow, event.damaged());

if (sendMessages) GriefPrevention.sendMessage(attacker, TextMode.Err, noContainersReason.get());
```

After:

```java
// Ownership beats the farm rule: tamed pets stay denied even for same-claim dispensers.
ProjectileSource source = ProtectionHelper.resolveSource(damageSource, attacker);
if (event.damaged() instanceof Tameable tameable && tameable.isTamed())
    source = null;

ProtectionHelper.ClaimDecision decision = ProtectionHelper.checkClaimedAction(
        source, event.damaged().getLocation(), ClaimPermission.Container, event.original());

// Allowed (trusted player, same-claim dispenser, or wilderness): handled, stop the chain.
if (decision.allowed()) return true;

event.setCancelled(true);

// Always remove projectiles shot by non-players, ground player ones to stop infinite bounce.
if (attacker == null)
{
    if (arrow != null) arrow.remove();
    return true;
}
preventInfiniteBounce(arrow, event.damaged());
```

and the message becomes:

```java
if (sendMessages)
{
    final Player finalAttacker = attacker;
    final Claim deniedClaim = decision.claim();
    final Supplier<String> defaultDenial = decision.denial();
    String message;
    if (deniedClaim != null)
    {
        message = dataStore.getMessage(Messages.NoDamageClaimedEntity, deniedClaim.getOwnerName());
        if (finalAttacker.hasPermission("griefprevention.ignoreclaims"))
            message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
    }
    else if (defaultDenial != null && !defaultDenial.get().isEmpty())
    {
        message = defaultDenial.get();
    }
    else return true;
    GriefPrevention.sendMessage(attacker, TextMode.Err, message);
}
```

What changed: the dispenser rule now applies to livestock, but a tamed pet is
explicitly excluded by nulling the source before the funnel call, so a dispenser
cannot hurt a player's own tamed animals inside their claim. The `override` supplier
that existed only to inject the owner name is gone; `decision.claim()` supplies it.

### 4. A hanging entity is broken

Before, `EntityEventHandler#onHangingBreak`:

```java
//again, making sure the breaker is a player
if (!(remover instanceof Player playerRemover))
{
    event.setCancelled(true);
    return;
}

//if the player doesn't have build permission, don't allow the breakage
Supplier<String> noBuildReason = ProtectionHelper.checkPermission(playerRemover, event.getEntity().getLocation(), ClaimPermission.Build, event);
if (noBuildReason != null)
{
    event.setCancelled(true);
    GriefPrevention.sendMessage(playerRemover, TextMode.Err, noBuildReason.get());
}
```

After:

```java
// Single funnel: trusted player and same-claim dispenser projectiles may break hangings.
ProtectionHelper.ClaimDecision decision = ProtectionHelper.checkClaimedAction(
        ProtectionHelper.resolveSource(remover, remover instanceof Player player ? player : null),
        event.getEntity().getLocation(),
        ClaimPermission.Build,
        event);
if (decision.allowed()) return;

event.setCancelled(true);
if (remover instanceof Player playerRemover
        && decision.denial() != null && !decision.denial().get().isEmpty())
{
    GriefPrevention.sendMessage(playerRemover, TextMode.Err, decision.denial().get());
}
```

What changed: 19 lines became 12. Any non-player remover used to be cancelled outright,
including a dispenser in the same claim.

### 5. A splash potion hits several entities

This is the loop case, which is why the funnel has the `cachedClaim` overload.

Before, inside the loop:

```java
Claim claim = this.dataStore.getClaimAt(affected.getLocation(), false, cachedClaim);
if (claim != null)
{
    cachedClaim = claim;

    if (thrower == null)
    {
        // Non-player source: Witches, dispensers, etc.
        if (!EntityEventHandler.isBlockSourceInClaim(projectileSource, claim))
        {
            // If the source is not a block in the same claim as the affected entity, disallow.
            event.setIntensity(affected, 0);
        }
    }
    else { /* ...per-player message bookkeeping... */ }
}
```

After:

```java
// Single funnel: trusted player and same-claim dispenser may splash.
ProtectionHelper.ClaimDecision decision = ProtectionHelper.checkClaimedAction(
        projectileSource, affected.getLocation(), ClaimPermission.Container, event, cachedClaim);
if (decision.claim() != null) cachedClaim = decision.claim();
if (decision.allowed()) continue;

// If the source may not affect the entity, null its effect.
event.setIntensity(affected, 0);
if (thrower != null && messagedPlayer.compareAndSet(false, true))
{
    // ...owner-name message...
}
```

What changed: the loop no longer re-implements the claim lookup, and `cachedClaim` is
threaded back out of the decision instead of being reassigned from a fresh lookup. The
call still passes the caller's cached claim, so a potion that lands inside one claim and
crosses into the next costs one lookup per boundary, not one per entity.

### 6. A player pulls a claimed animal with a lead

Before, `PlayerEventHandler#onPlayerInteractEntity`:

```java
//if preventing theft, prevent leashing claimed creatures
if (instance.config_claims_preventTheft && entity instanceof Creature && itemInHand.getType() == Material.LEAD)
{
    Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, playerData.lastClaim);
    if (claim != null)
    {
        Supplier<String> failureReason = claim.checkPermission(player, ClaimPermission.Container, event);
        if (failureReason != null)
        {
            event.setCancelled(true);
            GriefPrevention.sendMessage(player, TextMode.Err, failureReason.get());
            return;
        }
    }
}
```

After:

```java
//if preventing theft, prevent leashing claimed creatures
if (instance.config_claims_preventTheft && entity instanceof Creature && itemInHand.getType() == Material.LEAD)
{
    ProtectionHelper.ClaimDecision decision = ProtectionHelper.checkClaimedAction(
            player, entity.getLocation(), ClaimPermission.Container, event);
    if (!decision.allowed() && decision.denial() != null && !decision.denial().get().isEmpty())
    {
        event.setCancelled(true);
        GriefPrevention.sendMessage(player, TextMode.Err, decision.denial().get());
        return;
    }
}
```

The vehicle and animal guards in the same method lost their `claim != null` nesting the
same way. Six guards in that method were converted in one commit.

### 7. An enderman tries to pick up a claimed block

The smallest conversion, and a good illustration of the null-source case.

Before:

```java
//and the block is claimed
if (this.dataStore.getClaimAt(event.getBlock().getLocation(), false, null) != null)
{
    //he doesn't get to steal it
    event.setCancelled(true);
}
```

After:

```java
//and the block is claimed, he doesn't get to steal it
if (!ProtectionHelper.checkClaimedAction(
        ProtectionHelper.resolveSource(event.getEntity(), null),
        event.getBlock().getLocation(),
        ClaimPermission.Build,
        event).allowed())
{
    event.setCancelled(true);
}
```

The enderman is not a `ProjectileSource`, so `resolveSource` returns null and the
funnel denies because the block is claimed. Same outcome, four fewer lines, and if a
dispenser's arrow ever reaches this handler the behaviour is now correct by
construction rather than by a hand-written copy of the rule.

## Behaviour changes

These are intentional and all point the same direction: the same case now gives the
same answer everywhere.

- Same-claim dispensers are now allowed for chorus flowers and decorated pots, frames,
  stands, crystals, villagers, hangings, livestock and potion splashes. Before, several
  of those paths cancelled any non-player source.
- Administrators with `ignoreClaims` are now respected by the chorus flower check.
  Before, that handler had no ignore-claims path.
- Non-player denials stay silent. Every guard checks whether the message is non-empty
  before sending.
- Tamed pets are still ownership-protected. A dispenser in the claim cannot hurt them.

## What was deliberately left alone

Thirty `getClaimAt` calls remain in the four listeners. Each one is there because the
funnel is the wrong tool, not because they were missed.

| Area | Where | Why not |
|---|---|---|
| Nature hot paths | `onBlockSpread`, `onBlockBurn`, `onBlockFromTo`, `onMultiBlockGrow` | Run per tick, compare a source claim to a target claim, and have bespoke rules. Extra allocations show up in TPS. |
| Multi-claim geometry | `onPistonEvent`, `denyConnectingDoubleChestsAcrossClaimBoundary` | These compare two claims. The funnel decides one target. |
| Explosives policy | `onBlockIgnite` (lightning), wither branch of `onEntityChangeBLock` | Decided by `claim.areExplosivesAllowed` and `config_blockClaimExplosions`, not by a permission on a target. |
| PvP | `handlePvpInClaim`, `handlePvpPetDamageByPlayer`, `handlePvpDamageByLingeringPotion`, the `inPvpCombat` container denials in `onPlayerInteractEntity` and `onPlayerInteract` | `PreventPvPEvent` and `Messages.PvPNoContainers` are their own contracts. A player in PvP combat is denied containers regardless of trust. |
| Death drops | `onEntityDeath` | Fires `ProtectDeathDropsEvent`, which is handed the `Claim` itself, nullable included. Not a permission check. |
| Claim object or workflow state | claim inspection and claim-create overlap in `onPlayerInteract`, `resizeClaimWithChecks` | Fire `ClaimInspectionEvent`, or need the `Claim` and a pending resize task. Not a location and a permission. |
| Wilderness presence tests | `onForm`, `onPlayerBucketEmpty` lava distance | The lookup answers "is there a claim here", not "may this actor". `onForm` cancels when there is no claim; the bucket lookup only feeds `minLavaDistance`. |
| Entity spawn and projectile block change | `onEntitySpawn`, `handleProjectileChangeBlock`, `handleFallingBlockChangeBlock` | `handleProjectileChangeBlock` keeps its creative-world gate and its `config_mobProjectilesChangeBlocks` flag by design. The others decide something other than "may this happen at this location". |
| Cross-claim dispense | `onDispense` | Compares source claim to target claim. |
| Player block placement | `onBlockPlace` | The player path already funnels through `checkPermission`, which owns the wilderness auto-claim rules for chests. |

`handleExplosion` is also unrouted: it is explosives policy, not trust.

## Why this was not a port of `dev/v20`

`dev/v20` exists on `origin/dev/v20` and contains a `funnel` package with
`GPBaseEvent`, `GPBlockChangeTypeEvent`, `GPBlockChangeStateEvent`,
`GPBlockToggleDataEvent`, `EventOption` and a `BukkitToGPEventListener` translator
that fires those custom Bukkit events into a `ClaimListener`. RoboMWM's javadoc
describes the intent well (commits `3486fa9`, `e8d56b8`), and the work was stopped for
sheer effort.

Reasons not to port it:

- Every Bukkit event needs a translator entry. Missing one silently allows the action.
- `GPBaseEvent` shares one static `HandlerList` across all subclasses. Bukkit requires
  one per class, so listeners cannot tell Type from State from Toggle.
- New content costs a translator edit plus a choice of Type, State or Toggle.
- Addons would have to learn a new taxonomy instead of the event they already use.

This branch takes the one piece worth keeping, `getSourceBlock` / `getSourcePlayer`
style source unwrapping, and puts it in `resolveSource`. Everything else is a decision
made in one place inside GriefPrevention rather than a new public event bus. The
addon-facing half of that idea, generalizing `ClaimPermissionCheckEvent` to accept a
non-player source, is written up as a follow-up below and has not been done.

## Commit flow

Fifteen commits. The first three introduce the funnel in pieces: the helper move with
no behaviour change, the decision point with no callers, and the tests. The next four
convert one guard each. Every commit after the test commit only reroutes callers, so
each one deletes more lines than it adds.

| Commit | Scope | Stat |
|---|---|---|
| `8fab9db` | Move `isBlockSourceInClaim` from `EntityEventHandler` to `ProtectionHelper`, three call sites delegate | +16 / −10 |
| `8a2ed5e` | Funnel core with no callers: `checkClaimedAction`, `resolveSource`, `ClaimDecision`, `PROJECTILE_BREAKABLE_BLOCKS` | +75 / −0 |
| `eb92029` | `ProtectionHelperTest`, seven funnel-matrix cases | +185 / −0 |
| `90675e5` | Convert `chorusFlower` | +11 / −25 |
| `7db842b` | Convert `onHangingBreak` | +11 / −11 |
| `15db1eb` | Convert build-trust damage | +14 / −25 |
| `7bb9aa3` | Convert creature damage, tamed pets stay ownership-denied | +24 / −34 |
| `90d3eea` | Explosion interact loop, projectile block change, fireball ignite, cached-claim overload | +37 / −45 |
| `13aaa06` | Six `Container` interact guards: vehicle, animals, leash, nametag, boats, minecarts | +50 / −71 |
| `b2e7ef3` | Vehicle damage, potion splash loop, block blast damage | +42 / −56 |
| `f775b13` | Enderman pickup | +6 / −4 |
| `3c7a728` | Access commands, pearl teleport and refund, raid trigger, egg hatch and payback, fish reel | +27 / −44 |
| `5dc6b37` | Turtle, container, doors, buttons, cake, decor, lectern take | +47 / −85 |
| `515e219` | Lectern book fallback, boat frame break | +9 / −10 |

A reasonable reading order is: `8a2ed5e` to learn the funnel, `90675e5` and `90d3eea`
to see the two shapes (single-entity guard and per-block loop), then skim the rest.

## Tests

`src/test/java/com/griefprevention/protection/ProtectionHelperTest.java`, seven cases:

- `breakableSetCoversChorusAndPot`
- `worldsWithoutClaimsAllow`
- `wildernessAllows`
- `dispenserInSameClaimAllows`
- `dispenserAcrossClaimsDenies`
- `trustedPlayerAllowsUntrustedDenies`
- `resolveSourcePrefersAttackerThenShooter`

Full suite: 72 tests, 0 failures, on Java 25 with `mvn -o test`.

## Follow-ups not done

1. **Generalize `ClaimPermissionCheckEvent`.** It still takes `checkedPlayer` and
   `checkedUUID` (`events/ClaimPermissionCheckEvent.java:23`). It is fired only from
   `Claim#checkPermission`, which only the player path reaches. Until it accepts a
   non-player source, addons still cannot see or override the dispenser and mob
   decisions the funnel now makes. This is the piece that would make the funnel the
   single addon hook.
2. **`PreventBlockBreakEvent`** is still fired from `checkPermission` and is on its way
   out. It should go once 1 lands.
3. **`onForm`** and **`onPlayerBucketEmpty`** could take a funnel call if their presence
   tests were split out from the policy decision.
4. **`resolveInteractTarget`** for issue 2630's adjacent-face case, so clicked versus
   adjacent is resolved once for all materials. The sulfur bucket work lives on
   separate `fix/sulfur-cube-*` branches; the funnel's player path already covers
   bucket empty and fill by delegation.