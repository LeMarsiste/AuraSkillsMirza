package dev.aurelium.auraskills.bukkit.trait;

import dev.aurelium.auraskills.api.trait.Trait;
import dev.aurelium.auraskills.api.trait.Traits;
import dev.aurelium.auraskills.api.util.NumberUtil;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import org.bukkit.entity.Player;

import java.util.Locale;

public class CritDamageReductionTrait extends TraitImpl {

    CritDamageReductionTrait(AuraSkills plugin) {
        super(plugin, Traits.CRIT_DAMAGE_REDUCTION);
    }

    @Override
    public double getBaseLevel(Player player, Trait trait) {
        return Traits.CRIT_DAMAGE_REDUCTION.optionDouble("base");
    }

    @Override
    public String getMenuDisplay(double value, Trait trait, Locale locale) {
        return NumberUtil.format1(value) + "%";
    }

}
