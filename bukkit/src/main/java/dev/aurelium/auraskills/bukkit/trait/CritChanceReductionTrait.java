package dev.aurelium.auraskills.bukkit.trait;

import dev.aurelium.auraskills.api.trait.Trait;
import dev.aurelium.auraskills.api.trait.Traits;
import dev.aurelium.auraskills.api.util.NumberUtil;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Random;

public class CritChanceReductionTrait extends TraitImpl {


    CritChanceReductionTrait(AuraSkills plugin) {
        super(plugin, Traits.CRIT_CHANCE_REDUCTION);
    }

    @Override
    public double getBaseLevel(Player player, Trait trait) {
        return Traits.CRIT_CHANCE_REDUCTION.optionDouble("base");
    }

    @Override
    public String getMenuDisplay(double value, Trait trait, Locale locale) {
        return NumberUtil.format1(value) + "%";
    }


}
