# Funnel designs: dev/v20, this branch, and the roads not taken

Branch `draft/event-funnel-pilot`. Companion to `docs/funnel.md`, which covers what
this branch changed case by case. This document covers why it changed that way: what
`dev/v20` actually built, how the same situations flow through each design, and which
other designs were considered and rejected.

Every `dev/v20` sample is real code from `origin/dev/v20`. Every "this branch" sample
is real code from this branch.

## 1. What dev/v20 built

The v20 funnel has three parts: a translator, wrapper events, and one consumer.

### 1.1 The translator

`funnel/BukkitToGPEventListener.java` listens at `LOWEST` on concrete Bukkit events
and re-fires each one as a GriefPrevention wrapper event. The class javadoc is the
clearest statement of intent anywhere in the project:

> The "funnel" abstracts the many Bukkit events into a few high-level event wrappers.
> I.e., all events that cause blocks to be added or removed (BlockPlaceEvent,
> BlockBreakEvent, BlockFromToEvent, BlockExplodeEvent, EntityExplodeEvent, etc.) are
> fired as a "GPMutateTypeEvent". These GP-labeled events cancel the wrapped Bukkit
> event when canceled.
>
> This not only simplifies GP's event handling, but allows addons to undo or alter
> GP's behavior if desired.

Two different fire methods, and the difference matters:

```java
private boolean callEvent(GPBaseEvent event)
{
    Cancellable baseEvent = null;

    //GPevent.cancel = baseevent.isCancelled()
    if (event.getBaseEvent() instanceof Cancellable)
    {
        baseEvent = (Cancellable)event.getBaseEvent();
        event.setCancelled(baseEvent.isCancelled());
    }

    plugin.getServer().getPluginManager().callEvent(event);

    //baseevent.cancel = GPevent.isCancelled()
    if (baseEvent != null)
    {
        baseEvent.setCancelled(event.isCancelled());
    }

    return event.isCancelled();
}

private boolean callWithoutCancelingEvent(GPBaseEvent event)
{
    plugin.getServer().getPluginManager().callEvent(event);
    return event.isCancelled();
}
```

`callEvent` syncs cancellation in both directions: whatever the wrapper decides lands
on the Bukkit event. `callWithoutCancelingEvent` only reports the verdict and leaves
the Bukkit event alone. The translator uses the second one for explosions, where a
denial means "remove this block from the blast list" rather than "cancel the blast":

```java
@EventHandler(priority = LOWEST)
private void onEntityExplode(EntityExplodeEvent event)
{
    //Call an event for each block that's to-be-destroyed
    //Thus, the base event won't be canceled unless it's explicitly canceled
    List<Block> blocksToRemove = new ArrayList<>();
    for (Block block : event.blockList())
    {
        if (callWithoutCancelingEvent(new GPBlockChangeTypeEvent(event, event.getEntity(), block.getLocation(), block)))
            blocksToRemove.add(block);
    }
    event.blockList().removeAll(blocksToRemove);
}
```

That per-block pattern is the best piece of engineering in v20, and this branch keeps
its semantics: deny one block, keep the rest of the explosion.

### 1.2 The wrapper events

`GPBaseEvent` carries four things: the wrapped Bukkit event, a source, a location,
and a target. Source and target are typed as `Metadatable`, the loosest interface
that covers both blocks and entities:

```java
public GPBaseEvent(Event baseEvent, Metadatable source, Location location, Metadatable target)
```

plus an entity-oriented overload, `getSourcePlayer()`, `getSourceBlock()`, and an
options map (`Map<EventOption, Boolean>`) defaulting every unset option to `true`.

Three subclasses divide the world by GriefPrevention's trust model, not by Minecraft's:

- `GPBlockChangeTypeEvent` — type changes to or from air (place, break, frames,
  stands, crystals). Maps to build trust.
- `GPBlockChangeStateEvent` — state changes (chests, crop plant/uproot, redstone,
  item-frame rotation). Maps to container trust.
- `GPBlockToggleDataEvent` — two-state toggles (doors, levers, buttons). Maps to
  access trust.

The translator constructs the Type event twelve times. It constructs State and Toggle
zero times. Two thirds of the taxonomy was never wired up.

### 1.3 The consumer

`listener/ClaimListener.java` is the single decision point. The whole handler:

```java
@EventHandler(ignoreCancelled = true)
private void onClaimBuildBreak(GPBlockChangeTypeEvent event)
{
    Claim claim = claimRegistrar.getClaim(event.getLocation(), false, claimCache.get(event.getSource()));
    if (claim == null)
    {
        event.setCancelled(applyWildernessRules(event));
        return;
    }

    //Caused by player
    if (event.isPlayer())
    {
        event.setCancelled(!claim.hasPermission(event.getSourcePlayer(), ClaimPermission.BUILD));
        return;
    }

    //Allow if natural grief is enabled
    event.setCancelled(!claim.isNaturalGriefAllowed());

    if (event.getSourceBlock() != null)
    {
        switch (event.getSourceBlock().getType())
        {
            case FIRE:
                event.setCancelled(true);
        }
    }
}

private boolean applyWildernessRules(GPBlockChangeTypeEvent event)
{
    //fire spread & burn
    //explosions above sea level
    //etc.
    return false;
}
```

Note what is missing: `hasPermission` returns a bare boolean, so there is no denial
message path and no addon event. `applyWildernessRules` is a stub that returns
`false`, so everything unclaimed is allowed. The `claimCache` is a `HashMap` keyed by
source with entries removed only on `PlayerQuitEvent`, so a dispenser block source
accumulates a cache entry that is never evicted.

### 1.4 The same three situations in v20

**Player places a block in a claim.** `onBlockPlace` wraps the `BlockPlaceEvent` in a
Type event with the player as source. `ClaimListener` looks up the claim, sees
`isPlayer()`, and cancels unless `claim.hasPermission(player, BUILD)`. Clean, and the
reason the design is attractive: the translator is three lines and the decision is in
one place.

**Creeper explodes ten blocks, three inside a claim.** `onEntityExplode` fires ten
Type events, one per block, without touching the Bukkit event's cancelled flag. The
consumer denies three. The translator removes those three from the blast list. Seven
blocks break. Correct, and the pattern this branch copied.

**Fire spreads inside a claim.** `onFireSpread` wraps the spread in a Type event, and
on denial checks `gpEvent.getOption(EventOption.REMOVE_SOURCE_FIRE_BLOCK)` to decide
whether to extinguish the source fire block. The options map is v20's answer to
"the decision needs a side effect beyond cancel", which this branch does not have at
all: the funnel returns allow or deny, and any side effect stays in the guard.

### 1.5 What v20 got right

- One ingress at `LOWEST`, decision separated from translation. The split is correct;
  this branch keeps it.
- The per-block explosion pattern. Deny the block, not the blast.
- The options map admits that cancel is not always enough. Fire needs the source
  extinguished. This branch has no equivalent, which is a real gap, not a win.
- Source helpers. `getSourcePlayer()` and `getSourceBlock()` are the only v20 code
  worth keeping, and this branch's `resolveSource` is their descendant.

### 1.6 What is broken in v20

Each of these is verified against the source, not assumed.

- **One static `HandlerList` shared by every subclass.** `GPBaseEvent` declares
  `private static final HandlerList handlers` and no subclass overrides
  `getHandlerList()`. Bukkit requires one list per event class. A listener for the
  Type event and a listener for the State event receive each other's events. The
  taxonomy does not work as a dispatch mechanism.
- **State and Toggle are defined, never fired, never consumed.** Twelve translator
  call sites construct Type. Zero construct the other two. `ClaimListener` handles
  only Type. Container and access trust have no funnel path at all.
- **Coverage is a switch statement over 2017 content.** `onBlockLikeEntityDamage`
  handles `ITEM_FRAME`, `ARMOR_STAND`, `ENDER_CRYSTAL` and nothing else. A glow frame,
  an interaction entity, a decorated pot, a sulfur cube all fall through silently.
  Every new Minecraft release needs a translator edit to stay protected, and the
  failure mode is silent allow.
- **No shooter unwrapping.** The damager is passed raw as source. An arrow's shooter
  is never resolved, so dispenser-versus-player attribution does not exist.
- **Two cancel protocols.** `callEvent` versus `callWithoutCancelingEvent` is a
  per-call-site choice with opposite semantics. A translator author who picks the
  wrong one either cancels whole explosions for one protected block or lets a denied
  placement through. This branch has one protocol: the funnel never cancels anything
  itself, the guard always does.
- **The cache leaks.** `claimCache` entries for block sources are never removed.
  Only players quit.
- **No messages, no addon input.** Boolean verdicts only. An addon cannot learn why
  something was denied, and nothing fires for an addon to override.

Fixing all of that is a rewrite, not a repair. That is why v20 stayed a prototype.

## 2. The same three situations on this branch

The branch inverts v20's structure: no wrapper events, no second dispatch. The guard
stays on the Bukkit event and asks one static method for the verdict.

**Player places a block in a claim.** `onBlockPlace` is unchanged from master. The
player path delegates inside `checkClaimedAction` to the existing `checkPermission`,
which owns ignore-claims, wilderness modes, the chest auto-claim rule, and the
`PreventBlockBreakEvent` shim. One lookup, one message supplier, same as before.

**Arrow breaks a chorus flower.** `chorusFlower` resolves
`(projectile.getShooter(), block.getLocation(), Container)` and calls the funnel.
Same-claim dispenser: allowed, which is the behaviour fix. Cross-claim or
wilderness-adjacent: denied silently for the dispenser, messaged for the player.

**Creeper explodes ten blocks.** The explosion interact loop resolves per block and
passes its `cachedClaim` into the funnel overload, so a blast inside one claim costs
one lookup at the boundary, same as v20's per-block verdicts without v20's per-block
event objects.

**Fire spread** is the honest counterexample: this branch does not route it. v20's
options map handled "extinguish the source" as part of the decision. Here that side
effect would live in the guard, and no guard does it today.

## 3. Head to head

| | dev/v20 | This branch |
|---|---|---|
| Dispatch | Two: Bukkit event, then GP wrapper event through the plugin manager | One: the Bukkit event only |
| Decision point | `ClaimListener.onClaimBuildBreak`, the only decision method | `checkClaimedAction`, one method total |
| Translator cost per MC release | New content needs a new translator branch, else silent allow | New content needs a set entry or nothing (supertypes already cover it), else deny |
| Addon hook | Listen for GP wrapper events, override verdict | Nothing yet; `ClaimPermissionCheckEvent` is still player-only |
| Cancel semantics | Two protocols, chosen per call site | One: funnel never cancels, guard always cancels |
| Side effects beyond cancel | Options map (`REMOVE_SOURCE_FIRE_BLOCK`) | None; guards own side effects |
| Testability | Requires firing Bukkit events through a plugin manager | Funnel core is pure statics under JUnit and Mockito; guards are not tested |
| Silent-allow risk | Missed translator branch allows | Unknown source in a claim denies |
| Public API surface | Five new public event classes plus options enum, locked forever | One public static method family, no events |

The table is the whole argument. v20 optimises for the addon author's reading
experience at the cost of GriefPrevention's maintenance burden and a silent-allow
failure mode. This branch optimises for the opposite: less code to maintain, deny by
default, at the cost of leaving the addon story unfinished.

## 4. Designs that would have worked

### 4.1 Finish v20 properly

Fix the shared `HandlerList`, wire State and Toggle through the translator, resolve
shooters, replace the leak-prone cache, add denial reasons and an addon override
point. This is a coherent design and it would have worked. It wins outright in one
world: if GriefPrevention's product is its addon API and addons are first-class
consumers who prefer subscribing to `GPBlockChangeTypeEvent` over reading Bukkit
events. The price is permanent: a translator entry per Bukkit event per Minecraft
release, double dispatch on every protected action, and five public classes that can
never change shape. Given that the v20 rewrite died of sheer effort once already,
paying that price again for an API no addon has asked for is the wrong trade.

### 4.2 Extract only the preamble

Leave every guard where it is and collapse steps 1 to 4 of the old shape into one
boolean, e.g. `deny(source, target, permission)`. Smallest possible diff, no new
types, every call site stays readable. This would have worked and it is strictly
better than master. It keeps two duplications this branch removes: each guard still
looks the claim up for its owner-name message, and each guard still unwraps its own
projectiles. Reasonable if the goal were minimal churn. It is not where the deletion
is, so it was not chosen.

### 4.3 A guard registry

Turn each guard into a small class declaring which events it handles, register them in
a list, and dispatch each Bukkit event through the list. Better file organisation
than four giant listeners, and new content means a new small file instead of a new
branch in a giant method. Costs: every event pays an O(n) scan past uninterested
guards on the main thread, the resolve step still lives in each guard so nothing is
deleted, and the codebase gains a framework nobody asked for. Organisation without
deletion is the thing the Ponytail ladder forbids.

### 4.4 Two-phase cancel and uncancel

One listener at `LOWEST` cancels everything inside claims; a second listener at
`HIGH` un-cancels what is allowed. Tempting because the first listener is trivial.
Breaks the Bukkit contract: handlers with `ignoreCancelled = true` between the two
phases never run, other plugins observe a cancelled event that later is not, and
denial messages have no natural home. Worse than master. Listed here so nobody
proposes it again.

### 4.5 Policy objects keyed by permission

A `Map<ClaimPermission, ProtectionPolicy>` where each policy owns the rules for its
permission, possibly replaceable at runtime by addons. This only beats a static
method if policies need runtime swapping. They do not: the varying inputs are config
flags and claim data, both already parameters, not policy implementations. An
interface with one static implementation is indirection for its own sake.

## 5. What could beat this branch

Three concrete upgrades, none of them speculative.

### 5.1 Finish the addon half

`ClaimPermissionCheckEvent` still takes `checkedPlayer` and `checkedUUID`. It fires
only from `Claim#checkPermission`, which only the player path reaches. Until it
accepts a dispenser, mob, or null source, addons cannot see or override the
non-player decisions the funnel now makes. This is the documented follow-up and it is
the single highest-value change left: it completes the "less work for addons" goal
that motivated the funnel, and it lets `PreventBlockBreakEvent` finally be deleted.

### 5.2 Test the resolve step

Today the decide step is tested (seven cases in `ProtectionHelperTest`) and the
resolve step is not. But the resolve step is where Minecraft-update bugs live:
clicked versus adjacent block, shooter unwrapping, which permission a new entity
needs. Two options, cheapest first: Mockito event tests in the style of the existing
`BlockEventHandlerTest`, firing a mocked `ProjectileHitEvent` at a real handler
method and verifying cancel; or pure static resolve functions per guard that map an
event to `(source, target, permission)` with no Bukkit calls in between, tested
directly. Either one covers the exact failure mode v20 had — new content silently
unhandled — with a failing test instead of a griefing report.

### 5.3 Replace the empty-denial sentinel with a sealed result

Non-player denials currently come back as `() -> ""`, and every guard repeats the
`denial() != null && !denial().get().isEmpty()` dance to tell "denied, nobody to
tell" from "denied, message the player". A sealed type — `Allow`, `DenySilent`,
`DenyWithMessage` — says the same thing in the type system and deletes the dance
from all 31 call sites. Cost is a mechanical edit across those call sites, which is
why it was not done in the funnel commits. It is the natural next cleanup once the
call sites stop moving.

## 6. End state

This branch, plus 5.1, plus 5.3, plus resolve tests from 5.2, is the design to keep.
v20 stays a reference: read its javadoc for intent, read its translator for the
per-block explosion pattern, port nothing else.
