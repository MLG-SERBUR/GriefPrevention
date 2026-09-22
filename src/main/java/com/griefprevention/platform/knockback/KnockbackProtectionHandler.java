package com.griefprevention.platform.knockback;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.EntityDamageHandler;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import me.ryanhamshire.GriefPrevention.TextMode;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Abstract base class for player-caused knockback protection in claims.
 * Contains shared logic for both Paper and Spigot implementations.
 * <p>
 * Handles knockback from all player sources including melee attacks (spears),
 * projectiles (wind charges), and other mechanisms (shield blocks).
 * <p>
 * Subclasses provide the event listener method that extracts attacker/defender
 * from platform-specific events, then delegate to the protected handler methods.
 */
public abstract class KnockbackProtectionHandler implements Listener
{

    protected final DataStore dataStore;
    protected final GriefPrevention instance;

    protected KnockbackProtectionHandler(@NotNull DataStore dataStore, @NotNull GriefPrevention plugin)
    {
        this.dataStore = dataStore;
        this.instance = plugin;
    }

    /**
     * Handle player-caused knockback against other players. Prevents knocking players
     * around inside claims without access trust.
     *
     * @param event the knockback event
     * @param attacker the {@link Player} who caused the knockback
     * @param defender the {@link Player} being knocked back
     * @param <T> event type that extends Event and implements Cancellable
     */
    protected <T extends Event & Cancellable> void handleKnockbackPlayer(
            @NotNull T event,
            @NotNull Player attacker,
            @NotNull Player defender)
    {
        // Always allow self-knockback for mobility.
        if (attacker.equals(defender)) return;

        PlayerData defenderData = this.dataStore.getPlayerData(defender.getUniqueId());
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());

        if (attackerData.ignoreClaims) return;

        // Prevent knocking players around inside claims without access trust.
        Claim defenderClaim = this.dataStore.getClaimAt(defender.getLocation(), false, defenderData.lastClaim);
        if (defenderClaim != null)
        {
            defenderData.lastClaim = defenderClaim;

            // If the attacker lacks access trust, cancel the knockback.
            if (defenderClaim.checkPermission(attacker, ClaimPermission.Access, null) != null)
            {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Handle player-caused knockback against non-player entities. Prevents moving protected
     * entities out of claims.
     *
     * @param event the knockback event
     * @param attacker the {@link Player} who caused the knockback
     * @param entity the {@link Entity} being knocked back
     * @param <T> event type that extends Event and implements Cancellable
     */
    protected <T extends Event & Cancellable> void handleKnockbackEntity(
            @NotNull T event,
            @NotNull Player attacker,
            @NotNull Entity entity)
    {
        if (!instance.claimsEnabledForWorld(entity.getWorld())) return;

        // Determine protection type and required permission.
        ClaimPermission requiredPermission;
        if (entity instanceof ArmorStand || entity instanceof Hanging)
        {
            // These require build trust, matching handleClaimedBuildTrustDamageByEntity.
            requiredPermission = ClaimPermission.Build;
        }
        else if (entity instanceof Creature && instance.config_claims_preventTheft)
        {
            // Creatures require container trust, matching handleCreatureDamageByEntity,
            // but skip monsters - they are never protected.
            if (EntityDamageHandler.isHostile(entity)) return;
            requiredPermission = ClaimPermission.Container;
        }
        else
        {
            // Entity type not protected.
            return;
        }

        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());

        if (attackerData.ignoreClaims) return;

        Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, attackerData.lastClaim);

        if (claim == null) return;

        attackerData.lastClaim = claim;

        Supplier<String> noPermissionReason = claim.checkPermission(attacker, requiredPermission, event);
        if (noPermissionReason != null)
        {
            event.setCancelled(true);
            GriefPrevention.sendMessage(attacker, TextMode.Err, noPermissionReason.get());
        }
    }

}