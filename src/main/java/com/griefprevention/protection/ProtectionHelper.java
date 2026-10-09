package com.griefprevention.protection;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import me.ryanhamshire.GriefPrevention.events.PreventBlockBreakEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A utility used to simplify various protection-related checks.
 */
public final class ProtectionHelper
{

    private ProtectionHelper() {}

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
        if (world == null) return null;

        PlayerData playerData = GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId());

        // Administrators ignoring claims always have permission.
        if (playerData.ignoreClaims) return null;

        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, playerData.lastClaim);


        // Wilderness is fair game.
        if (claim == null) return null;

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
