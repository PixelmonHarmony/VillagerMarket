package net.bestemor.villagermarket.shop;

import net.bestemor.core.config.ConfigManager;
import net.bestemor.core.config.ListBuilder;
import net.bestemor.core.config.VersionUtils;
import net.bestemor.villagermarket.VMPlugin;
import net.bestemor.villagermarket.menu.EditItemMenu;
import net.bestemor.villagermarket.menu.StorageHolder;
import net.bestemor.villagermarket.utils.VMUtils;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

import static net.bestemor.villagermarket.shop.ItemMode.*;

public class ShopItem {

    public enum LimitMode {
        SERVER,
        PLAYER
    }

    private final VMPlugin plugin;
    private final ItemStack item;

    private final int slot;

    private boolean isAdmin;

    private List<String> editorLore = new ArrayList<>();

    private BigDecimal sellPrice;
    private BigDecimal buyPrice;
    private int amount;
    private int itemTradeAmount = 0;
    private ItemStack itemTrade;

    private int discount = 0;
    private int limit = 0;
    private LimitMode limitMode = LimitMode.PLAYER;
    private String cooldown = "never";
    private int serverTrades = 0;
    private Instant nextReset;
    private Instant discountEnd;
    private final Map<UUID, Integer> playerLimits = new HashMap<>();

    int storageAmount = 0;
    int available = -1;

    private ItemMode mode = SELL;

    private final List<String> commands = new ArrayList<>();

    public ShopItem(VMPlugin plugin, ItemStack item, int slot) {
        this.plugin = plugin;
        this.slot = slot;
        this.item = item;
        this.amount = item.getAmount();
    }
    public ShopItem(VMPlugin plugin, ConfigurationSection section) {
        this.plugin = plugin;
        this.slot = Integer.parseInt(section.getName());

        this.item = section.getItemStack("item");
        if (item == null) {
            throw new NullPointerException("ItemStack is null!");
        }
        this.amount = section.getInt("amount") == 0 ? item.getAmount() : section.getInt("amount");

        Object trade = section.get("price");
        double d = section.getDouble("price");

        if (d != 0) {
            this.sellPrice = new BigDecimal(String.valueOf(d));
        } else if (trade instanceof ItemStack) {
            this.itemTrade = (ItemStack) trade;
            this.itemTradeAmount = section.getInt("trade_amount") == 0 ? itemTrade.getAmount() : section.getInt("trade_amount");
            this.sellPrice = BigDecimal.ZERO;
        }
        this.buyPrice = new BigDecimal(String.valueOf(section.getDouble("buy_price")));

        List<String> commands = section.getStringList("command");
        if (!commands.isEmpty()) {
            this.mode = COMMAND;
            this.commands.addAll(commands);
        }

        this.mode = ItemMode.valueOf(section.getString("mode"));
        this.limit = section.getInt("buy_limit");
        this.limitMode = section.getString("limit_mode") == null ? LimitMode.PLAYER : LimitMode.valueOf(section.getString("limit_mode"));
        this.cooldown = section.getString("cooldown");
        this.serverTrades = section.getInt("server_trades");

        if (this.cooldown != null && !this.cooldown.equals("never")) {
            this.nextReset = Instant.ofEpochSecond(section.getLong("next_reset"));
        }
        if (section.getConfigurationSection("discount") != null && section.getLong("discount.end") > Instant.now().getEpochSecond()) {
            this.discount = section.getInt("discount.amount");
            this.discountEnd = Instant.ofEpochSecond(section.getLong("discount.end"));
        }

        ConfigurationSection limits = section.getConfigurationSection("limits");
        if (limits != null) {
            for (String uuid : limits.getKeys(false)) {
                playerLimits.put(UUID.fromString(uuid), limits.getInt(uuid));
            }
        }
    }

    public BigDecimal getSellPrice() {
        return getSellPrice(true);
    }
    public BigDecimal getSellPrice(boolean applyDiscount) {
        if (sellPrice == null) {
            return BigDecimal.ZERO;
        } else if (!applyDiscount || discount <= 0) {
            return sellPrice;
        } else {
            return sellPrice.subtract(sellPrice.multiply(BigDecimal.valueOf(discount / 100.0)));
        }
    }
    public BigDecimal getBuyPrice() {
        return getBuyPrice(true);
    }
    public BigDecimal getBuyPrice(boolean applyDiscount) {
        if (mode != BUY_AND_SELL) {
            return getSellPrice();
        } else if (buyPrice == null) {
            return BigDecimal.ZERO;
        } else if (!applyDiscount || discount <= 0) {
            return buyPrice;
        } else {
            return buyPrice.subtract(buyPrice.multiply(BigDecimal.valueOf(discount / 100.0)));
        }
    }
    public Material getType() { return item.getType(); }
    public int getSlot() {
        return slot;
    }
    public ItemMode getMode() {
        return mode;
    }
    public int getLimit() {
        return limit;
    }
    public int getAmount() { return amount; }
    public List<String> getCommands() {
        return new ArrayList<>(commands);
    }
    public boolean isItemTrade() {
        return this.itemTrade != null;
    }
    public ItemStack getItemTrade() {
        return itemTrade;
    }
    public int getServerTrades() {
        return serverTrades;
    }
    public LimitMode getLimitMode() {
        return limitMode;
    }
    public String getCooldown() {
        return cooldown;
    }
    public Instant getNextReset() {
        return nextReset;
    }
    public int getItemTradeAmount() {
        return itemTradeAmount;
    }

    public Map<UUID, Integer> getPlayerLimits() {
        return playerLimits;
    }
    public void setAdmin(boolean admin) {
        isAdmin = admin;
    }
    public void setSellPrice(BigDecimal sellPrice) {
        this.sellPrice = sellPrice;
    }
    public void setBuyPrice(BigDecimal buyPrice) {
        this.buyPrice = buyPrice;
    }
    public void setLimit(int limit) {
        this.limit = limit;
    }
    public void setAmount(int amount) {
        this.item.setAmount(amount > item.getMaxStackSize() ? 1 : amount);
        this.amount = amount;
    }
    public void addCommand(String command) {
        this.mode = ItemMode.COMMAND;
        this.commands.add(command);
    }
    public void setItemTrade(ItemStack itemTrade, int amount) {
        this.itemTrade = itemTrade;
        this.itemTradeAmount = amount;
        if (itemTrade != null) {
            this.mode = SELL;
        }
    }

    public void resetCommand() {
        this.commands.clear();
    }


    public void setCooldown(String cooldown) {
        String amount = cooldown.substring(0, cooldown.length() - 1);
        String unit = cooldown.substring(cooldown.length() - 1);
        if (!VMUtils.isInteger(amount) || (!unit.equals("m") && !unit.equals("h") && !unit.equals("d"))) {
            this.cooldown = null;
            return;
        } else {
            this.cooldown = cooldown;
        }
        resetCooldown();
    }

    public void openEditor(Player player, VillagerShop shop, int page) {
        new EditItemMenu(plugin, shop, this, page).open(player);
    }

    public void cycleTradeMode() {
        if (!isItemTrade()) {
            switch (mode) {
                case SELL:
                    mode = ItemMode.BUY;
                    break;
                case BUY:
                    mode = BUY_AND_SELL;
                    break;
                case BUY_AND_SELL:
                    mode = isAdmin ? ItemMode.COMMAND : ItemMode.SELL;
                    break;
                case COMMAND:
                    mode = SELL;
            }
        }
    }
    public int getPlayerLimit(Player player) {
        return playerLimits.getOrDefault(player.getUniqueId(), 0);
    }
    public void incrementPlayerTrades(Player player) {
        playerLimits.put(player.getUniqueId(), getPlayerLimit(player) + 1);
    }
    public void incrementServerTrades() {
        serverTrades ++;
    }
    private void reloadData(VillagerShop shop) {
        if (shop instanceof PlayerShop) {
            PlayerShop playerShop = (PlayerShop) shop;
            this.storageAmount = playerShop.getStorageHolder().getAmount(item.clone());
        }
        this.available = shop.getAvailable(this);
    }

    private void resetCooldown() {
        this.nextReset = VMUtils.getTimeFromNow(cooldown);
        if (nextReset.getEpochSecond() == 0) {
            this.cooldown = null;
        }
    }
    public void setDiscount(int discount, Instant discountEnd) {
        this.discount = discount;
        this.discountEnd = discountEnd;
    }
    public int getDiscount() {
        return discount;
    }

    public Instant getDiscountEnd() {
        if (discountEnd == null || discountEnd.getEpochSecond() == 0) {
            return null;
        }
        if (discountEnd.isBefore(Instant.now())) {
            return Instant.now();
        }
        if (discountEnd.isAfter(Instant.MAX)) {
            return Instant.now();
        }
        if (discountEnd.isBefore(Instant.MIN)) {
            return Instant.now();
        }
        return discountEnd;
    }

    public void clearLimits() {
        this.playerLimits.clear();
        this.serverTrades = 0;
        resetCooldown();
    }
    public void cycleLimitMode() {
        limitMode = limitMode == LimitMode.SERVER ? LimitMode.PLAYER : LimitMode.SERVER;
    }

    public void reloadMeta(VillagerShop shop) {
        reloadData(shop);
        editorLore = getLore("edit_shopfront", mode, null);
    }

    public String getItemName() {
        return getItemName(item);
    }

    public boolean verifyPurchase(Player player, ItemMode verifyMode, int finaUnits) {
        return verifyPurchase(player, verifyMode, null,null,finaUnits);
    }

    /**
     * Verifies if the transaction for `quantity` units is valid under the given mode.
     *
     * @param customer    The player performing the transaction
     * @param verifyMode  The ItemMode (SELL = shop->player, BUY = player->shop)
     * @param owner       The OfflinePlayer owner of the shop (null if AdminShop)
     * @param storage     The shop's StorageHolder (null if AdminShop or not relevant)
     * @param quantity    How many "units" of this shop item are being transacted
     * @return            true if the transaction is allowed; false otherwise
     */
    public boolean verifyPurchase(Player customer, ItemMode verifyMode, OfflinePlayer owner, StorageHolder storage, int quantity) {

        // 1) If the 'owner' is the same as the 'customer', deny self-transactions.
        if (owner != null && customer.getUniqueId().equals(owner.getUniqueId())) {
            customer.sendMessage(ConfigManager.getMessage(
                    "messages.cannot_" + (verifyMode == SELL ? "buy_from" : "sell_to") + "_yourself"));
            return false;
        }

        Economy economy = plugin.getEconomy();

        // We'll calculate total items and total price based on the requested `quantity`.

        // "Single unit" = getAmount() items.
        // If the transaction is "bulk," we do `totalItems = getAmount() * quantity`.
        // Similarly, total price = getSellPrice() or getBuyPrice() * quantity,
        // if using currency (not itemTrade).

        int singleUnitItems = getAmount();
        int totalItems = singleUnitItems * quantity;

        // Sell price from the shop's perspective => what the customer pays (if verifyMode == SELL)
        // or what the shop pays (if verifyMode == BUY). We'll compute it if not an itemTrade.
        // e.g. "shop sells item => player pays getSellPrice()"
        //      "shop buys item => player gets getBuyPrice()"
        // Because of how the code is structured:
        //    verifyMode == SELL => shopItem.getSellPrice() is cost for 1 "unit"
        //    verifyMode == BUY  => shopItem.getBuyPrice() is cost for 1 "unit"
        BigDecimal unitPrice = (verifyMode == SELL ? getSellPrice() : getBuyPrice());
        BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));

        // 2) If the shop is "SELL" mode (shop->player), but itemTrade != null =>
        //    it’s an item-for-item trade. Must check the player's inventory
        //    for enough itemTrade items * itemTradeAmount * quantity.
        if (verifyMode == SELL && isItemTrade()) {
            int neededTradeItems = getItemTradeAmount() * quantity;
            if (getAmountInventory(itemTrade, customer.getInventory()) < neededTradeItems) {
                customer.sendMessage(ConfigManager.getMessage("messages.not_enough_in_inventory"));
                return false;
            }
        }

        // 3) If the shop is "SELL" mode (shop->player) with a physical storage =>
        //    check if the shop has enough items.
        //    i.e. 'storage.getAmount(...) < totalItems'
        //    This only applies if itemTrade == null (meaning the shop sells items for currency).
        if (verifyMode == SELL && !isItemTrade() && storage != null) {
            if (storage.getAmount(item.clone()) < totalItems) {
                customer.sendMessage(ConfigManager.getMessage("messages.not_enough_stock"));
                return false;
            }
        }

        // 4) If the shop is "SELL" mode => the player must pay the shop (no itemTrade).
        //    So check if the customer has enough money for totalPrice.
        if (verifyMode == SELL && !isItemTrade() && itemTrade == null) {
            if (economy.getBalance(customer) < totalPrice.doubleValue()) {
                customer.sendMessage(ConfigManager.getMessage("messages.not_enough_money"));
                return false;
            }
        }

        // 5) If the shop is "BUY" mode => the shop pays the player.
        //    If there's an owner, check if the owner has enough money to cover totalPrice.
        if (verifyMode == BUY && owner != null && !isItemTrade() && itemTrade == null) {
            if (economy.getBalance(owner) < totalPrice.doubleValue()) {
                customer.sendMessage(ConfigManager.getMessage("messages.owner_not_enough_money"));
                return false;
            }
        }

        // 6) If the shop is "BUY" mode => player sells items to the shop.
        //    Check if the player has at least `totalItems` in their inventory.
        //    (the code base uses 'getAmountInventory(item.clone(), ...).')
        if (verifyMode == BUY) {
            if (getAmountInventory(item.clone(), customer.getInventory()) < totalItems) {
                customer.sendMessage(ConfigManager.getMessage("messages.not_enough_in_inventory"));
                return false;
            }
        }

        // 7) Check "available" (some limit on how many more items can be bought or sold).
        //    If 'available != -1' => there's a limit.
        //    If 'totalItems' > 'available', transaction fails.
        //    For itemTrade, the code lumps them with "verifyMode == BUY" check,
        //    so we adapt it:
        if ((verifyMode == BUY || isItemTrade()) && available != -1 && totalItems > available) {
            // isItemTrade() => this treat it as "shop sells for itemTrade" or "shop buys with itemTrade"
            // It’s a bit ambiguous, but we follow the original logic.
            customer.sendMessage(ConfigManager.getMessage("messages.reached_" +
                    (isItemTrade() ? "buy" : "sell") + "_limit"));
            return false;
        }

        // 8) If it's an AdminShop with a limit set, check if the limit is reached
        //    for 'serverTrades' or 'playerLimits' (depends on limitMode).
        //    e.g. if limit is 100 but you're about to do 10 more trades, do we interpret
        //    that as 'serverTrades + quantity >= limit'?
        //    We'll assume yes.
        boolean bypass = customer.hasPermission("villagermarket.bypass_limit");
        if (isAdmin && !bypass && limit > 0) {
            int tradesSoFar = (limitMode == LimitMode.SERVER ? serverTrades : getPlayerLimit(customer));
            // If by adding 'quantity' we exceed the limit, fail
            if (tradesSoFar + quantity > limit) {
                customer.sendMessage(ConfigManager.getMessage("messages.reached_" +
                        (verifyMode == BUY ? "sell" : "buy") + "_limit"));
                return false;
            }
        }

        // If none of the checks failed, the transaction is valid
        return true;
    }

    private List<String> getLore(String path, ItemMode mode, Player p) {
        String typePath = (isAdmin ? "admin_shop." : "player_shop.");
        String modePath = isItemTrade() ? "trade" : mode.toString().toLowerCase();

        String reset = nextReset == null || nextReset.getEpochSecond() == 0 ? ConfigManager.getString("time.never") : ConfigManager.getTimeLeft(nextReset);
        String bought = String.valueOf(limitMode == LimitMode.SERVER || p == null ? serverTrades : getPlayerLimit(p));
        String limitInfo = limit == 0 ? ConfigManager.getString("quantity.unlimited") : String.valueOf(limit);

        String lorePath = "menus." + path + "." + typePath + (isAdmin && path.startsWith("edit") ? "standard" : modePath)  + "_lore";
        ListBuilder builder = ConfigManager.getListBuilder(lorePath)
                .replace("%amount%", String.valueOf(amount))
                .replace("%stock%", String.valueOf(storageAmount))
                .replace("%bought%", bought)
                .replace("%available%", String.valueOf(available))
                .replace("%mode%", ConfigManager.getString("menus.shopfront.modes." + modePath))
                .replace("%reset%", reset)
                .replace("%limit%", limitInfo);

        if (isItemTrade()) {
            builder.replace("%price%", getItemTradeAmount() + "x" + " " + getItemName(itemTrade));
        } else if (getSellPrice().equals(BigDecimal.ZERO)) {

            builder.replace("%price%", ConfigManager.getString("quantity.free"));
            builder.replace("%price_per_unit%", ConfigManager.getString("quantity.free"));
        } else if (mode != BUY_AND_SELL) {
            if (discount > 0) {
                ChatColor c = VMUtils.getCodeBeforePlaceholder(ConfigManager.getStringList(lorePath), "%price%");
                String prePrice = ConfigManager.getCurrencyBuilder("%price%").replaceCurrency("%price%", sellPrice).build();
                String currentPrice = ConfigManager.getCurrencyBuilder("%price%").replaceCurrency("%price%", getSellPrice()).build();
                builder.replace("%price%", "§m" + prePrice + c + " " + currentPrice);
            } else {
                builder.replaceCurrency("%price%", getSellPrice());
            }
            builder.replaceCurrency("%price_per_unit%", getSellPrice().divide(BigDecimal.valueOf(getAmount()), RoundingMode.HALF_UP));
        } else {
            boolean isCustomerMenu = path.equals("shopfront");
            if (isAdmin && !isCustomerMenu) {
                builder.replace("%price%", VMUtils.formatBuySellPrice(getBuyPrice(false), getSellPrice(false)));
            } else if (discount > 0) {
                String preSell = ConfigManager.getCurrencyBuilder("%price%").replaceCurrency("%price%", sellPrice).build();
                String currentSell = ConfigManager.getCurrencyBuilder("%price%").replaceCurrency("%price%", getSellPrice()).build();
                String preBuy = ConfigManager.getCurrencyBuilder("%price%").replaceCurrency("%price%", buyPrice).build();
                String currentBuy = ConfigManager.getCurrencyBuilder("%price%").replaceCurrency("%price%", getBuyPrice()).build();

                ChatColor cBuy = VMUtils.getCodeBeforePlaceholder(ConfigManager.getStringList(lorePath), "%buy_price%");
                ChatColor cSell = VMUtils.getCodeBeforePlaceholder(ConfigManager.getStringList(lorePath), "%sell_price%");
                builder.replace("%buy_price%", "§m" + (isCustomerMenu ? preSell : preBuy) + cBuy + " " + (isCustomerMenu ? currentSell : currentBuy));
                builder.replace("%sell_price%", "§m" + (isCustomerMenu ? preBuy : preSell) + cSell + " " + (isCustomerMenu ? currentBuy : currentSell));
            } else {
                builder.replaceCurrency("%buy_price%", isCustomerMenu ? getSellPrice() : getBuyPrice());
                builder.replaceCurrency("%sell_price%", isCustomerMenu ? getBuyPrice() : getSellPrice());
            }
        }
        List<String> lore = builder.build();

        if (discount > 0 && discountEnd != null) {
            lore.addAll(ConfigManager.getListBuilder("menus.shopfront.discount_lore")
                    .replace("%discount%", String.valueOf(discount))
                    .replace("%time%", ConfigManager.getTimeLeft(getDiscountEnd())).build());
        }
        if (isAdmin && limit > 0) {
            int index = lore.indexOf("%limit_lore%");
            if (index != -1) {
                lore.remove(index);
                String type = isItemTrade() ? "buy" : mode.getInteractionType();
                lore.addAll(index, ConfigManager.getListBuilder("menus.shopfront.admin_shop." + type + "_limit_lore")
                        .replace("%reset%", reset)
                        .replace("%limit%", limitInfo)
                        .replace("%bought%", bought).build());
            }
        }
        lore.remove("%limit_lore%");

        return lore;
    }

    public ItemStack getEditorItem() {
        ItemStack i = getRawItem();
        ItemMeta m = i.getItemMeta();
        if (m != null && editorLore != null) {

            m.setLore(editorLore);
            i.setItemMeta(m);
        }
        return i;
    }
    public ItemStack getCustomerItem(Player p) {
        ItemStack i = getRawItem();
        ItemMeta m = i.getItemMeta();
        if (m != null) {
            m.setLore(getLore("shopfront", mode.inverted(), p));
            i.setItemMeta(m);
        }
        return i;
    }

    public ItemStack getRawItem() {
        return item.clone();
    }

    public String getItemTradeName() {
        return getItemName(itemTrade);
    }

    private int getAmountInventory(ItemStack itemStack, Inventory inventory) {
        int amount = 0;
        for (ItemStack storageStack : inventory.getContents()) {
            if (storageStack == null) { continue; }

            if (VMUtils.compareItems(storageStack, itemStack)) {
                amount = amount + storageStack.getAmount();
            }
        }
        return amount;
    }

    private String getItemName(ItemStack i) {
        ItemMeta m = i.getItemMeta();
        if (m != null && m.hasDisplayName()) {
            return m.getDisplayName();
        } else if (plugin.getLocalizedMaterial(i.getType().name()) != null) {
            return plugin.getLocalizedMaterial(i.getType().name());
        } else if (m != null && VersionUtils.getMCVersion() > 11 && m.hasLocalizedName()) {
            return m.getLocalizedName();
        } else {
            return i.getType().name().replaceAll("_", " ");
        }
    }
}
