package dev.aurelium.auraskills.bukkit.item;

import dev.aurelium.auraskills.api.item.ModifierType;
import dev.aurelium.auraskills.api.skill.Multiplier;
import dev.aurelium.auraskills.api.stat.ReloadableIdentifier;
import dev.aurelium.auraskills.api.stat.Stat;
import dev.aurelium.auraskills.api.stat.StatModifier;
import dev.aurelium.auraskills.api.trait.Trait;
import dev.aurelium.auraskills.api.trait.TraitModifier;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.scheduler.TaskRunnable;
import dev.aurelium.auraskills.common.stat.StatManager;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ItemListener implements Listener {

    private final AuraSkills plugin;
    private final ItemStateManager stateManager;
    private final StatManager statManager;

    public ItemListener(AuraSkills plugin) {
        this.plugin = plugin;
        this.statManager = plugin.getStatManager();
        this.stateManager = new ItemStateManager(plugin);
        scheduleTask();
    }

    public void scheduleTask() {
        var task = new TaskRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // Gets stored and held items
                    ItemStack held = player.getInventory().getItemInMainHand();

                    stateManager.changeItemInSlot(plugin.getUser(player), player, held, EquipmentSlot.HAND);
                }
            }
        };
        plugin.getScheduler().timerSync(task, 0L, plugin.configInt(Option.MODIFIER_ITEM_CHECK_PERIOD) * 50L, TimeUnit.MILLISECONDS);
        scheduleOffHandTask();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.configBoolean(Option.MODIFIER_ITEM_ENABLE_OFF_HAND)) {
            return;
        }
        Player player = event.getPlayer();
        User user = plugin.getUser(player);

        // Get items switched
        ItemStack itemOffHand = event.getOffHandItem();
        if (itemOffHand == null) {
            itemOffHand = new ItemStack(Material.AIR);
        }
        ItemStack itemMainHand = event.getMainHandItem();
        if (itemMainHand == null) {
            itemMainHand = new ItemStack(Material.AIR);
        }

        Set<ReloadableIdentifier> toReload = new HashSet<>();

        toReload.addAll(stateManager.changeItemInSlot(user, player, itemOffHand, EquipmentSlot.OFF_HAND, false, false, false));
        toReload.addAll(stateManager.changeItemInSlot(user, player, itemMainHand, EquipmentSlot.HAND, false, false, false));

        stateManager.reloadIdentifiers(user, toReload);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void OnConsume(PlayerItemConsumeEvent event){
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        User playerData = plugin.getUser(player);

        ItemStack consumedItem = event.getItem();
        SkillsItem skillItem = new SkillsItem(consumedItem,plugin);
        boolean meetsRequirements = skillItem.meetsRequirements(ModifierType.ITEM, player);

        Set<Stat> statsToReload = new HashSet<>();
        Set<Trait> traitsToReload = new HashSet<>();

        for (StatModifier modifier : skillItem.getStatModifiers(ModifierType.ITEM)) {
            // Removes the old modifier from main hand
            StatModifier consumedModifier = new StatModifier(modifier.stat().name() + ".Consumed", modifier.stat(), modifier.value());
            // Add the existing modifier to the new one
            if (playerData.getStatModifier(consumedModifier.name()) != null){
                consumedModifier = new StatModifier(consumedModifier.name(),modifier.stat(),
                        modifier.value()+playerData.getStatModifier(consumedModifier.name()).value());
                if (consumedModifier.value() > 50)
                    consumedModifier = new StatModifier(consumedModifier.name(),
                            consumedModifier.stat(),
                            50);
                playerData.removeStatModifier(consumedModifier.name());
            }
            playerData.removeStatModifier(modifier.stat().name(), false);
            // Add new one if meets requirements
            if (meetsRequirements) {
                playerData.addStatModifier(consumedModifier, false);
            }
            // Reload check stuff
            statsToReload.add(modifier.stat());
        }
        for (TraitModifier modifier : skillItem.getTraitModifiers(ModifierType.ITEM)) {
            TraitModifier consumedModifier = new TraitModifier(modifier.trait().name() + ".Consumed", modifier.trait(), modifier.value());
            // Add the existing modifier to the new one
            if (playerData.getTraitModifier(consumedModifier.name()) != null){
                consumedModifier = new TraitModifier(consumedModifier.name(),modifier.trait(),
                        modifier.value()+playerData.getTraitModifier(consumedModifier.name()).value());
                playerData.removeTraitModifier(consumedModifier.name());
            }
            playerData.removeTraitModifier(modifier.trait().name(), false);
            if (consumedModifier.value() > 50)
                consumedModifier = new TraitModifier(consumedModifier.name(),
                        consumedModifier.trait(),
                        50);
            // Add new one if meets requirements
            if (meetsRequirements) {
                playerData.addTraitModifier(consumedModifier, false);
            }
            // Reload check stuff
            traitsToReload.add(modifier.trait());
        }
        for (Multiplier multiplier : skillItem.getMultipliers(ModifierType.ITEM)) {
            Multiplier consumedModifier = new Multiplier(multiplier.name() + ".Consumed", multiplier.skill(), multiplier.value());
            // Add the existing modifier to the new one
            if (playerData.getMultipliers().get(consumedModifier.name()) != null){
                consumedModifier = new Multiplier(consumedModifier.name(),consumedModifier.skill(),
                        multiplier.value()+playerData.getMultipliers().get(consumedModifier.name()).value());
                playerData.removeMultiplier(consumedModifier.name());
            }
            playerData.removeMultiplier(multiplier.name());
            if (meetsRequirements) {
                playerData.addMultiplier(consumedModifier);
            }
        }

        // Reload stats
        for (Stat stat : statsToReload) {
            statManager.reload(plugin.getUser(player), stat);
        }
        for (Trait trait : traitsToReload) {
            statManager.reload(plugin.getUser(player), trait);
        }

    }
    public void scheduleOffHandTask() {
        var task = new TaskRunnable() {
            @Override
            public void run() {
                if (!plugin.configBoolean(Option.MODIFIER_ITEM_ENABLE_OFF_HAND)) {
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // Gets stored and held items
                    ItemStack held = player.getInventory().getItemInOffHand();

                    stateManager.changeItemInSlot(plugin.getUser(player), player, held, EquipmentSlot.OFF_HAND);
                }
            }
        };
        plugin.getScheduler().timerSync(task, 0L, plugin.configInt(Option.MODIFIER_ITEM_CHECK_PERIOD) * 50L, TimeUnit.MILLISECONDS);
    }

}
