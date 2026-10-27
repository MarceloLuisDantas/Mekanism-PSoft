package mekanism.common.item.gear;

import mekanism.api.math.MathUtils;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.proxy.AutomatedEnergyHandler;
import mekanism.common.lib.transaction.TransactionHelper;
import mekanism.common.util.ItemAccessUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/// Shared energy handling for the energy based mining tools ([ItemAtomicDisassembler] and [ItemMekaTool]), so that changes to how they use energy only have to
/// be made in one place.
public class EnergyToolUtils {

    private EnergyToolUtils() {
    }

    /// @return The energy required to break a block with the given hardness. Instantly breakable blocks only require half the energy.
    public static int getDestroyEnergy(int baseDestroyEnergy, float hardness) {
        return hardness == 0 ? Math.max(baseDestroyEnergy / 2, 1) : baseDestroyEnergy;
    }

    /// Calculates the destroy speed of a tool, reducing it based on how much energy is available if there isn't enough to break the block.
    ///
    /// @param energyRequired The energy required to break the block.
    /// @param baseEfficiency The efficiency to scale down when there is not enough energy.
    /// @param efficiency     The efficiency to use when there is enough energy.
    public static float getDestroySpeed(ItemStack stack, int energyRequired, float baseEfficiency, float efficiency) {
        EnergyHandler energyHandler = AutomatedEnergyHandler.manual(Capabilities.ENERGY.getCapability(ItemAccessUtils.sideEffectFreeAccess(stack)));
        if (energyHandler == null) {
            return 0;
        }
        //Protect against any mods that might be doing transactional logic, such as if an auto clicker validates it has enough energy before calling this method
        try (Transaction simulation = TransactionHelper.openTransactionSafe()) {
            int energyAvailable = energyHandler.extract(energyRequired, simulation);
            if (energyAvailable < energyRequired) {
                //If we can't extract all the energy we need to break it go at base speed reduced by how much we actually have available
                return baseEfficiency * ((float) energyAvailable / energyRequired);
            }
        }
        return efficiency;
    }

    /// Uses the energy for breaking a block, and if the entity is a player that is not in creative or spectator and there is enough energy left to break another
    /// block, lets the tool mine additional blocks.
    public static void mineBlock(ItemStack stack, Level world, LivingEntity entity, int energyRequired, AreaMiner areaMiner) {
        EnergyHandler energyHandler = AutomatedEnergyHandler.manual(Capabilities.ENERGY.getCapability(ItemAccess.forStack(stack)));
        if (energyHandler != null) {
            //Protect against any mods that might be doing transactional logic, such as if an auto clicker validates it has enough energy before calling this method
            try (Transaction transaction = TransactionHelper.openTransactionSafe()) {
                energyHandler.extract(energyRequired, transaction);
                //AOE/vein mining handling
                if (!world.isClientSide() && entity instanceof ServerPlayer player && MekanismUtils.isPlayingMode(player)) {
                    boolean hasEnergyToVeinMine;
                    try (Transaction simulation = Transaction.open(transaction)) {
                        hasEnergyToVeinMine = energyHandler.extract(energyRequired, simulation) == energyRequired;
                    }
                    if (hasEnergyToVeinMine) {
                        areaMiner.mine(energyHandler, player, transaction);
                    }
                }
                transaction.commit();
            }
        }
    }

    /// Uses the given amount of energy for an attack.
    public static void useAttackEnergy(ItemStack stack, int energy) {
        EnergyHandler energyHandler = AutomatedEnergyHandler.manual(Capabilities.ENERGY.getCapability(ItemAccess.forStack(stack)));
        if (energyHandler != null) {
            //Try to extract full energy, even if we have a lower damage amount this is fine as that just means
            // we don't have enough energy, but we will remove as much as we can, which is how much corresponds
            // to the amount of damage we will actually do
            //Protect against any mods that might be doing transactional logic, such as if an auto clicker validates it has enough energy before calling this method
            try (Transaction transaction = TransactionHelper.openTransactionSafe()) {
                energyHandler.extract(energy, transaction);
                transaction.commit();
            }
        }
    }

    /// @return How much of the given energy cost the tool has stored, between zero and one. Used to reduce the damage of attacks when there is not enough energy.
    public static double getAttackEnergyLevel(ItemStack stack, int energyCost) {
        EnergyHandler energyHandler = Capabilities.ENERGY.getCapability(ItemAccessUtils.sideEffectFreeAccess(stack));
        int energy = energyHandler == null ? 0 : energyHandler.getAmountAsInt();
        return MathUtils.divideToLevel(energy, energyCost);
    }

    @FunctionalInterface
    public interface AreaMiner {

        /// Mines any additional blocks, such as for vein mining or blasting.
        void mine(EnergyHandler energyHandler, ServerPlayer player, TransactionContext transaction);
    }
}
