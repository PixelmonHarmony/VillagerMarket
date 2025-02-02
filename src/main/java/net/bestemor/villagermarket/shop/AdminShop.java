package net.bestemor.villagermarket.shop;

import net.bestemor.core.config.ConfigManager;
import net.bestemor.core.config.CurrencyBuilder;
import net.bestemor.villagermarket.VMPlugin;
import net.bestemor.villagermarket.event.interact.BuyShopItemsEvent;
import net.bestemor.villagermarket.event.interact.SellShopItemsEvent;
import net.bestemor.villagermarket.event.interact.TradeShopItemsEvent;
import net.bestemor.villagermarket.utils.VMUtils;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.math.BigDecimal;
import java.util.Date;

public class AdminShop extends VillagerShop {

    public AdminShop(VMPlugin plugin, File file) {
        super(plugin, file);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity entity = VMUtils.getEntity(entityUUID);
            setShopName(entity == null ? null : entity.getCustomName());
            shopfrontHolder.load();
            isLoaded = true;
        });
    }

    /** Buys item/command from the admin shop */
    @Override
    protected void buyItem(int slot, Player player, boolean bulkMode) {
        ShopItem shopItem = shopfrontHolder.getItemList().get(slot);
        Economy economy = plugin.getEconomy();

        BigDecimal pricePerUnit = shopItem.getSellPrice();
        int amountPerUnit = shopItem.getAmount();

        int finalUnits = 1;

        if (bulkMode) {
            if (shopItem.isItemTrade()) {
                int tradeHas = countItems(player, shopItem.getItemTrade());
                int neededPerUnit = shopItem.getItemTradeAmount();

                finalUnits = tradeHas / neededPerUnit;
                if (finalUnits < 1) {
                    player.sendMessage("§cYou don’t have enough trade items to buy more than 0 units!");
                    return;
                }
            } else {
                double playerBalance = economy.getBalance(player);
                double costPerUnit = pricePerUnit.doubleValue();

                int affordUnits = (costPerUnit <= 0.0)
                        ? Integer.MAX_VALUE
                        : (int) Math.floor(playerBalance / costPerUnit);

                int fitUnits = calculateMaxFitUnits(player, shopItem.getRawItem(), amountPerUnit);

                finalUnits = Math.min(affordUnits, fitUnits);

                if (finalUnits < 1) {
                    player.sendMessage("§cYou cannot afford or fit even 1 more unit of this item!");
                    return;
                }
            }
        }


        if (!shopItem.verifyPurchase(player, ItemMode.SELL, finalUnits)) {
            return;
        }

        int finalItemAmount = amountPerUnit * finalUnits;
        BigDecimal finalPrice = pricePerUnit.multiply(BigDecimal.valueOf(finalUnits));

        CurrencyBuilder message = ConfigManager.getCurrencyBuilder("messages.bought_item_as_customer")
                .replace("%amount%", String.valueOf(shopItem.getAmount()))
                .replace("%item%", shopItem.getItemName())
                .replace("%shop%", getShopName())
                .addPrefix();

        if (shopItem.isItemTrade()) {
            message.replace("%price%", shopItem.getItemTradeAmount() + "x " + shopItem.getItemTradeName());
        } else {
            message.replaceCurrency("%price%", finalPrice);
        }
        player.sendMessage(message.build());

        if (shopItem.isItemTrade()) {
            TradeShopItemsEvent tradeShopItemsEvent = new TradeShopItemsEvent(player,this, shopItem);
            Bukkit.getPluginManager().callEvent(tradeShopItemsEvent);
            if (tradeShopItemsEvent.isCancelled()) {
                return;
            }
            removeItems(player.getInventory(), shopItem.getItemTrade(), shopItem.getItemTradeAmount());
        } else {

            economy.withdrawPlayer(player, finalPrice.doubleValue());
            BigDecimal left = BigDecimal.valueOf(economy.getBalance(player));
            player.sendMessage(ConfigManager.getCurrencyBuilder("messages.money_left").replaceCurrency("%amount%", left).addPrefix().build());
            shopStats.addEarned(finalPrice.doubleValue());
        }

        shopStats.addSold(finalItemAmount);
        giveShopItem(player, shopItem);
        shopItem.incrementPlayerTrades(player);
        shopItem.incrementServerTrades();

        BuyShopItemsEvent buyShopItemsEvent = new BuyShopItemsEvent(player,this, shopItem);
        Bukkit.getPluginManager().callEvent(buyShopItemsEvent);

        player.playSound(player.getLocation(), ConfigManager.getSound("sounds.buy_item"), 1, 1);

        VMPlugin.log.add(new Date() + ": " + player.getName() + " bought " + finalItemAmount + "x " + shopItem.getType() + " from Admin Shop " + "(" + finalPrice.toPlainString() + ")");

    }

    /** Sells item to the admin shop */
    @Override
    protected void sellItem(int slot, Player player, boolean bulkMode) {
        ShopItem shopItem = shopfrontHolder.getItemList().get(slot);
        Economy economy = plugin.getEconomy();

        int amountPerUnit = shopItem.getAmount();
        BigDecimal pricePerUnit = shopItem.getBuyPrice();

        int finalUnits = 1;

        if (bulkMode) {
            int inInventory = countItems(player, shopItem.getRawItem());

            finalUnits = inInventory / amountPerUnit;
            if (finalUnits < 1) {
                player.sendMessage("§cYou don't have enough items to sell in bulk!");
                return;
            }
        }

        if (!shopItem.verifyPurchase(player, ItemMode.BUY, finalUnits)) {
            return;
        }

        int finalItemAmount = amountPerUnit * finalUnits;
        BigDecimal finalPrice = pricePerUnit.multiply(BigDecimal.valueOf(finalUnits));

        player.sendMessage(ConfigManager.getCurrencyBuilder("messages.sold_item_as_customer")
                .replace("%amount%", String.valueOf(shopItem.getAmount()))
                .replaceCurrency("%price%", finalPrice)
                .replace("%item%", shopItem.getItemName())
                .replace("%shop%", getShopName()).build());

        economy.depositPlayer(player, finalPrice.doubleValue());
        removeItems(player.getInventory(), shopItem.getRawItem(), shopItem.getAmount());
        shopItem.incrementPlayerTrades(player);
        shopItem.incrementServerTrades();
        shopStats.addBought(finalItemAmount);
        shopStats.addSpent(finalPrice.doubleValue());

        SellShopItemsEvent sellShopItemsEvent = new SellShopItemsEvent(player,this, shopItem);
        Bukkit.getPluginManager().callEvent(sellShopItemsEvent);

        player.playSound(player.getLocation(), ConfigManager.getSound("sounds.sell_item"), 0.5f, 1);

        String currency = config.getString("currency");
        String valueCurrency = (config.getBoolean("currency_before") ? currency + finalPrice : finalPrice + currency);
        VMPlugin.log.add(new Date() + ": " + player.getName() + " sold " + finalItemAmount + "x " + shopItem.getType() + " to: " + entityUUID + " (" + valueCurrency + ")");
    }

    @Override
    public String getModeCycle(String mode, boolean isItemTrade) {
        return ConfigManager.getString("menus.edit_item.mode_cycle.admin_shop." + (!isItemTrade ? mode : "item_trade"));
    }


    @Override
    public int getAvailable(ShopItem shopItem) {
        return -1;
    }

    /** Runs when a Player wants to buy a command */
    public void buyCommand(Player player, ShopItem shopItem) {
        Economy economy = plugin.getEconomy();

        BigDecimal price = shopItem.getSellPrice();
        if (economy.getBalance(player) < price.doubleValue()) {
            player.sendMessage(ConfigManager.getMessage("messages.not_enough_money"));
            return;
        }
        boolean bypass = player.hasPermission("villagermarket.bypass_limit");
        int limit = shopItem.getLimit();
        int serverTrades = shopItem.getServerTrades();
        int playerTrades = shopItem.getPlayerLimit(player);
        ShopItem.LimitMode limitMode = shopItem.getLimitMode();
        if (!bypass && limit > 0 && ((limitMode == ShopItem.LimitMode.SERVER && serverTrades >= limit) || (limitMode == ShopItem.LimitMode.PLAYER && playerTrades >= limit))) {
            player.sendMessage(ConfigManager.getMessage("messages.reached_command_limit"));
            return;
        }
        economy.withdrawPlayer(player, price.doubleValue());

        if (shopItem.getCommands() != null && !shopItem.getCommands().isEmpty()) {
            ConsoleCommandSender sender = Bukkit.getConsoleSender();
            for (String command : shopItem.getCommands()) {
                Bukkit.dispatchCommand(sender, command.replaceAll("%player%", player.getName()));
            }
        }

        shopItem.incrementPlayerTrades(player);
        shopItem.incrementServerTrades();
    }
}
