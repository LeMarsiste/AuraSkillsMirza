package dev.aurelium.auraskills.bukkit.listeners;

import dev.aurelium.auraskills.api.damage.DamageMeta;
import dev.aurelium.auraskills.api.damage.DamageModifier;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import dev.aurelium.auraskills.api.trait.Traits;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.trait.CritChanceTrait;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.concurrent.TimeUnit;

public class CriticalHandler implements Listener {

    private final AuraSkills plugin;

    public CriticalHandler(AuraSkills plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void damageListener(DamageEvent event) {
        DamageMeta meta = event.getDamageMeta();
        Player attacker = meta.getAttackerAsPlayer();
        Player target = meta.getTargetAsPlayer();

        if (attacker != null &&
                plugin.configBoolean(Option.valueOf("CRITICAL_ENABLED_" + event.getDamageMeta().getDamageType().name()))) {
            User user = plugin.getUser(attacker);
            User defender = plugin.getUser(target);
            meta.addAttackModifier(getCrit(attacker, user,defender));
        }
    }

    private DamageModifier getCrit(Player player, User user,User defender) {
        if (!isCrit(defender,user)) {
            return DamageModifier.none();
        }
        // Set metadata for holograms to detect
        player.setMetadata("skillsCritical", new FixedMetadataValue(plugin, true));
        plugin.getScheduler().scheduleAtEntity(player, () -> player.removeMetadata("skillsCritical", plugin), 50, TimeUnit.MILLISECONDS);

        double value = (user.getEffectiveTraitLevel(Traits.CRIT_DAMAGE)-defender.getEffectiveTraitLevel(Traits.CRIT_DAMAGE_REDUCTION)) / 100;
        return new DamageModifier(value, DamageModifier.Operation.ADD_COMBINED);
    }

    private boolean isCrit(User defender,User user) {
        return plugin.getTraitManager().getTraitImpl(CritChanceTrait.class).isCrit(user,defender);
    }

}
