package forge.game.ability.effects;

import java.util.*;

import com.google.common.collect.Sets;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerView;
import forge.util.CardTranslation;
import org.apache.commons.lang3.StringUtils;

import forge.card.CardType;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardPredicates.Presets;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.Lang;
import forge.util.Localizer;

public class ChooseCardEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        final int numCards = sa.hasParam("Amount") ?
                AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("Amount"), sa) : 1;

        sb.append(Lang.joinHomogenous(getTargetPlayers(sa))).append(" ");
        if (sa.hasParam("Mandatory")) {
            sb.append(getTargetPlayers(sa).size() == 1 ? "chooses " : "choose ");
        } else {
            sb.append("may choose ");
        }
        String desc = sa.getParamOrDefault("ChoiceDesc", "card");
        if (!desc.contains("card") && !desc.contains("control")) {
            desc = desc + " card";
        }
        sb.append(Lang.nounWithNumeralExceptOne(numCards, desc));
        if (sa.hasParam("FromDesc")) {
            sb.append(" ").append(sa.getParam("FromDesc"));
        }
        sb.append(".");

        return sb.toString();
    }

    @Override
    public void resolve(SpellAbility sa) {
        final Card host = sa.getHostCard();
        final Player activator = sa.getActivatingPlayer();
        final Game game = activator.getGame();
        final CardCollection chosen = new CardCollection();

        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final List<Player> tgtPlayers = getTargetPlayers(sa);

        ZoneType choiceZone = ZoneType.Battlefield;
        if (sa.hasParam("ChoiceZone")) {
            choiceZone = ZoneType.smartValueOf(sa.getParam("ChoiceZone"));
        }
        CardCollectionView choices = sa.hasParam("AllCards") ? game.getCardsInGame() : game.getCardsIn(choiceZone);
        if (sa.hasParam("Choices")) {
            choices = CardLists.getValidCards(choices, sa.getParam("Choices"), activator, host, sa);
        }
        if (sa.hasParam("TargetControls")) {
            choices = CardLists.filterControlledBy(choices, tgtPlayers.get(0));
        }
        if (sa.hasParam("DefinedCards")) {
            choices = AbilityUtils.getDefinedCards(host, sa.getParam("DefinedCards"), sa);
        }
        if (sa.hasParam("IncludeSpellsOnStack")) {
            CardCollectionView stack = game.getCardsIn(ZoneType.Stack);
            CardCollection combined = new CardCollection();
            combined.addAll(stack);
            combined.addAll(choices);
            choices = combined;
        }

        final String amountValue = sa.getParamOrDefault("Amount", "1");
        int validAmount;
        if (StringUtils.isNumeric(amountValue)) {
            validAmount = Integer.parseInt(amountValue);
        } else if (amountValue.equals("Random")) {
            validAmount = Aggregates.randomInt(0, choices.size());
        } else {
            validAmount = AbilityUtils.calculateAmount(host, amountValue, sa);
        }
        final int minAmount = sa.hasParam("MinAmount") ? Integer.parseInt(sa.getParam("MinAmount")) : validAmount;

        if (validAmount <= 0) {
            return;
        }

        for (final Player p : tgtPlayers) {
            boolean dontRevealToOwner = true;
            if (sa.hasParam("EachBasicType")) {
                // Get all lands, 
                List<Card> land = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), Presets.LANDS);
                String eachBasic = sa.getParam("EachBasicType");
                if (eachBasic.equals("Controlled")) {
                    land = CardLists.filterControlledBy(land, p);
                }
                
                // Choose one of each BasicLand given special place
                for (final String type : CardType.getBasicTypes()) {
                    final CardCollectionView cl = CardLists.getType(land, type);
                    if (!cl.isEmpty()) {
                        final String prompt = Localizer.getInstance().getMessage("lblChoose") + " " + Lang.nounWithAmount(1, type);
                        Card c = p.getController().chooseSingleEntityForEffect(cl, sa, prompt, false, null);
                        if (c != null) {
                            chosen.add(c);
                        }
                    }
                }
            } else if (sa.hasParam("ChooseParty")) {
                Set<String> partyTypes = Sets.newHashSet("Cleric", "Rogue", "Warrior", "Wizard");
                for (final String type : partyTypes) {
                    CardCollection valids = CardLists.filter(p.getCardsIn(ZoneType.Battlefield),
                            CardPredicates.isType(type));
                    for (Card alreadyChosen : chosen) {
                        valids.remove(alreadyChosen);
                    }
                    if (!valids.isEmpty()) {
                        final String prompt = Localizer.getInstance().getMessage("lblChoose") + " " +
                                Lang.nounWithNumeralExceptOne(1, type);
                        Card c = p.getController().chooseSingleEntityForEffect(valids, sa, prompt, true, null);
                        if (c != null) {
                            chosen.add(c);
                        }
                    }
                }
            } else if (sa.hasParam("WithTotalPower")) {
                final int totP = AbilityUtils.calculateAmount(host, sa.getParam("WithTotalPower"), sa);
                CardCollection negativeCreats = CardLists.filterLEPower(p.getCreaturesInPlay(), -1);
                int negativeNum = Aggregates.sum(negativeCreats, CardPredicates.Accessors.fnGetNetPower);
                CardCollection creature = CardLists.filterLEPower(p.getCreaturesInPlay(), totP - negativeNum);
                CardCollection chosenPool = new CardCollection();
                int chosenP = 0;
                while (!creature.isEmpty()) {
                    Card c = p.getController().chooseSingleEntityForEffect(creature, sa,
                            Localizer.getInstance().getMessage("lblSelectCreatureWithTotalPowerLessOrEqualTo", (totP - chosenP - negativeNum))
                                    + "\r\n(" + Localizer.getInstance().getMessage("lblSelected") + ":" + chosenPool + ")\r\n(" + Localizer.getInstance().getMessage("lblTotalPowerNum", chosenP) + ")", chosenP <= totP, null);
                    if (c == null) {
                        if (p.getController().confirmAction(sa, PlayerActionConfirmMode.OptionalChoose, Localizer.getInstance().getMessage("lblCancelChooseConfirm"), null)) {
                            break;
                        }
                    } else {
                        chosenP += c.getNetPower();
                        chosenPool.add(c);
                        negativeCreats.remove(c);
                        negativeNum = Aggregates.sum(negativeCreats, CardPredicates.Accessors.fnGetNetPower);
                        creature = CardLists.filterLEPower(p.getCreaturesInPlay(), totP - chosenP - negativeNum);
                        creature.removeAll(chosenPool);
                    }
                }
                chosen.addAll(chosenPool);
            } else if (sa.hasParam("WithDifferentPowers")) {
                String restrict = sa.getParam("Choices");
                CardCollection chosenPool = new CardCollection();
                String title = Localizer.getInstance().getMessage("lblChooseCreature");
                Card choice = null;
                while (!choices.isEmpty() && chosenPool.size() < validAmount) {
                    boolean optional = chosenPool.size() >= minAmount;
                    CardCollection creature = (CardCollection) choices;
                    if (!chosenPool.isEmpty()) {
                        title = Localizer.getInstance().getMessage("lblChooseCreatureWithDiffPower");
                    }
                    choice = p.getController().chooseSingleEntityForEffect(creature, sa, title, optional, null);
                    if (choice == null) {
                        break;
                    }
                    chosenPool.add(choice);
                    restrict = restrict + (restrict.contains(".") ? "+powerNE" : ".powerNE") + choice.getNetPower();
                    choices = CardLists.getValidCards(choices, restrict, activator, host, sa);
                }
                if (choice != null) {
                    chosenPool.add(choice);
                }
                chosen.addAll(chosenPool);
            } else if (sa.hasParam("EachDifferentPower")) {
                List<Integer> powers = new ArrayList<>();
                CardCollection chosenPool = new CardCollection();
                for (Card c : choices) {
                    int pow = c.getNetPower();
                    if (!powers.contains(pow)) {
                        powers.add(c.getNetPower());
                    }
                }
                Collections.sort(powers);
                String re = sa.getParam("Choices");
                re = re + (re.contains(".") ? "+powerEQ" : ".powerEQ");
                for (int i : powers) {
                    String restrict = re + i;
                    CardCollection valids = CardLists.getValidCards(choices, restrict, activator, host, sa);
                    Card choice = p.getController().chooseSingleEntityForEffect(valids, sa,
                            Localizer.getInstance().getMessage("lblChooseCreatureWithXPower", i), false, null);
                    chosenPool.add(choice);
                }
                chosen.addAll(chosenPool);
            } else if (sa.hasParam("ControlAndNot")) {
                String title = sa.hasParam("ChoiceTitle") ? sa.getParam("ChoiceTitle") : Localizer.getInstance().getMessage("lblChooseCreature");
                // Targeted player (p) chooses N creatures that belongs to them
                CardCollection tgtPlayerCtrl = CardLists.filterControlledBy(choices, p);
                chosen.addAll(p.getController().chooseCardsForEffect(tgtPlayerCtrl, sa, title + " " + "you control", minAmount, validAmount,
                    !sa.hasParam("Mandatory"), null));
                // Targeted player (p) chooses N creatures that don't belong to them
                CardCollection notTgtPlayerCtrl = new CardCollection(choices);
                notTgtPlayerCtrl.removeAll(tgtPlayerCtrl);
                chosen.addAll(p.getController().chooseCardsForEffect(notTgtPlayerCtrl, sa, title + " " + "you don't control", minAmount, validAmount,
                    !sa.hasParam("Mandatory"), null));

            } else if ((tgt == null) || p.canBeTargetedBy(sa)) {
                if (sa.hasParam("AtRandom") && !choices.isEmpty()) {
                    Aggregates.random(choices, validAmount, chosen);
                    dontRevealToOwner = false;
                } else {
                    String title = sa.hasParam("ChoiceTitle") ? sa.getParam("ChoiceTitle") : Localizer.getInstance().getMessage("lblChooseaCard") + " ";
                    if (sa.hasParam ("ChoiceTitleAppend")) {
                        String tag = "";
                        String value = sa.getParam("ChoiceTitleAppend");
                        if (value.startsWith("Defined ")) {
                            tag = AbilityUtils.getDefinedPlayers(host, value.substring(8), sa).toString();
                        } else if (value.equals("ChosenType")) {
                            tag = host.getChosenType();
                        }
                        if (!tag.equals("")) {
                            title = title + " (" + tag +")";
                        }
                    }
                    if (sa.hasParam("QuasiLibrarySearch")) {
                        final Player searched = AbilityUtils.getDefinedPlayers(host,
                                sa.getParam("QuasiLibrarySearch"), sa).get(0);
                        final int fetchNum = Math.min(searched.getCardsIn(ZoneType.Library).size(), 4);
                        CardCollectionView shown = !p.hasKeyword("LimitSearchLibrary")
                                ? searched.getCardsIn(ZoneType.Library) : searched.getCardsIn(ZoneType.Library, fetchNum);
                        DelayedReveal delayedReveal = new DelayedReveal(shown, ZoneType.Library, PlayerView.get(searched),
                                CardTranslation.getTranslatedName(host.getName()) + " - " +
                                        Localizer.getInstance().getMessage("lblLookingCardIn") + " ");
                        Card choice = p.getController().chooseSingleEntityForEffect(choices, delayedReveal, sa, title,
                                !sa.hasParam("Mandatory"), p, null);
                        if (choice == null) {
                            return;
                        }
                        chosen.add(choice);
                    } else {
                        chosen.addAll(p.getController().chooseCardsForEffect(choices, sa, title, minAmount, validAmount,
                                !sa.hasParam("Mandatory"), null));
                    }
                }
            }
            if (sa.hasParam("Reveal") && !sa.hasParam("SecretlyChoose")) {
                game.getAction().reveal(chosen, p, dontRevealToOwner, sa.hasParam("RevealTitle") ?
                        sa.getParam("RevealTitle") : Localizer.getInstance().getMessage("lblChosenCards") + " ");
            }
        }
        if (sa.hasParam("Reveal") && sa.hasParam("SecretlyChoose")) {
            for (final Player p : tgtPlayers) {
                game.getAction().reveal(chosen, p, true, sa.hasParam("RevealTitle") ?
                        sa.getParam("RevealTitle") : Localizer.getInstance().getMessage("lblChosenCards") + " ");
            }
        }
        host.setChosenCards(chosen);
        if (sa.hasParam("RememberChosen")) {
            host.addRemembered(chosen);
        }
        if (sa.hasParam("ForgetChosen")) {
            host.removeRemembered(chosen);
        }
        if (sa.hasParam("ImprintChosen")) {
            host.addImprintedCards(chosen);
        }
    }
}
