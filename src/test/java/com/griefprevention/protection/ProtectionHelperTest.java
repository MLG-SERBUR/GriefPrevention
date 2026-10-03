package com.griefprevention.protection;

import com.griefprevention.test.ServerMocks;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProtectionHelperTest
{
    private DataStore dataStore;
    private World world;
    private Location target;
    private Event trigger;

    @BeforeAll
    static void beforeAll()
    {
        Server server = ServerMocks.newServer();
        doAnswer(invocation ->
        {
            Tag<?> tag = mock();
            doReturn(Set.of()).when(tag).getValues();
            return tag;
        }).when(server).getTag(notNull(), notNull(), notNull());
        Bukkit.setServer(server);
    }

    @AfterAll
    static void afterAll()
    {
        GriefPrevention.instance = null;
        ServerMocks.unsetBukkitServer();
    }

    @BeforeEach
    void beforeEach()
    {
        dataStore = mock(DataStore.class);
        GriefPrevention plugin = mock(GriefPrevention.class);
        plugin.dataStore = dataStore;
        GriefPrevention.instance = plugin;

        world = mock(World.class);
        when(plugin.claimsEnabledForWorld(world)).thenReturn(true);

        target = mock(Location.class);
        when(target.getWorld()).thenReturn(world);
        trigger = mock(Event.class);
    }

    @Test
    void breakableSetCoversChorusAndPot()
    {
        assertTrue(ProtectionHelper.PROJECTILE_BREAKABLE_BLOCKS.contains(Material.CHORUS_FLOWER));
        assertTrue(ProtectionHelper.PROJECTILE_BREAKABLE_BLOCKS.contains(Material.DECORATED_POT));
    }

    @Test
    void worldsWithoutClaimsAllow()
    {
        when(target.getWorld()).thenReturn(null);
        assertTrue(ProtectionHelper.checkClaimedAction(null, target, ClaimPermission.Build, trigger).allowed());
    }

    @Test
    void wildernessAllows()
    {
        when(dataStore.getClaimAt(eq(target), eq(false), eq(null))).thenReturn(null);
        assertTrue(ProtectionHelper.checkClaimedAction(null, target, ClaimPermission.Build, trigger).allowed());
    }

    @Test
    void dispenserInSameClaimAllows()
    {
        Claim claim = mock(Claim.class);
        Location dispenserLocation = mock(Location.class);
        Block dispenserBlock = mock(Block.class);
        when(dispenserBlock.getLocation()).thenReturn(dispenserLocation);
        BlockProjectileSource source = mock(BlockProjectileSource.class);
        when(source.getBlock()).thenReturn(dispenserBlock);

        when(dataStore.getClaimAt(eq(target), eq(false), eq(null))).thenReturn(claim);
        when(dataStore.getClaimAt(eq(dispenserLocation), eq(false), eq(claim))).thenReturn(claim);

        ProtectionHelper.ClaimDecision decision =
                ProtectionHelper.checkClaimedAction(source, target, ClaimPermission.Build, trigger);
        assertTrue(decision.allowed());
        assertSame(claim, decision.claim());
    }

    @Test
    void dispenserAcrossClaimsDenies()
    {
        Claim claim = mock(Claim.class);
        Claim other = mock(Claim.class);
        Location dispenserLocation = mock(Location.class);
        Block dispenserBlock = mock(Block.class);
        when(dispenserBlock.getLocation()).thenReturn(dispenserLocation);
        BlockProjectileSource source = mock(BlockProjectileSource.class);
        when(source.getBlock()).thenReturn(dispenserBlock);

        when(dataStore.getClaimAt(eq(target), eq(false), eq(null))).thenReturn(claim);
        when(dataStore.getClaimAt(eq(dispenserLocation), eq(false), eq(claim))).thenReturn(other);

        ProtectionHelper.ClaimDecision decision =
                ProtectionHelper.checkClaimedAction(source, target, ClaimPermission.Build, trigger);
        assertFalse(decision.allowed());
        assertSame(claim, decision.claim());
    }

    @Test
    void trustedPlayerAllowsUntrustedDenies()
    {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(dataStore.getPlayerData(playerId)).thenReturn(new PlayerData());

        Claim claim = mock(Claim.class);
        when(dataStore.getClaimAt(eq(target), eq(false), any())).thenReturn(claim);

        when(claim.checkPermission(eq(player), eq(ClaimPermission.Build), eq(trigger))).thenReturn(null);
        assertTrue(ProtectionHelper.checkClaimedAction(player, target, ClaimPermission.Build, trigger).allowed());

        when(claim.checkPermission(eq(player), eq(ClaimPermission.Build), eq(trigger))).thenReturn(() -> "no");
        ProtectionHelper.ClaimDecision denied =
                ProtectionHelper.checkClaimedAction(player, target, ClaimPermission.Build, trigger);
        assertFalse(denied.allowed());
        assertSame(claim, denied.claim());
    }

    @Test
    void resolveSourcePrefersAttackerThenShooter()
    {
        Player attacker = mock(Player.class);
        Entity damager = mock(Entity.class);
        assertSame(attacker, ProtectionHelper.resolveSource(damager, attacker));

        ProjectileSource shooter = mock(ProjectileSource.class);
        Projectile projectile = mock(Projectile.class);
        when(projectile.getShooter()).thenReturn(shooter);
        assertSame(shooter, ProtectionHelper.resolveSource(projectile, null));

        ProjectileSource block = mock(Mob.class);
        assertSame(block, ProtectionHelper.resolveSource((Entity) block, null));

        assertNull(ProtectionHelper.resolveSource(mock(Entity.class), null));
        assertNull(ProtectionHelper.resolveSource(null, null));
    }
}
