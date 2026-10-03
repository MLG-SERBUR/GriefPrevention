package com.griefprevention.protection;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.ClaimsMode;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.Messages;
import me.ryanhamshire.GriefPrevention.PlayerData;
import me.ryanhamshire.GriefPrevention.events.PreventBlockBreakEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A utility used to simplify various protection-related checks.
 */
public final class ProtectionHelper
{

    private ProtectionHelper() {}

    /**
     * Blocks a projectile can break without an {@code EntityChangeBlockEvent}.
     * Single home so new MC blocks need one edit here, not one per handler.
     */
    public static final Set<Material> PROJECTILE_BREAKABLE_BLOCKS = Set.copyOf(EnumSet.of(
            Material.CHORUS_FLOWER,
            Material.DECORATED_POT));

    /**
     * Result of {@link #checkClaimedAction}: denial plus the claim that denied,
     * so callers with custom messages (owner name) keep them without looking up the claim twice.
     */
    public record ClaimDecision(@Nullable Supplier<String> denial, @Nullable Claim claim)
    {
        public boolean allowed()
        {
            return denial == null;
        }
    }

    /**
     * Check whether a dispenser (or other block source) sits in the same claim.
     * Moved from EntityEventHandler so all guards share one funnel.
     */
    public static boolean isBlockSourceInClaim(@Nullable ProjectileSource projectileSource, @Nullable Claim claim)
    {
        return projectileSource instanceof BlockProjectileSource &&
                GriefPrevention.instance.dataStore.getClaimAt(((BlockProjectileSource) projectileSource).getBlock().getLocation(), false, claim) == claim;
    }

    /**
     * Resolve the ultimate source behind a causing entity: the attacking player,
     * a projectile's shooter, a block source, or null when unknown.
     */
    public static @Nullable ProjectileSource resolveSource(@Nullable Entity cause, @Nullable Player attacker)
    {
        if (attacker != null) return attacker;
        if (cause instanceof Projectile projectile) return projectile.getShooter();
        if (cause instanceof ProjectileSource source) return source;
        return null;
    }

    /**
     * Single permission check for claimed actions caused by players, dispensers, or mobs.
     * Owns the world gate, claim lookup, same-claim dispenser allow, and player delegation.
     * Guards only resolve (source, target, permission); all claim logic lives here.
     * Wilderness allows, matching the damage/hanging/projectile-hit guards
     * (block-change keeps its own creative gate and does not use this).
     *
     * @param source the cause (player, dispenser block source, mob, or null)
     * @param target the harmed block or entity location
     * @param permission the required permission
     * @param trigger the triggering event, if any
     * @return the decision; non-player denials carry no message
     */
    public static @NotNull ClaimDecision checkClaimedAction(
            @Nullable ProjectileSource source,
            @NotNull Location target,
            @NotNull ClaimPermission permission,
            @Nullable Event trigger)
    {
        World world = target.getWorld();
        if (world == null || !GriefPrevention.instance.claimsEnabledForWorld(world))
            return new ClaimDecision(null, null);

        if (source instanceof Player player)
        {
            Supplier<String> denial = checkPermission(player, target, permission, trigger);
            // Extra lookup only on deny (rare) so custom messages keep the owner name.
            Claim deniedClaim = denial == null ? null : GriefPrevention.instance.dataStore.getClaimAt(target, false, null);
            return new ClaimDecision(denial, deniedClaim);
        }

        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(target, false, null);
        if (claim == null) return new ClaimDecision(null, null);

        if (isBlockSourceInClaim(source, claim)) return new ClaimDecision(null, claim);

        return new ClaimDecision(() -> "", claim);
    }

    /**
     * Check the {@link ClaimPermission} state for a {@link Player} at a particular {@link Location}.
     *
     * <p>This respects ignoring claims, wilderness rules, etc.</p>
     *
     * @param player the person performing the action
     * @param location the affected {@link Location}
     * @param permission the required permission
     * @param trigger the triggering {@link Event}, if any
     * @return the denial message supplier, or {@code null} if the action is not denied
     */
    public static @Nullable Supplier<String> checkPermission(
            @NotNull Player player,
            @NotNull Location location,
            @NotNull ClaimPermission permission,
            @Nullable Event trigger)
    {
        World world = location.getWorld();
        if (world == null || !GriefPrevention.instance.claimsEnabledForWorld(world)) return null;

        PlayerData playerData = GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId());

        // Administrators ignoring claims always have permission.
        if (playerData.ignoreClaims) return null;

        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, playerData.lastClaim);


        // If there is no claim here, use wilderness rules.
        if (claim == null)
        {
            ClaimsMode mode = GriefPrevention.instance.config_claims_worldModes.get(world);
            if (mode == ClaimsMode.Creative || mode == ClaimsMode.SurvivalRequiringClaims)
            {
                // Allow placing chest if it would create an automatic claim.
                if (trigger instanceof BlockPlaceEvent placeEvent
                        && placeEvent.getBlock().getType() == Material.CHEST
                        && playerData.getClaims().isEmpty()
                        && GriefPrevention.instance.config_claims_automaticClaimsForNewPlayersRadius > -1)
                    return null;

                // If claims are required, provide relevant information.
                return () ->
                {
                    String reason = GriefPrevention.instance.dataStore.getMessage(Messages.NoBuildOutsideClaims);
                    if (player.hasPermission("griefprevention.ignoreclaims"))
                        reason += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                    reason += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.CreativeBasicsVideo2, DataStore.CREATIVE_VIDEO_URL);
                    return reason;
                };
            }

            // If claims are not required, then the player has permission.
            return null;
        }

        // Update cached claim.
        playerData.lastClaim = claim;

        // Apply claim rules.
        Supplier<String> cancel = claim.checkPermission(player, permission, trigger);

        // Apply additional specific rules.
        if (cancel != null && trigger instanceof BlockBreakEvent breakEvent)
        {
            PreventBlockBreakEvent preventionEvent = new PreventBlockBreakEvent(breakEvent);
            Bukkit.getPluginManager().callEvent(preventionEvent);
            if (preventionEvent.isCancelled())
            {
                cancel = null;
            }
        }

        return cancel;
    }

}
