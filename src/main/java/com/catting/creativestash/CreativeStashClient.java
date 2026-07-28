package com.catting.creativestash;

import com.catting.creativestash.mixin.AbstractContainerScreenAccessor;
import com.catting.creativestash.mixin.AbstractRecipeBookScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creative Stash - client-side only. Adds a togglable "CM" (cheat mode) panel,
 * styled after vanilla's Creative Mode search tab, that replaces the crafting
 * grid in the survival inventory screen and lets you pick items like Creative
 * mode without being OP.
 *
 * SINGLEPLAYER: reaches directly into the local integrated server and edits your
 * inventory - no command, no cheats.
 *
 * A REAL SERVER or LAN world (unless you're the LAN host): there is no local
 * server to reach into, so this falls back to sending "/give" and "/item
 * replace" chat commands. Those require permission level 2 - on a real server
 * you need to be OP'd; on a friend's "Open to LAN" world it works automatically
 * as long as they opened it with "Allow Cheats" turned on (that grants every
 * connected player command access for the session, vanilla behavior).
 */
public class CreativeStashClient implements ClientModInitializer {

    // ----- slot-map constants (position within the vanilla InventoryScreen's menu.slots list) -----
    // NOTE: this mapping (armor order especially) is my best reconstruction of vanilla's
    // long-standing InventoryMenu layout. If armor/offhand end up in the wrong spot, the
    // hotbar/main-inventory targeting is the part I'm confident is correct.
    private static final int SLOT_ARMOR_HEAD = 5;
    private static final int SLOT_ARMOR_CHEST = 6;
    private static final int SLOT_ARMOR_LEGS = 7;
    private static final int SLOT_ARMOR_FEET = 8;
    private static final int SLOT_MAIN_START = 9;
    private static final int SLOT_MAIN_END = 35;
    private static final int SLOT_HOTBAR_START = 36;
    private static final int SLOT_HOTBAR_END = 44;
    private static final int SLOT_OFFHAND = 45;

    // ----- panel state -----
    private static boolean open = false;
    private static ItemStack held = ItemStack.EMPTY;
    private static Integer heldFromSlot = null; // null = came from the panel, not a real slot
    private static EditBox searchBox = null;
    private static String query = "";
    private static int scroll = 0;
    private static final List<Item> filtered = new ArrayList<>();
    private static final Set<String> quietedServers = new HashSet<>();
    private static final List<AbstractWidget> hiddenWidgets = new ArrayList<>();

    // ----- layout: matches vanilla CreativeModeInventoryScreen's item-search tab exactly -----
    // (background texture is 195x136 visible in a 256x256 canvas; grid is a fixed 9x5,
    // 18px cells starting at local (9,18); search box at local (82,6) sized 80x9;
    // scrollbar track at local x=175, spanning y=18..130, thumb is the 12x15 sprite)
    private static final Identifier SEARCH_BG = Identifier.withDefaultNamespace("textures/gui/container/creative_inventory/tab_item_search.png");
    private static final Identifier SCROLLER_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
    private static final Identifier SCROLLER_DISABLED_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");
    private static final int PANEL_WIDTH = 195;
    private static final int PANEL_HEIGHT = 136;
    private static final int NUM_COLS = 9;
    private static final int NUM_ROWS = 5;
    private static final int CELL = 18;
    private static final int GRID_ORIGIN_X = 9;
    private static final int GRID_ORIGIN_Y = 18;
    private static final int TRASH_X = 9;
    private static final int TRASH_Y = 112;
    private static final int SCROLLBAR_X = 175;
    private static final int SCROLLBAR_TOP = 18;
    private static final int SCROLLBAR_TRAVEL = 95; // matches vanilla: track height (112) minus thumb height (15) minus 2px border
    private static final int WINDOW_SHIFT = PANEL_WIDTH + 4; // how far we push the vanilla window aside to make room

    @Override
    public void onInitializeClient() {
        refreshFilter();

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof InventoryScreen inv)) return;
            open = false;
            held = ItemStack.EMPTY;
            heldFromSlot = null;

            // positioned right next to vanilla's own recipe-book toggle button.
            // InventoryScreen.getRecipeBookButtonPosition() returns
            // (leftPos+104, screenHeight/2-22); since topPos is standardly
            // (screenHeight-imageHeight)/2, that resolves to local (104, imageHeight/2-22)
            // = (104, 61) for the standard 166-tall inventory window - confirmed math,
            // not a guess.
            int btnW = 18, btnH = 18; // square, matching vanilla's recipe-book button shape
            int btnX = left(inv) + 126;
            int btnY = top(inv) + 61;
            Button[] toggleHolder = new Button[1];
            Button toggle = Button.builder(Component.literal("CM"), b -> {
                open = !open;
                if (open) {
                    scroll = 0;

                    // push the vanilla window aside (same trick vanilla's own recipe
                    // book uses) to make room instead of floating our panel over it
                    accessor(inv).setLeftPos(left(inv) + WINDOW_SHIFT);
                    toggleHolder[0].setX(toggleHolder[0].getX() + WINDOW_SHIFT);

                    // close vanilla's recipe book - it renders/handles clicks fully
                    // independently of the crafting grid, so covering the grid alone
                    // wouldn't stop it from popping open over our panel
                    RecipeBookComponent<?> recipeBook = ((AbstractRecipeBookScreenAccessor) inv).getRecipeBookComponent();
                    if (recipeBook.isVisible()) recipeBook.toggleVisibility();

                    searchBox = new EditBox(client.font, panelX(inv) + 82, panelY(inv) + 6, 80, 9, Component.literal("Search"));
                    searchBox.setBordered(false);
                    searchBox.setTextColor(0xFFFFFFFF);
                    searchBox.setResponder(s -> { query = s; refreshFilter(); scroll = 0; });
                    Screens.getWidgets(screen).add(searchBox);

                    // hide every other vanilla widget (recipe book button included) so
                    // nothing clickable/visible is left poking out from under our panel
                    hiddenWidgets.clear();
                    for (AbstractWidget w : Screens.getWidgets(screen)) {
                        if (w == toggleHolder[0] || w == searchBox || !w.visible) continue;
                        w.visible = false;
                        w.active = false;
                        hiddenWidgets.add(w);
                    }
                } else {
                    accessor(inv).setLeftPos(left(inv) - WINDOW_SHIFT);
                    toggleHolder[0].setX(toggleHolder[0].getX() - WINDOW_SHIFT);

                    for (AbstractWidget w : hiddenWidgets) {
                        w.visible = true;
                        w.active = true;
                    }
                    hiddenWidgets.clear();
                    if (searchBox != null) Screens.getWidgets(screen).remove(searchBox);
                    searchBox = null;
                    cancelHeld(client, inv);
                }
            }).bounds(btnX, btnY, btnW, btnH).build();
            toggleHolder[0] = toggle;
            Screens.getWidgets(screen).add(toggle);

            ScreenEvents.remove(screen).register(s -> {
                cancelHeld(client, inv);
                open = false;
                searchBox = null;
                hiddenWidgets.clear();
            });

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, h, v) -> {
                if (!open || !insidePanel(inv, mx, my)) return true;
                scroll = clampScroll(scroll - (int) Math.signum(v));
                return false;
            });

            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                double mx = event.x();
                double my = event.y();
                boolean shift = event.hasShiftDown();

                if (!held.isEmpty()) {
                    resolveDrop(client, inv, mx, my);
                    return false;
                }

                if (open && insideSearchBox(inv, mx, my)) {
                    // let vanilla's normal click handling through so it can focus
                    // our EditBox - otherwise it never gets focus and typing does
                    // nothing, since we'd be eating the click ourselves below
                    return true;
                }

                if (open && insideTrash(inv, mx, my)) {
                    if (!inv.getMenu().getCarried().isEmpty()) {
                        // deleting an item you already had picked up the normal vanilla way
                        inv.getMenu().setCarried(ItemStack.EMPTY);
                    }
                    return false;
                }

                if (open && insidePanel(inv, mx, my)) {
                    handlePanelClick(client, inv, mx, my, shift);
                    return false;
                }

                if (open) {
                    Slot slot = hoveredSlot(inv);
                    if (slot != null && !slot.getItem().isEmpty()) {
                        int idx = inv.getMenu().slots.indexOf(slot);
                        if (isSupportedSlot(idx)) {
                            held = slot.getItem().copy();
                            heldFromSlot = idx;
                            setSlot(client, idx, ItemStack.EMPTY);
                            return false;
                        }
                    }
                }

                return true;
            });

            // background layer first, so our own search box (a widget, rendered right
            // after this) and vanilla's covered-up crafting/recipe-book widgets end up
            // drawn on top of it rather than underneath
            ScreenEvents.afterBackground(screen).register((s, graphics, mx, my, delta) -> {
                if (open) renderPanelBackground(graphics, inv);
            });

            // foreground layer last: item icons, tooltip, floating held item, trash and
            // scrollbar thumb all need to sit on top of everything else, including
            // whatever vanilla widgets got covered by the background layer above
            ScreenEvents.afterExtract(screen).register((s, graphics, mx, my, delta) -> {
                if (open) renderPanelForeground(graphics, inv, mx, my);
                if (!held.isEmpty()) {
                    graphics.item(held, mx - 8, my - 8);
                }
            });
        });
    }

    // ---------- click handling ----------

    private void handlePanelClick(Minecraft client, InventoryScreen inv, double mx, double my, boolean shift) {
        int index = gridIndexAt(inv, mx, my);
        if (index < 0 || index >= filtered.size()) return;
        Item item = filtered.get(index);

        if (shift) {
            giveStack(client, new ItemStack(item, item.getDefaultMaxStackSize()));
        } else {
            held = new ItemStack(item, 1);
            heldFromSlot = null;
        }
    }

    private void resolveDrop(Minecraft client, InventoryScreen inv, double mx, double my) {
        if (open && insideTrash(inv, mx, my)) {
            held = ItemStack.EMPTY;
            heldFromSlot = null;
            return;
        }

        Slot slot = hoveredSlot(inv);
        if (slot != null) {
            int idx = inv.getMenu().slots.indexOf(slot);
            if (isSupportedSlot(idx)) {
                ItemStack existing = slot.getItem().copy();
                setSlot(client, idx, held);
                held = existing;
                heldFromSlot = existing.isEmpty() ? null : idx;
                return;
            }
        }

        if (open && insidePanel(inv, mx, my)) {
            cancelHeld(client, inv);
            return;
        }

        cancelHeld(client, inv);
    }

    private void cancelHeld(Minecraft client, InventoryScreen inv) {
        if (heldFromSlot != null && !held.isEmpty()) {
            setSlot(client, heldFromSlot, held);
        }
        held = ItemStack.EMPTY;
        heldFromSlot = null;
    }

    // ---------- rendering ----------

    private static AbstractContainerScreenAccessor accessor(InventoryScreen inv) {
        return (AbstractContainerScreenAccessor) inv;
    }

    private static int left(InventoryScreen inv) {
        return accessor(inv).getLeftPos();
    }

    private static int top(InventoryScreen inv) {
        return accessor(inv).getTopPos();
    }

    private static Slot hoveredSlot(InventoryScreen inv) {
        return accessor(inv).getHoveredSlot();
    }

    // Sits to the LEFT of the inventory window - the same side vanilla's own
    // recipe book occupies when opened - rather than over the crafting grid,
    // since "replace the recipes menu" meant the recipe book's search UI, not
    // the crafting grid itself. The window itself gets pushed right by
    // WINDOW_SHIFT while open (see the toggle button's onPress above), so by
    // the time this is called leftPos already reflects that shift and this
    // correctly lands back on the window's original, pre-shift position.
    private int panelX(InventoryScreen inv) {
        return left(inv) - WINDOW_SHIFT;
    }

    private int panelY(InventoryScreen inv) {
        return top(inv) + 4;
    }

    private boolean insidePanel(InventoryScreen inv, double mx, double my) {
        int x = panelX(inv), y = panelY(inv);
        return mx >= x && mx < x + PANEL_WIDTH && my >= y && my < y + PANEL_HEIGHT;
    }

    private boolean insideSearchBox(InventoryScreen inv, double mx, double my) {
        int x = panelX(inv) + 82, y = panelY(inv) + 6;
        return mx >= x && mx < x + 80 && my >= y && my < y + 9;
    }

    private boolean insideTrash(InventoryScreen inv, double mx, double my) {
        int x = panelX(inv) + TRASH_X, y = panelY(inv) + TRASH_Y;
        return mx >= x && mx < x + CELL && my >= y && my < y + CELL;
    }

    private int gridIndexAt(InventoryScreen inv, double mx, double my) {
        int x = panelX(inv) + GRID_ORIGIN_X;
        int y = panelY(inv) + GRID_ORIGIN_Y;
        int col = (int) ((mx - x) / CELL);
        int row = (int) ((my - y) / CELL);
        if (col < 0 || col >= NUM_COLS || row < 0 || row >= NUM_ROWS || mx < x || my < y) return -1;
        return scroll * NUM_COLS + row * NUM_COLS + col;
    }

    private static int maxScroll() {
        int totalRows = (filtered.size() + NUM_COLS - 1) / NUM_COLS;
        return Math.max(0, totalRows - NUM_ROWS);
    }

    private static int clampScroll(int value) {
        return Math.max(0, Math.min(value, maxScroll()));
    }

    private void renderPanelBackground(GuiGraphicsExtractor graphics, InventoryScreen inv) {
        int x = panelX(inv), y = panelY(inv);
        graphics.blit(RenderPipelines.GUI_TEXTURED, SEARCH_BG, x, y, 0f, 0f, PANEL_WIDTH, PANEL_HEIGHT, 256, 256);
        graphics.text(Minecraft.getInstance().font, "Search Items", x + 8, y + 7, 0x404040);
    }

    private void renderPanelForeground(GuiGraphicsExtractor graphics, InventoryScreen inv, int mouseX, int mouseY) {
        int x = panelX(inv), y = panelY(inv);
        int gridX = x + GRID_ORIGIN_X, gridY = y + GRID_ORIGIN_Y;

        for (int i = 0; i < NUM_ROWS * NUM_COLS; i++) {
            int itemIndex = scroll * NUM_COLS + i;
            if (itemIndex >= filtered.size()) break;
            int cx = gridX + (i % NUM_COLS) * CELL;
            int cy = gridY + (i / NUM_COLS) * CELL;
            ItemStack stack = new ItemStack(filtered.get(itemIndex));
            graphics.item(stack, cx, cy);
            if (mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                graphics.setTooltipForNextFrame(Minecraft.getInstance().font, stack, mouseX, mouseY);
            }
        }

        int maxScroll = maxScroll();
        Identifier scrollerSprite = maxScroll > 0 ? SCROLLER_SPRITE : SCROLLER_DISABLED_SPRITE;
        float scrollOffs = maxScroll > 0 ? (float) scroll / maxScroll : 0f;
        int thumbY = y + SCROLLBAR_TOP + Math.round(SCROLLBAR_TRAVEL * scrollOffs);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, scrollerSprite, x + SCROLLBAR_X, thumbY, 12, 15);

        // trash icon - a red X, like the delete slot in vanilla creative mode
        int tx = x + TRASH_X, ty = y + TRASH_Y;
        graphics.fill(tx, ty, tx + CELL, ty + CELL, 0x80111111);
        var font = Minecraft.getInstance().font;
        String cross = "X";
        int textWidth = font.width(cross);
        graphics.text(font, cross, tx + CELL / 2 - textWidth / 2, ty + 5, 0xFFFF5555, true);
    }

    private void refreshFilter() {
        filtered.clear();
        String q = query.toLowerCase();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            if (q.isEmpty() || item.getName(new ItemStack(item)).getString().toLowerCase().contains(q)) {
                filtered.add(item);
            }
        }
        scroll = clampScroll(scroll);
    }

    // ---------- slot math ----------

    private static boolean isSupportedSlot(int menuIndex) {
        return menuIndex == SLOT_ARMOR_HEAD || menuIndex == SLOT_ARMOR_CHEST
                || menuIndex == SLOT_ARMOR_LEGS || menuIndex == SLOT_ARMOR_FEET
                || (menuIndex >= SLOT_MAIN_START && menuIndex <= SLOT_MAIN_END)
                || (menuIndex >= SLOT_HOTBAR_START && menuIndex <= SLOT_HOTBAR_END)
                || menuIndex == SLOT_OFFHAND;
    }

    private static int toUnifiedInventoryIndex(int menuIndex) {
        if (menuIndex == SLOT_ARMOR_HEAD) return 39;
        if (menuIndex == SLOT_ARMOR_CHEST) return 38;
        if (menuIndex == SLOT_ARMOR_LEGS) return 37;
        if (menuIndex == SLOT_ARMOR_FEET) return 36;
        if (menuIndex >= SLOT_MAIN_START && menuIndex <= SLOT_MAIN_END) return menuIndex;
        if (menuIndex >= SLOT_HOTBAR_START && menuIndex <= SLOT_HOTBAR_END) return menuIndex - SLOT_HOTBAR_START;
        if (menuIndex == SLOT_OFFHAND) return 40;
        throw new IllegalArgumentException("Unsupported slot " + menuIndex);
    }

    private static String toCommandSlotString(int menuIndex) {
        if (menuIndex == SLOT_ARMOR_HEAD) return "armor.head";
        if (menuIndex == SLOT_ARMOR_CHEST) return "armor.chest";
        if (menuIndex == SLOT_ARMOR_LEGS) return "armor.legs";
        if (menuIndex == SLOT_ARMOR_FEET) return "armor.feet";
        if (menuIndex >= SLOT_MAIN_START && menuIndex <= SLOT_MAIN_END) return "inventory." + (menuIndex - SLOT_MAIN_START);
        if (menuIndex >= SLOT_HOTBAR_START && menuIndex <= SLOT_HOTBAR_END) return "hotbar." + (menuIndex - SLOT_HOTBAR_START);
        if (menuIndex == SLOT_OFFHAND) return "weapon.offhand";
        throw new IllegalArgumentException("Unsupported slot " + menuIndex);
    }

    // ---------- give / place (singleplayer direct, real server/LAN via command) ----------

    /**
     * Real servers (and a LAN world for anyone who isn't the host) can only get items
     * via a command, and by default the game prints something like "Gave 1 Diamond to
     * You" in chat every time a command runs. This turns that off, once per server,
     * so grabbing items from the panel doesn't spam the chat.
     */
    private static void ensureQuiet(Minecraft client) {
        String key = client.getCurrentServer() != null ? client.getCurrentServer().ip : "unknown";
        if (quietedServers.add(key)) {
            client.player.connection.sendCommand("gamerule sendCommandFeedback false");
        }
    }

    private static void setSlot(Minecraft client, int menuIndex, ItemStack stack) {
        if (client.player == null) return;
        MinecraftServer sp = client.getSingleplayerServer();
        if (sp != null) {
            UUID uuid = client.player.getUUID();
            int unified = toUnifiedInventoryIndex(menuIndex);
            ItemStack copy = stack.copy();
            sp.execute(() -> {
                PlayerList playerList = sp.getPlayerList();
                ServerPlayer serverPlayer = playerList.getPlayer(uuid);
                if (serverPlayer == null) return;
                serverPlayer.getInventory().setItem(unified, copy);
            });
        } else {
            ensureQuiet(client);
            String slotStr = toCommandSlotString(menuIndex);
            String name = client.player.getGameProfile().name();
            String cmd;
            if (stack.isEmpty()) {
                cmd = "item replace entity " + name + " " + slotStr + " with minecraft:air";
            } else {
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                cmd = "item replace entity " + name + " " + slotStr + " with " + itemId + " " + stack.getCount();
            }
            client.player.connection.sendCommand(cmd);
        }
    }

    private static void giveStack(Minecraft client, ItemStack stack) {
        if (client.player == null) return;
        MinecraftServer sp = client.getSingleplayerServer();
        if (sp != null) {
            UUID uuid = client.player.getUUID();
            ItemStack copy = stack.copy();
            sp.execute(() -> {
                PlayerList playerList = sp.getPlayerList();
                ServerPlayer serverPlayer = playerList.getPlayer(uuid);
                if (serverPlayer == null) return;
                Inventory inventory = serverPlayer.getInventory();
                if (!inventory.add(copy)) {
                    serverPlayer.drop(copy, false);
                }
            });
        } else {
            ensureQuiet(client);
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = client.player.getGameProfile().name();
            client.player.connection.sendCommand("give " + name + " " + itemId + " " + stack.getCount());
        }
    }
}
