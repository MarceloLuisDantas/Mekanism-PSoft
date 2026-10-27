package mekanism.common.tile.laser;

import java.util.Comparator;
import java.util.List;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.SerializationConstants;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.lasers.ILaserDissipation;
import mekanism.api.lasers.ILaserReceptor;
import mekanism.api.math.MathUtils;
import mekanism.common.advancements.MekanismCriteriaTriggers;
import mekanism.common.base.MekFakePlayer;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.energy.LaserEnergyContainer;
import mekanism.common.capabilities.holder.single.ISingleContainerHolder;
import mekanism.common.config.MekanismConfig;
import mekanism.common.integration.computer.annotation.SyntheticComputerMethod;
import mekanism.common.item.gear.ItemAtomicDisassembler;
import mekanism.common.network.PacketUtils;
import mekanism.common.network.to_client.PacketHitBlockEffect;
import mekanism.common.particle.LaserParticleData;
import mekanism.common.registries.MekanismDamageTypes;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemUtil;
import net.neoforged.neoforge.transfer.item.LivingEntityEquipmentWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.UnknownNullability;
import org.jspecify.annotations.Nullable;

//TODO - V11: Make the laser "shrink" the further distance it goes, If above a certain energy level and in water makes it make a bubble stream
public abstract class TileEntityBasicLaser extends TileEntityMekanism {

    @UnknownNullability//Initialized via getInitialEnergyContainer
    protected LaserEnergyContainer energyContainer;
    @Nullable
    @SyntheticComputerMethod(getter = "getDiggingPos")
    private BlockPos digging;
    private int diggingProgress = 0;
    private int lastFired = 0;

    public TileEntityBasicLaser(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    @Override
    protected abstract ISingleContainerHolder<IEnergyContainer> getInitialEnergyContainer(IContentsListener listener);

    @Override
    protected boolean onUpdateServer(ServerLevel level) {
        boolean sendUpdatePacket = super.onUpdateServer(level);
        int energyFired = fireLaser(level);
        if (energyFired > 0) {
            if (energyFired != lastFired || !getActive()) {
                setActive(true);
                lastFired = energyFired;
                sendUpdatePacket = true;
            }
        } else if (getActive()) {
            setActive(false);
            diggingProgress = 0;
            if (lastFired != 0) {
                lastFired = 0;
                sendUpdatePacket = true;
            }
        }
        return sendUpdatePacket;
    }

    private int fireLaser(ServerLevel level) {
        int toFire = toFire();
        if (toFire == 0) {
            return 0;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            if (energyContainer.extract(toFire, transaction, AutomationType.INTERNAL) == toFire) {
                fireLaser(level, toFire, transaction);
                transaction.commit();
                return toFire;
            }
            return 0;
        }
    }

    private void fireLaser(ServerLevel level, int firing, TransactionContext transaction) {
        Direction direction = getDirection();
        Vec3 from = Vec3.atCenterOf(worldPosition).relative(direction, 0.501);
        Vec3 to = from.relative(direction, MekanismConfig.general.laserRange.get() - 0.002);
        BlockHitResult result = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (result.getType() != Type.MISS) {
            to = result.getLocation();
        }

        LaserBeam beam = new LaserBeam(direction, from, to, firing, getEnergyScale(firing));
        hitEntities(level, beam, transaction);
        //Tell the clients to render the laser
        sendLaserDataToPlayers(level, new LaserParticleData(direction, (float) beam.to.distanceTo(beam.from), beam.energyScale), beam.from);

        if (beam.remainingEnergy == 0 || result.getType() == Type.MISS) {
            //If all the energy was spent on damaging entities or if we aren't actively digging a block,
            // then reset any digging progress we may have
            digging = null;
            diggingProgress = 0;
        } else {
            //Otherwise, we still have energy left that we can use
            hitBlock(level, result, beam, transaction);
        }
    }

    /// Applies the laser to all entities in its path, in order of distance, until the beam has been fully absorbed.
    private void hitEntities(ServerLevel level, LaserBeam beam, TransactionContext transaction) {
        List<Entity> hitEntities = level.getEntitiesOfClass(Entity.class, getLaserBox(beam.direction, beam.from, beam.to, beam.energyScale));
        if (hitEntities.isEmpty()) {
            setEmittingRedstone(false);
            return;
        }
        setEmittingRedstone(true);
        //Sort the entities in order of which one is closest to the laser
        Vec3 origin = beam.from;
        hitEntities.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(origin)));
        int energyPerDamage = MekanismConfig.general.laserEnergyPerDamage.get();
        for (Entity entity : hitEntities) {
            if (beam.adjustedAABB != null && !entity.getBoundingBox().intersects(beam.adjustedAABB)) {
                //If we have a smaller AABB than we started with, make sure the entity still is getting hit by the laser
                // before we do any processing related to behavior when hit
                continue;
            } else if (isInvulnerableToLaser(entity, level)) {
                //The entity can absorb all the energy because they are immune to the damage
                beam.remainingEnergy = 0;
                beam.stopAt(entity);
                break;
            } else if (entity instanceof ItemEntity item && handleHitItem(item, transaction)) {
                //TODO: Allow the tractor beam to have an energy cost for pulling items?
                continue;
            }
            if (!hitEntity(level, beam, entity, energyPerDamage)) {
                break;
            }
        }
    }

    /// @return `true` if the beam continues past the entity, `false` if the entity absorbed all the remaining energy.
    private boolean hitEntity(ServerLevel level, LaserBeam beam, Entity entity, int energyPerDamage) {
        boolean updateEnergyScale = false;
        double value;
        float health = 0;
        if (entity instanceof LivingEntity livingEntity) {
            //If the entity is a living entity check if they are blocking with a shield and then allow
            // the shield to cause some damage to be dissipated in exchange for durability
            //TODO - V11: Add a laser reflector capability that shields can implement to cause the laser beam to be reflected
            // maybe even implement this ability but don't add it to any of our things yet?
            if (blockWithShield(level, beam, livingEntity, energyPerDamage)) {
                //If we absorbed it all then update the position the laser is going to
                beam.stopAt(entity);
                return false;
            }
            //After our shield checks see if the armor the entity is wearing can dissipate or refract lasers
            LaserArmorProtection protection = getLaserArmorProtection(livingEntity);
            //We start by dissipating energy across the armor after it is blocked by the shield
            // we check this after blocking by the shield as the shield is in front of the entity and their armor
            if (protection.dissipation() > 0) {
                beam.remainingEnergy = (int) (beam.remainingEnergy * (1 - protection.dissipation()));
                if (beam.remainingEnergy == 0) {
                    //If we dissipated it all then update the position the laser is going to
                    beam.stopAt(entity);
                    return false;
                }
            }
            //After dissipating any energy across the armor we try to refract some energy through the armor this
            // will further reduce the damage the entity would take and allow the laser to continue through onto
            // the other side
            if (protection.refraction() > 0) {
                float refractedEnergy = beam.remainingEnergy * protection.refraction();
                //Don't actually use the refracted energy from our remaining energy
                // but lower the damage values to not include the energy that is being refracted
                value = (beam.remainingEnergy - refractedEnergy) / energyPerDamage;
                //Mark the energy scale should be checked for updates as if some energy got dissipated above, and
                // we end up refracting all the remaining energy we won't do any damage and not get through the
                // normal code path that checks if the energy scale changed
                updateEnergyScale = true;
            } else {
                value = (double) beam.remainingEnergy / energyPerDamage;
            }
            health = livingEntity.getHealth();
        } else {
            value = (double) beam.remainingEnergy / energyPerDamage;
        }
        //If the damage is more than zero, which should be all cases except for when we are refracting all the energy past the entity
        // try to damage the entity
        if ((float) value > 0 && damageEntity(level, beam, entity, value, health, energyPerDamage)) {
            if (beam.remainingEnergy == 0) {
                //Update the position that the laser is going to
                beam.stopAt(entity);
                return false;
            }
            //If we have any energy left over after damaging the entity, mark that we are going to need to update the energy scale
            updateEnergyScale = true;
        }
        if (updateEnergyScale) {
            updateEnergyScale(level, beam, entity);
        }
        return true;
    }

    /// Removes from the beam however much energy the entity's shield was able to block.
    ///
    /// @return `true` if the shield absorbed all the remaining energy.
    private boolean blockWithShield(ServerLevel level, LaserBeam beam, LivingEntity livingEntity, int energyPerDamage) {
        float damage = (float) ((double) beam.remainingEnergy / energyPerDamage);
        float damageBlocked = damageShield(level, livingEntity, beam.from, damage);
        if (damageBlocked <= 0) {
            return false;
        }
        if (livingEntity instanceof ServerPlayer player) {
            //If the entity is a player trigger the advancement criteria for blocking a laser with a shield
            MekanismCriteriaTriggers.BLOCK_LASER.value().trigger(player);
        }
        //Remove however much energy we were able to block
        beam.remainingEnergy -= MathUtils.clampToInt(energyPerDamage * damageBlocked);
        return beam.remainingEnergy == 0;
    }

    /// Sums up the dissipation and refraction percentages of the armor the entity is wearing.
    private static LaserArmorProtection getLaserArmorProtection(LivingEntity livingEntity) {
        float dissipationPercent = 0;
        float refractionPercent = 0;
        ResourceHandler<ItemResource> armorSlots = LivingEntityEquipmentWrapper.of(livingEntity, EquipmentSlot.Type.HUMANOID_ARMOR);
        for (int slot = 0, size = armorSlots.size(); slot < size; slot++) {
            ItemStack stack = ItemUtil.getStack(armorSlots, slot);
            if (!stack.isEmpty()) {
                ILaserDissipation laserDissipation = stack.getCapability(Capabilities.LASER_DISSIPATION);
                if (laserDissipation != null) {
                    dissipationPercent += laserDissipation.getDissipationPercent();
                    refractionPercent += laserDissipation.getRefractionPercent();
                    if (dissipationPercent >= 1) {
                        //If we will fully dissipate it, don't bother checking the rest of the armor slots
                        break;
                    }
                }
            }
        }
        //Cap the amounts we will dissipate and refract at one
        return new LaserArmorProtection(Math.min(dissipationPercent, 1), Math.min(refractionPercent, 1));
    }

    /// Sets the entity on fire if it is not fire immune and tries to damage it, removing the energy used for the damage from the beam.
    ///
    /// @return `true` if the entity was damaged.
    private boolean damageEntity(ServerLevel level, LaserBeam beam, Entity entity, double value, float health, int energyPerDamage) {
        float damage = (float) value;
        if (!entity.fireImmune()) {
            entity.igniteForTicks(MathUtils.clampToInt(value));
        }
        int totemTimesUsed = -1;
        if (entity instanceof ServerPlayer player && level.getServer().isHardcore()) {
            totemTimesUsed = player.getStats().getValue(Stats.ITEM_USED.get(Items.TOTEM_OF_UNDYING));
        }
        //Note: We add the laser damage type to bypass cooldown via tags so this will go off regardless of invulnerability timer
        if (!entity.hurtServer(level, MekanismDamageTypes.LASER.source(level), damage)) {
            return false;
        }
        if (entity instanceof LivingEntity livingEntity) {
            //Update the damage to match how much health the entity lost
            damage = Math.clamp(health - livingEntity.getHealth(), 0, damage);
            if (entity instanceof ServerPlayer player) {
                //If the damage actually went through fire the trigger
                boolean hardcoreTotem = totemTimesUsed != -1 && totemTimesUsed < player.getStats().getValue(Stats.ITEM_USED.get(Items.TOTEM_OF_UNDYING));
                MekanismCriteriaTriggers.DAMAGE.value().trigger(player, MekanismDamageTypes.LASER, hardcoreTotem);
            }
        }
        beam.remainingEnergy -= MathUtils.clampToInt(energyPerDamage * damage);
        return true;
    }

    /// If the beam lost enough energy, renders the segment up to the entity and continues with a thinner beam from there.
    private void updateEnergyScale(ServerLevel level, LaserBeam beam, Entity entity) {
        float energyScale = getEnergyScale(beam.remainingEnergy);
        if (beam.energyScale - energyScale > 0.01) {
            //Send the laser between the two positions and update the energy scale
            Vec3 entityPos = beam.positionOf(entity);
            sendLaserDataToPlayers(level, new LaserParticleData(beam.direction, (float) entityPos.distanceTo(beam.from), beam.energyScale), beam.from);
            beam.energyScale = energyScale;
            //Update the from position to be where the entity is
            beam.from = entityPos;
            //Mark we have a new AABB we have to check against, as the beam isn't as large anymore,
            // so it is possible some things no longer should be getting hit by it
            beam.adjustedAABB = getLaserBox(beam.direction, beam.from, beam.to, beam.energyScale);
        }
    }

    /// Uses the remaining energy of the beam on the block it hit, either by giving it to a laser receptor or by making progress on breaking the block.
    private void hitBlock(ServerLevel level, BlockHitResult result, LaserBeam beam, TransactionContext transaction) {
        BlockPos hitPos = result.getBlockPos();
        if (!hitPos.equals(digging)) {
            digging = hitPos;
            diggingProgress = 0;
        }
        ILaserReceptor laserReceptor = WorldUtils.getCapability(level, Capabilities.LASER_RECEPTOR, hitPos, result.getDirection());
        if (laserReceptor != null && !laserReceptor.canLasersDig()) {
            //Give the energy to the receptor
            laserReceptor.receiveLaserEnergy(beam.remainingEnergy, transaction);
        } else {
            //Otherwise, make progress on breaking the block
            digBlock(level, result, beam, transaction);
        }
    }

    private void digBlock(ServerLevel level, BlockHitResult result, LaserBeam beam, TransactionContext transaction) {
        BlockPos hitPos = result.getBlockPos();
        BlockState hitState = level.getBlockState(hitPos);
        float hardness = hitState.getDestroySpeed(level, hitPos);
        if (hardness < 0) {
            return;
        }
        diggingProgress += beam.remainingEnergy;
        if (diggingProgress >= hardness * MekanismConfig.general.laserEnergyPerHardness.get()) {
            if (MekanismConfig.general.aestheticWorldDamage.get()) {
                withFakePlayer(level, beam.to.x(), beam.to.y(), beam.to.z(), hitPos, hitState, result.getDirection(), transaction);
            }
            diggingProgress = 0;
        } else {
            //Note: If this has a significant network performance, we could instead convert this to a start/stop packet
            PacketUtils.sendToAllTracking(new PacketHitBlockEffect(hitPos, result.getDirection(), false), this);
        }
    }

    /// Mutable state of a laser beam as it travels through the entities in its path.
    private static final class LaserBeam {

        private final Direction direction;
        private final Axis axis;
        private Vec3 from;
        private Vec3 to;
        private int remainingEnergy;
        private float energyScale;
        /// The bounding box of the beam after it became thinner, or `null` if it still has its initial size
        @Nullable
        private AABB adjustedAABB;

        private LaserBeam(Direction direction, Vec3 from, Vec3 to, int energy, float energyScale) {
            this.direction = direction;
            this.axis = direction.getAxis();
            this.from = from;
            this.to = to;
            this.remainingEnergy = energy;
            this.energyScale = energyScale;
        }

        private Vec3 positionOf(Entity entity) {
            return from.with(axis, entity.position().get(axis));
        }

        /// Makes the laser end at the given entity.
        private void stopAt(Entity entity) {
            to = positionOf(entity);
        }
    }

    private record LaserArmorProtection(float dissipation, float refraction) {
    }

    private static boolean isInvulnerableToLaser(Entity entity, ServerLevel level) {
        DamageSource lasers = MekanismDamageTypes.LASER.source(level);
        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity.isInvulnerableTo(level, lasers);
        }
        //fall back to the normally protected method, which fires an event
        return entity.isInvulnerableToBase(lasers);
    }

    private AABB getLaserBox(Direction direction, Vec3 from, Vec3 to, float energyScale) {
        AABB aabb = new AABB(from, to);
        double halfDiameter = energyScale / 2;
        return switch (direction) {
            case DOWN, UP -> aabb.inflate(halfDiameter, 0, halfDiameter);
            case NORTH, SOUTH -> aabb.inflate(halfDiameter, halfDiameter, 0);
            case WEST, EAST -> aabb.inflate(0, halfDiameter, halfDiameter);
        };
    }

    private void withFakePlayer(ServerLevel level, double x, double y, double z, BlockPos hitPos, BlockState hitState, Direction hitSide, TransactionContext transaction) {
        MekFakePlayer dummy = MekFakePlayer.setupFakePlayer(level, x, y, z);
        dummy.setEmulatingData(this);//pretend to be the owner
        BreakBlockEvent event = new BreakBlockEvent(level, hitPos, hitState, dummy);
        //Note: We only fire this on the server, so it doesn't matter if the client is marked for notification or not, as the client doesn't know the block was even
        // trying to be broken
        if (!NeoForge.EVENT_BUS.post(event).isCanceled()) {
            if (hitState.getBlock() instanceof TntBlock && hitState.isFlammable(level, hitPos, hitSide)) {
                //Convert TNT that can be lit on fire into a tnt entity
                //Note: We don't mark the fake player as the igniter as then when the tnt explodes if it hits a player
                // there will be a crash as our fake player's level will be null
                hitState.onCaughtFire(level, hitPos, hitSide, null, ItemStack.EMPTY);
                level.removeBlock(hitPos, false);
            } else {
                //Use the disassembler as the item to break the block with as that is marked as being the correct tool for drops
                handleBreakBlock(hitState, level, hitPos, dummy, ItemAtomicDisassembler.fullyChargedStack(transaction), transaction);
            }
        }
        dummy.cleanupFakePlayer(level);
    }

    /// @param from   Where the laser is firing from
    /// @param damage Damage to do
    ///
    /// @return The amount of damage that was blocked
    ///
    /// @implNote most logic copied from [net.minecraft.world.entity.LivingEntity#applyItemBlocking]
    private float damageShield(Level level, LivingEntity livingEntity, Vec3 from, float damage) {
        DamageSource source = MekanismDamageTypes.LASER.source(level, from);
        //Absorb part of the damage based on the given absorption ratio
        DamageContainer damageContainer = new DamageContainer(source, damage);
        //Note: Even though we fire this even here manually, it doesn't cause issues with the damage pipeline
        // as if we do block damage, then we won't end up firing the normal pipeline
        ItemStack blockingWith = livingEntity.getItemBlockingWith();
        if (blockingWith == null) {
            return 0;
        }
        BlocksAttacks blocksAttacks = blockingWith.get(DataComponents.BLOCKS_ATTACKS);
        if (blocksAttacks == null) {
            return 0;
        }
        HolderSet<DamageType> bypassedBy = blocksAttacks.bypassedBy().orElse(null);
        boolean originallyBlocked = bypassedBy != null && !bypassedBy.contains(source.typeHolder());
        float damageBlocked = 0;
        //assuming if it was bypassed we don't need to calculate this?
        if (originallyBlocked) {
            double angle = getAngle(livingEntity, from);
            damageBlocked = blocksAttacks.resolveBlockedDamage(source, damage, angle);
        }
        LivingShieldBlockEvent event = CommonHooks.onDamageBlock(livingEntity, damageContainer, damageBlocked, originallyBlocked);
        if (!event.getBlocked()) {
            //Blocking was not allowed, return we didn't block any damage
            return 0;
        }
        int shieldDamage = event.shieldDamage();
        if (shieldDamage > 0) {
            //Only damage the shield if the shield isn't setup to block damage for free
            blocksAttacks.hurtBlockingItem(level, blockingWith, livingEntity, livingEntity.getUsedItemHand(), damageBlocked, shieldDamage);
        }
        damageBlocked = event.getBlockedDamage();
        if (livingEntity instanceof Player player && damageBlocked > 0 && damageBlocked < 3.4028235E37F) {
            player.awardStat(Stats.DAMAGE_BLOCKED_BY_SHIELD, Math.round(damageBlocked * 10F));
        }
        return damageBlocked;
    }

    private static double getAngle(LivingEntity livingEntity, Vec3 from) {
        Vec3 viewVector = livingEntity.calculateViewVector(0.0F, livingEntity.getYHeadRot());
        Vec3 vectorTo = from.subtract(livingEntity.position());
        vectorTo = new Vec3(vectorTo.x, 0.0, vectorTo.z).normalize();
        return Math.acos(vectorTo.dot(viewVector));
    }

    private float getEnergyScale(int energy) {
        //Returned energy scale is between [0.1, 0.6]
        return Math.min((float) energy / MekanismConfig.usage.laser.get() / 10, 0.6F);
    }

    private void sendLaserDataToPlayers(ServerLevel level, LaserParticleData data, Vec3 from) {
        for (ServerPlayer player : level.players()) {
            //Note: We render laser particles regardless of the particle limit to avoid players accidentally killing themselves on them
            level.sendParticles(player, data, true, true, from.x, from.y, from.z, 1, 0, 0, 0, 0);
        }
    }

    protected void setEmittingRedstone(boolean foundEntity) {
    }

    protected boolean handleHitItem(ItemEntity entity, TransactionContext transaction) {
        return false;
    }

    protected void handleBreakBlock(BlockState state, ServerLevel level, BlockPos hitPos, Player player, ItemStack tool, TransactionContext transaction) {
        for (ItemEntity drop : WorldUtils.getDrops(state, level, hitPos, WorldUtils.getTileEntity(level, hitPos), player, tool, true)) {
            if (!drop.getItem().isEmpty()) {
                level.addFreshEntity(drop);
            }
        }
        breakBlock(state, level, hitPos, tool);
    }

    protected final void breakBlock(BlockState state, ServerLevel level, BlockPos hitPos, ItemStack tool) {
        state.spawnAfterBreak(level, hitPos, tool, false);
        level.removeBlock(hitPos, false);
        //TODO: We may want to evaluate at some point doing this with our fake player so that it is fired as the "cause"?
        level.gameEvent(GameEvent.BLOCK_DESTROY, hitPos, GameEvent.Context.of(null, state));
        level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, hitPos, Block.getId(state));
    }

    protected int toFire() {
        return energyContainer.getAmountAsInt();
    }

    @Override
    public void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        lastFired = input.getIntOr(SerializationConstants.LAST_FIRED, 0);
    }

    @Override
    public void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt(SerializationConstants.LAST_FIRED, lastFired);
    }

    @Override
    @Deprecated
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard(SerializationConstants.LAST_FIRED);
    }

    @Override
    public void writeReducedUpdatedTag(ValueOutput output) {
        super.writeReducedUpdatedTag(output);
        output.putInt(SerializationConstants.LAST_FIRED, lastFired);
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        super.handleUpdateTag(input);
        lastFired = input.getIntOr(SerializationConstants.LAST_FIRED, 0);
    }

    public LaserEnergyContainer energyContainer() {
        return energyContainer;
    }
}