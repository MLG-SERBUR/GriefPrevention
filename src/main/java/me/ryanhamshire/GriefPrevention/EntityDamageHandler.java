package me.ryanhamshire.GriefPrevention;

import com.griefprevention.protection.ProtectionHelper;
import me.ryanhamshire.GriefPrevention.events.PreventPvPEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Animals;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Mule;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class EntityDamageHandler implements Listener
{

    private static final Set<PotionEffectType> GRIEF_EFFECTS = Set.of(
            // Damaging effects
            PotionEffectType.INSTANT_DAMAGE,
            PotionEffectType.POISON,
            PotionEffectType.WITHER,
            // Effects that could remove entities from normally-secure pens
            PotionEffectType.JUMP_BOOST,
            PotionEffectType.LEVITATION
    );
    private static final Set<EntityType> TEMPTABLE_SEMI_HOSTILES = Set.of(
            EntityType.HOGLIN,
            EntityType.POLAR_BEAR,
            EntityType.PANDA
    );

    private static final NamespacedKey LURED_BY_PLAYER = NamespacedKey.fromString("griefprevention:lured_by_player");

    private final @NotNull DataStore dataStore;
    private final @NotNull GriefPrevention instance;

    EntityDamageHandler(@NotNull DataStore dataStore, @NotNull GriefPrevention plugin)
    {
        this.dataStore = dataStore;
        instance = plugin;
    }

    // Tag passive animals that can become aggressive so that we can tell whether they are hostile later
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityTarget(@NotNull EntityTargetEvent event)
    {
        if (!instance.claimsEnabledForWorld(event.getEntity().getWorld()))
            return;

        if (!TEMPTABLE_SEMI_HOSTILES.contains(event.getEntityType()))
            return;

        if (event.getReason() == EntityTargetEvent.TargetReason.TEMPT)
            event.getEntity().getPersistentDataContainer().set(LURED_BY_PLAYER, PersistentDataType.BYTE, (byte) 1);
        else
            event.getEntity().getPersistentDataContainer().remove(LURED_BY_PLAYER);

    }

    //when an entity is damaged
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityDamage(@NotNull EntityDamageEvent event)
    {
        this.handleEntityDamageEvent(new EntityDamageInstance(event), true);
    }

    //when an entity is set on fire
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityCombustByEntity(@NotNull EntityCombustByEntityEvent event)
    {
        this.handleEntityDamageEvent(new EntityDamageInstance(event), false);
    }

    private void handleEntityDamageEvent(@NotNull EntityDamageInstance event, boolean sendMessages)
    {
        //monsters are never protected
        if (isHostile(event.damaged())) return;

        //horse protections can be disabled
        if (event.damaged() instanceof Horse && !instance.config_claims_protectHorses) return;
        if (event.damaged() instanceof Donkey && !instance.config_claims_protectDonkeys) return;
        if (event.damaged() instanceof Mule && !instance.config_claims_protectDonkeys) return;
        if (event.damaged() instanceof Llama && !instance.config_claims_protectLlamas) return;
        //protected death loot can't be destroyed, only picked up or despawned due to expiration
        if (event.damaged().getType() == EntityType.ITEM)
        {
            if (event.damaged().hasMetadata("GP_ITEMOWNER"))
            {
                event.setCancelled(true);
            }
        }

        // Handle environmental damage to tamed animals that could easily be caused maliciously.
        if (handlePetDamageByEnvironment(event)) return;

        // Handle entity damage by block explosions.
        if (handleEntityDamageByBlockExplosion(event)) return;

        //the rest is only interested in entities damaging entities (ignoring environmental damage)
        if (event.damager() == null) return;

        if (event.damager() instanceof LightningStrike && event.damager().hasMetadata("GP_TRIDENT"))
        {
            event.setCancelled(true);
            return;
        }

        //determine which player is attacking, if any
        Player attacker = null;
        Projectile arrow = null;
        Entity damageSource = event.damager();
        if (damageSource instanceof Player damager)
        {
            attacker = damager;
        }
        else if (damageSource instanceof Projectile projectile)
        {
            arrow = projectile;
            if (arrow.getShooter() instanceof Player shooter)
            {
                attacker = shooter;
            }
        }

        // Specific handling for PVP-enabled situations.
        if (instance.pvpRulesApply(event.damaged().getWorld()))
        {
            if (event.damaged() instanceof Player defender)
            {
                // Protect players from other players' pets when protected from PVP.
                if (handlePvpDamageByPet(event, attacker, defender)) return;

                // Protect players from lingering splash potions when protected from PVP.
                if (handlePvpDamageByLingeringPotion(event, attacker, defender)) return;

                // Handle regular PVP with an attacker and defender.
                if (attacker != null && handlePvpDamageByPlayer(event, attacker, defender, sendMessages))
                {
                    return;
                }
            }
            else if (event.damaged() instanceof Tameable tameable)
            {
                if (attacker != null && handlePvpPetDamageByPlayer(event, tameable, attacker, sendMessages))
                {
                    return;
                }
            }
        }

        //don't track in worlds where claims are not enabled
        if (!instance.claimsEnabledForWorld(event.damaged().getWorld())) return;

        //if the damaged entity is a claimed item frame or armor stand, the damager needs to be a player with build trust in the claim
        if (handleClaimedBuildTrustDamageByEntity(event, attacker, sendMessages)) return;

        //if the entity is a non-monster creature (remember monsters disqualified above), or a vehicle
        if (handleCreatureDamageByEntity(event, attacker, arrow, sendMessages)) return;
    }

    /**
     * Checks if the given entity is considered hostile.
     * <p>
     * Used to determine whether an entity should be protected in claims.
     * Hostile entities are never protected.
     *
     * @param entity the {@code Entity}
     * @return true if the {@code Entity} is hostile
     */
    public static boolean isHostile(@NotNull Entity entity)
    {
        if (entity instanceof Monster) return true;

        EntityType type = entity.getType();
        if (type == EntityType.GHAST || type == EntityType.MAGMA_CUBE || type == EntityType.SHULKER)
            return true;

        if (entity instanceof Slime slime)
        {
            // Size 0 "baby" slimes cannot deal damage and are often kept as pets.
            // This is really inconvenient for players who are trying to harvest slimeballs in areas with claims;
            // the full-sized slimes are considered dangerous, but the ones that actually drop the slimeballs are not.
            // To make this protection less obnoxious, only protect baby slimes that have lived for a minute or more.
            return slime.getSize() > 0 || slime.getTicksLived() < 1200;
        }

        if (entity instanceof Rabbit rabbit)
            return rabbit.getRabbitType() == Rabbit.Type.THE_KILLER_BUNNY;

        if ((TEMPTABLE_SEMI_HOSTILES.contains(type)) && entity instanceof Mob mob)
            return !entity.getPersistentDataContainer().has(LURED_BY_PLAYER, PersistentDataType.BYTE) && mob.getTarget() != null;

        return false;
    }

    /**
     * Handle damage to {@link Tameable} entities by environmental sources.
     *
     * @param event the {@link EntityDamageInstance}
     * @return true if the damage is handled
     */
    private boolean handlePetDamageByEnvironment(@NotNull EntityDamageInstance event)
    {
        // If PVP is enabled, the damaged entity is not a pet, or the pet has no owner, allow.
        if (instance.pvpRulesApply(event.damaged().getWorld())
                || !(event.damaged() instanceof Tameable tameable)
                || !tameable.isTamed())
        {
            return false;
        }
        switch (event.cause())
        {
            // Block environmental and easy-to-cause damage sources.
            case BLOCK_EXPLOSION,
                    ENTITY_EXPLOSION,
                    FALLING_BLOCK,
                    FIRE,
                    FIRE_TICK,
                    LAVA,
                    SUFFOCATION,
                    CONTACT,
                    DROWNING ->
            {
                event.setCancelled(true);
                return true;
            }
            default ->
            {
                return false;
            }
        }
    }

    /**
     * Handle entity damage caused by block explosions.
     *
     * @param event the {@link EntityDamageInstance}
     * @return true if the damage is handled
     */
    private boolean handleEntityDamageByBlockExplosion(@NotNull EntityDamageInstance event)
    {
        if (event.cause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return false;

        Entity entity = event.damaged();

        // Skip players - does allow players to use block explosions to bypass PVP protections,
        // but also doesn't disable self-damage.
        if (entity instanceof Player) return false;

        // Unattributed blast (null source): denied exactly when inside a claim.
        if (!ProtectionHelper.checkClaimedAction(null, entity.getLocation(), ClaimPermission.Build, event.original()).allowed())
        {
            event.setCancelled(true);
            return true;
        }

        return false;
    }

    /**
     * Handle PVP damage caused by a lingering splash potion.
     *
     * <p>For logical simplicity, this method does not check the state of the PVP rules. PVP rules should be confirmed
     * to be enabled before calling this method.
     *
     * @param event the {@link EntityDamageInstance}
     * @param attacker the attacking {@link Player}, if any
     * @param damaged the defending {@link Player}
     * @return true if the damage is handled
     */
    private boolean handlePvpDamageByLingeringPotion(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            @NotNull Player damaged)
    {
        if (!(event.damager() instanceof AreaEffectCloud)) return false;

        PlayerData damagedData = dataStore.getPlayerData(damaged.getUniqueId());

        //case 1: recently spawned
        if (instance.config_pvp_protectFreshSpawns && damagedData.pvpImmune)
        {
            event.setCancelled(true);
            return true;
        }

        //case 2: in a pvp safe zone
        Claim damagedClaim = dataStore.getClaimAt(damaged.getLocation(), false, damagedData.lastClaim);
        if (damagedClaim != null)
        {
            damagedData.lastClaim = damagedClaim;
            if (instance.claimIsPvPSafeZone(damagedClaim))
            {
                PreventPvPEvent pvpEvent = new PreventPvPEvent(damagedClaim, attacker, damaged);
                Bukkit.getPluginManager().callEvent(pvpEvent);
                if (!pvpEvent.isCancelled())
                {
                    event.setCancelled(true);
                }
                return true;
            }
        }

        return false;
    }

    /**
     * General PVP handler.
     *
     * @param event the {@link EntityDamageInstance}
     * @param attacker the attacking {@link Player}
     * @param defender the defending {@link Player}
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handlePvpDamageByPlayer(
            @NotNull EntityDamageInstance event,
            @NotNull Player attacker,
            @NotNull Player defender,
            boolean sendMessages)
    {
        if (attacker == defender) return false;

        PlayerData defenderData = this.dataStore.getPlayerData(defender.getUniqueId());
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());

        //FEATURE: prevent pvp in the first minute after spawn and when one or both players have no inventory
        if (instance.config_pvp_protectFreshSpawns)
        {
            if (attackerData.pvpImmune || defenderData.pvpImmune)
            {
                event.setCancelled(true);
                if (sendMessages)
                    GriefPrevention.sendMessage(
                            attacker,
                            TextMode.Err,
                            attackerData.pvpImmune ? Messages.CantFightWhileImmune : Messages.ThatPlayerPvPImmune);
                return true;
            }
        }

        //FEATURE: prevent players from engaging in PvP combat inside land claims (when it's disabled)
        // Ignoring claims bypasses this feature.
        if (attackerData.ignoreClaims
                || !instance.config_pvp_noCombatInPlayerLandClaims
                && !instance.config_pvp_noCombatInAdminLandClaims)
        {
            return false;
        }
        Consumer<Messages> cancelHandler = message ->
        {
            event.setCancelled(true);
            if (sendMessages) GriefPrevention.sendMessage(attacker, TextMode.Err, message);
        };
        // Return whether PVP is handled by a claim at the attacker or defender's locations.
        return handlePvpInClaim(attacker, defender, attacker.getLocation(), attackerData, () -> cancelHandler.accept(Messages.CantFightWhileImmune))
                || handlePvpInClaim(attacker, defender, defender.getLocation(), defenderData, () -> cancelHandler.accept(Messages.PlayerInPvPSafeZone));
    }

    /**
     * Handle PVP damage caused by an owned pet.
     *
     * @param event the {@link EntityDamageInstance}
     * @param attacker the attacking {@link Player}, if any
     * @return true if the damage is handled
     */
    private boolean handlePvpDamageByPet(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            @NotNull Player defender)
    {
        if (!(event.damager() instanceof Tameable pet) || !pet.isTamed() || pet.getOwner() == null) return false;

        PlayerData defenderData = dataStore.getPlayerData(defender.getUniqueId());
        Runnable cancelHandler = () ->
        {
            event.setCancelled(true);
            pet.setTarget(null);
        };

        // If the defender is PVP-immune, prevent the attack.
        if (defenderData.pvpImmune)
        {
            cancelHandler.run();
            return true;
        }

        // Return whether PVP is handled by a claim at the defender's location.
        return handlePvpInClaim(attacker, defender, defender.getLocation(), defenderData, cancelHandler);
    }

    /**
     * Handle PVP damage to an owned pet.
     *
     * @param event the {@link EntityDamageInstance}
     * @param pet the potential pet being damaged
     * @param attacker the attacking {@link Player}
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handlePvpPetDamageByPlayer(
            @NotNull EntityDamageInstance event,
            @NotNull Tameable pet,
            @NotNull Player attacker,
            boolean sendMessages)
    {

        if (!pet.isTamed()) return false;

        AnimalTamer owner = pet.getOwner();
        if (owner == null) return false;

        // If the player interacting is the owner, always allow.
        if (attacker.equals(owner)) return true;

        // Allow admin override.
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());
        if (attackerData.ignoreClaims) return true;

        // Disallow provocations while PVP-immune.
        if (attackerData.pvpImmune)
        {
            event.setCancelled(true);
            if (sendMessages)
                GriefPrevention.sendMessage(attacker, TextMode.Err, Messages.CantFightWhileImmune);
            return true;
        }

        // Wolves are exempt from pet protections in PVP worlds when their target is the attacker
        if (event.damaged().getType() == EntityType.WOLF && pet.getTarget() == attacker) return true;

        Claim claim;
        // Note: Internal name is not descriptive. Actual node is "GriefPrevention.PVP.ProtectPetsOutsideLandClaims"
        if (!instance.config_pvp_protectPets)
        {
            claim = dataStore.getClaimAt(event.damaged().getLocation(), false, attackerData.lastClaim);
            if (claim == null)
            {
                // Pet is not in a claim, allow attack.
                return true;
            }
            attackerData.lastClaim = claim;
        }
        else
        {
            // Create a dummy claim to signify blanket pet protection.
            claim = new Claim(event.damaged().getLocation(), event.damaged().getLocation(), null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
        }

        PreventPvPEvent pvpEvent = new PreventPvPEvent(claim, attacker, pet);
        Bukkit.getPluginManager().callEvent(pvpEvent);
        if (!pvpEvent.isCancelled())
        {
            event.setCancelled(true);
            if (sendMessages)
            {
                String ownerName = GriefPrevention.lookupPlayerName(owner);
                String message = dataStore.getMessage(Messages.NoDamageClaimedEntity, ownerName);
                if (attacker.hasPermission("griefprevention.ignoreclaims"))
                    message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                GriefPrevention.sendMessage(attacker, TextMode.Err, message);
            }
        }
        return true;
    }

    /**
     * Handle a PVP action depending on configured rules. Fires a {@link PreventPvPEvent} to allow addons to change
     * default behavior.
     *
     * @param attacker the attacking {@link Player}, or null for indirect PVP like pet-induced damage
     * @param defender the defending {@link Player}
     * @param location the {@link Location} to be checked
     * @param playerData the {@link PlayerData} used for caching last claim
     * @param cancelHandler the {@link Runnable} to run if PVP is disallowed
     * @return true if PVP is handled by claim rules
     */
    private boolean handlePvpInClaim(
            @Nullable Player attacker,
            @NotNull Player defender,
            @NotNull Location location,
            @NotNull PlayerData playerData,
            @NotNull Runnable cancelHandler)
    {
        if (playerData.inPvpCombat()) return false;

        Claim claim = this.dataStore.getClaimAt(location, false, playerData.lastClaim);

        if (claim == null || !instance.claimIsPvPSafeZone(claim)) return false;

        playerData.lastClaim = claim;
        PreventPvPEvent pvpEvent = new PreventPvPEvent(claim, attacker, defender);
        Bukkit.getPluginManager().callEvent(pvpEvent);

        //if other plugins aren't making an exception to the rule
        if (!pvpEvent.isCancelled())
        {
            cancelHandler.run();
        }
        return true;
    }

    /**
     * Handle actions requiring build trust.
     *
     * @param event the {@link EntityDamageInstance}
     * @param attacker the attacking {@link Player}, if any
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handleClaimedBuildTrustDamageByEntity(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            boolean sendMessages)
    {
        EntityType entityType = event.damaged().getType();
        if (entityType != EntityType.ITEM_FRAME
                && entityType != EntityType.GLOW_ITEM_FRAME
                && entityType != EntityType.ARMOR_STAND
                && entityType != EntityType.VILLAGER
                && entityType != EntityType.END_CRYSTAL
                // Item Displays have no hitbox, but display plugins may manually fire events where appropriate.
                && entityType != EntityType.ITEM_DISPLAY)
        {
            return false;
        }

        if (entityType == EntityType.VILLAGER
                // Allow disabling villager protections in the config.
                && (!instance.config_claims_protectCreatures
                // Always allow zombies and raids to target villagers.
                //why exception?  so admins can set up a village which can't be CHANGED by players, but must be "protected" by players.
                || event.damager() instanceof Zombie
                || event.damager() instanceof Raider
                || event.damager() instanceof Vex
                || event.damager() instanceof Projectile projectile && projectile.getShooter() instanceof Raider
                || event.damager() instanceof EvokerFangs fangs && fangs.getOwner() instanceof Raider))
        {
            return true;
        }

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
    }

    /**
     * Handle damage to a {@link Creature} by an {@link Entity}. Because monsters are
     * already discounted, any qualifying entity is livestock or a pet.
     *
     * @param event the {@link EntityDamageInstance}
     * @param attacker the attacking {@link Player}, if any
     * @param arrow the {@link Projectile} dealing the damage, if any
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handleCreatureDamageByEntity(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            @Nullable Projectile arrow,
            boolean sendMessages)
    {
        if (!(event.damaged() instanceof Creature) || !instance.config_claims_protectCreatures)
            return false;

        //if entity is tameable and has an owner, apply special rules
        if (handlePetDamageByEntity(event, attacker, sendMessages)) return true;

        Entity damageSource = event.damager();

        // Can't be hit, but for simplicity
        if (damageSource == null) return false;

        EntityType damageSourceType = damageSource.getType();
        //if not a player, explosive, or ranged/area of effect attack, allow
        if (attacker == null
                && damageSourceType != EntityType.CREEPER
                && damageSourceType != EntityType.WITHER
                && damageSourceType != EntityType.END_CRYSTAL
                && damageSourceType != EntityType.AREA_EFFECT_CLOUD
                && damageSourceType != EntityType.WITCH
                && !(damageSource instanceof Projectile)
                && !(damageSource instanceof Explosive)
                && !(damageSource instanceof ExplosiveMinecart))
        {
            return true;
        }

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

        // Do not message players about fireworks to prevent spam due to multi-hits.
        sendMessages &= damageSourceType != EntityType.FIREWORK_ROCKET;

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

        return true;
    }

    /**
     * Handle damage to a {@link Tameable} by a {@link Player}.
     *
     * @param event the {@link EntityDamageInstance}
     * @param attacker the attacking {@link Player}, if any
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handlePetDamageByEntity(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            boolean sendMessages)
    {
        if (!(event.damaged() instanceof Tameable tameable) || !tameable.isTamed())
        {
            // If the animal is not owned, specifically allow attacks only if the animal is a wolf.
            return event.damaged().getType() == EntityType.WOLF;
        }

        AnimalTamer owner = tameable.getOwner();
        if (owner == null)
        {
            // Treat invalid state of tamed with no owner identically to untamed.
            return tameable.getType() == EntityType.WOLF;
        }

        //limit attacks by players to owners and admins in ignore claims mode
        if (attacker == null) return false;

        //if the player interacting is the owner, always allow
        if (attacker.equals(owner)) return true;

        //allow for admin override
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());
        if (attackerData.ignoreClaims) return true;

        // Allow players to attack wolves (dogs) if under attack by them.
        if (tameable.getType() == EntityType.WOLF && tameable.getTarget() != null)
        {
            if (tameable.getTarget() == attacker) return true;
        }

        event.setCancelled(true);
        if (sendMessages)
        {
            String ownerName = GriefPrevention.lookupPlayerName(owner);
            String message = dataStore.getMessage(Messages.NoDamageClaimedEntity, ownerName);
            if (attacker.hasPermission("griefprevention.ignoreclaims"))
                message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
            GriefPrevention.sendMessage(attacker, TextMode.Err, message);
        }
        return true;
    }

    /**
     * Prevent infinite bounces for cancelled projectile hits by removing or grounding {@link Projectile Projectiles}
     * as necessary.
     *
     * @param projectile the {@code Projectile} that has been prevented from hitting
     * @param entity the {@link Entity} being hit
     */
    private void preventInfiniteBounce(@Nullable Projectile projectile, @NotNull Entity entity)
    {
        if (projectile != null)
        {
            if (projectile.getType() == EntityType.TRIDENT)
            {
                // Instead of removing a trident, teleport it to the entity's foot location and remove velocity.
                projectile.teleport(entity);
                projectile.setVelocity(new Vector());
            }
            // Otherwise remove the projectile.
            else projectile.remove();
        }
    }

    // Flag players engaging in PVP.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDamageByEntityMonitor(@NotNull EntityDamageByEntityEvent event)
    {
        //FEATURE: prevent players who very recently participated in pvp combat from hiding inventory to protect it from looting
        //FEATURE: prevent players who are in pvp combat from logging out to avoid being defeated

        // If there is no damage (snowballs, eggs, etc.) or the defender is not a player in a PVP world, do nothing.
        if (event.getDamage() == 0
                || !(event.getEntity() instanceof Player defender)
                || !instance.pvpRulesApply(defender.getWorld())) return;

        //determine which player is attacking, if any
        Player attacker = null;
        Entity damageSource = event.getDamager();

        if (damageSource instanceof Player damager)
        {
            attacker = damager;
        }
        else if (damageSource instanceof Projectile arrow && arrow.getShooter() instanceof Player shooter)
        {
            attacker = shooter;
        }

        // If not PVP or attacking self, do nothing.
        if (attacker == null || attacker == defender) return;

        PlayerData defenderData = this.dataStore.getPlayerData(defender.getUniqueId());
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());

        long now = Calendar.getInstance().getTimeInMillis();
        defenderData.lastPvpTimestamp = now;
        defenderData.lastPvpPlayer = attacker.getName();
        attackerData.lastPvpTimestamp = now;
        attackerData.lastPvpPlayer = defender.getName();
    }

    //when a vehicle is damaged
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onVehicleDamage(@NotNull VehicleDamageEvent event)
    {
        //all of this is anti theft code
        if (!instance.config_claims_preventTheft) return;

        //don't track in worlds where claims are not enabled
        if (!instance.claimsEnabledForWorld(event.getVehicle().getWorld())) return;

        //determine which player is attacking, if any
        Player attacker = null;
        Projectile arrow = null;
        Entity damageSource = event.getAttacker();
        EntityType damageSourceType = null;

        //if damage source is null or a creeper, don't allow the damage when the vehicle is in a land claim
        if (damageSource != null)
        {
            damageSourceType = damageSource.getType();

            if (damageSource instanceof Player player)
            {
                attacker = player;
            }
            else if (damageSource instanceof Projectile projectile)
            {
                arrow = projectile;
                if (arrow.getShooter() instanceof Player shooter)
                {
                    attacker = shooter;
                }
            }
        }

        //if not a player and not an explosion, always allow
        if (attacker == null && damageSourceType != EntityType.CREEPER && damageSourceType != EntityType.WITHER && damageSourceType != EntityType.TNT)
        {
            return;
        }

        //NOTE: vehicles can be pushed around.
        //so unless precautions are taken by the owner, a resourceful thief might find ways to steal anyway
        ProtectionHelper.ClaimDecision decision = ProtectionHelper.checkClaimedAction(
                ProtectionHelper.resolveSource(damageSource, attacker),
                event.getVehicle().getLocation(),
                ClaimPermission.Container,
                event);

        // Allowed (trusted player or wilderness): nothing to do.
        if (decision.allowed()) return;

        event.setCancelled(true);

        //if damaged by anything other than a player, remove the projectile and stop
        if (attacker == null)
        {
            if (arrow != null) arrow.remove();
            return;
        }

        //otherwise the player damaging the entity must have permission
        preventInfiniteBounce(arrow, event.getVehicle());
        String message;
        if (decision.claim() != null)
        {
            message = dataStore.getMessage(Messages.NoDamageClaimedEntity, decision.claim().getOwnerName());
            if (attacker.hasPermission("griefprevention.ignoreclaims"))
                message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
        }
        else if (decision.denial() != null && !decision.denial().get().isEmpty())
        {
            message = decision.denial().get();
        }
        else return;
        GriefPrevention.sendMessage(attacker, TextMode.Err, message);
    }

    //when a splash potion affects one or more entities...
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPotionSplash(@NotNull PotionSplashEvent event)
    {
        ThrownPotion potion = event.getPotion();

        ProjectileSource projectileSource = potion.getShooter();
        // Ignore potions with no source.
        if (projectileSource == null) return;
        final Player thrower;
        if ((projectileSource instanceof Player))
            thrower = (Player) projectileSource;
        else thrower = null;
        AtomicBoolean messagedPlayer = new AtomicBoolean(false);

        Collection<PotionEffect> effects = potion.getEffects();
        for (PotionEffect effect : effects)
        {
            PotionEffectType effectType = effect.getType();

            // Restrict some potions on claimed villagers and animals.
            // Griefers could use potions to kill entities or steal them over fences.
            if (GRIEF_EFFECTS.contains(effectType))
            {
                Claim cachedClaim = null;
                for (LivingEntity affected : event.getAffectedEntities())
                {
                    // Always impact the thrower.
                    if (affected == thrower) continue;

                    if (affected.getType() == EntityType.VILLAGER || affected instanceof Animals)
                    {
                        // Single funnel: trusted player and same-claim dispenser may splash.
                        ProtectionHelper.ClaimDecision decision = ProtectionHelper.checkClaimedAction(
                                projectileSource, affected.getLocation(), ClaimPermission.Container, event, cachedClaim);
                        if (decision.claim() != null) cachedClaim = decision.claim();
                        if (decision.allowed()) continue;

                        // If the source may not affect the entity, null its effect.
                        event.setIntensity(affected, 0);
                        if (thrower != null && messagedPlayer.compareAndSet(false, true))
                        {
                            String message;
                            if (decision.claim() != null)
                            {
                                message = instance.dataStore.getMessage(Messages.NoDamageClaimedEntity, decision.claim().getOwnerName());
                            }
                            else if (decision.denial() != null && !decision.denial().get().isEmpty())
                            {
                                message = decision.denial().get();
                            }
                            else continue;
                            GriefPrevention.sendMessage(thrower, TextMode.Err, message);
                        }
                    }
                }
            }

            //Otherwise, ignore potions not thrown by players
            if (thrower == null) return;

            //otherwise, no restrictions for positive effects
            if (effectType.getCategory() == PotionEffectTypeCategory.BENEFICIAL) continue;

            for (LivingEntity affected : event.getAffectedEntities())
            {
                //always impact the thrower
                if (affected == thrower) continue;

                //always impact non players
                if (!(affected instanceof Player affectedPlayer)) continue;

                //otherwise if in no-pvp zone, stop effect
                //FEATURE: prevent players from engaging in PvP combat inside land claims (when it's disabled)
                if (instance.config_pvp_noCombatInPlayerLandClaims || instance.config_pvp_noCombatInAdminLandClaims)
                {
                    PlayerData playerData = this.dataStore.getPlayerData(thrower.getUniqueId());
                    Consumer<Messages> cancelHandler = message ->
                    {
                        event.setIntensity(affected, 0);
                        if (messagedPlayer.compareAndSet(false, true))
                            GriefPrevention.sendMessage(thrower, TextMode.Err, message);
                    };
                    if (handlePvpInClaim(thrower, affectedPlayer, thrower.getLocation(), playerData, () -> cancelHandler.accept(Messages.CantFightWhileImmune)))
                    {
                        continue;
                    }
                    playerData = this.dataStore.getPlayerData(affectedPlayer.getUniqueId());
                    handlePvpInClaim(thrower, affectedPlayer, affectedPlayer.getLocation(), playerData, () -> cancelHandler.accept(Messages.PlayerInPvPSafeZone));
                }
            }
        }
    }

    private record EntityDamageInstance(
            @NotNull Entity damaged,
            @Nullable Entity damager,
            @NotNull EntityDamageEvent.DamageCause cause,
            @NotNull Event original)
    {

        EntityDamageInstance(@NotNull EntityDamageEvent event)
        {
            this(
                    event.getEntity(),
                    event instanceof EntityDamageByEntityEvent damageBy ? damageBy.getDamager() : null,
                    event.getCause(),
                    event
            );
        }

        EntityDamageInstance(@NotNull EntityCombustEvent event)
        {
            this(
                    event.getEntity(),
                    event instanceof EntityCombustByEntityEvent combustBy ? combustBy.getCombuster() : null,
                    EntityDamageEvent.DamageCause.FIRE_TICK,
                    event
            );
        }

        public void setCancelled(boolean cancelled)
        {
            if (this.original instanceof Cancellable cancellable) cancellable.setCancelled(cancelled);
        }
    }

}
