package com.catting.creativestash;

import com.catting.creativestash.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
 * Creative Stash - client-side only. Adds a togglable side panel to the survival
 * inventory screen that lets you pick items like Creative mode, without being OP.
 *
 * SINGLEPLAYER: reaches directly into the local integrated server and edits your
 * inventory - no command, no cheats.
 *
 * A REAL SERVER (like Aternos): there is no local server to reach into, so this
 * falls back to sending "/give" and "/item replace" chat commands. Those commands
 * require permission level 2 (OP) - if you're not OP'd on that server, they will
 * silently fail there, same as typing them yourself would.
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

    // ----- layout -----
    private static final int COLS = 8;
    private static final int CELL = 18;
    private static final int PANEL_WIDTH = COLS * CELL + 12;

    @Override
    public void onInitializeClient() {
        refreshFilter();

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof InventoryScreen inv)) return;
            open = false;
            held = ItemStack.EMPTY;
            heldFromSlot = null;

            int btnX = left(inv) + width(inv) - 22;
            int btnY = top(inv) + 4;
            Button toggle = Button.builder(Component.literal("≡"), b -> {
                open = !open;
                if (open) {
                    searchBox = new EditBox(client.font, panelX(inv) + 6, top(inv) + 4, PANEL_WIDTH - 12, 14, Component.literal("Search"));
                    searchBox.setResponder(s -> { query = s; refreshFilter(); scroll = 0; });
                    Screens.getWidgets(screen).add(searchBox);
                } else {
                    if (searchBox != null) Screens.getWidgets(screen).remove(searchBox);
                    searchBox = null;
                    cancelHeld(client, inv);
                }
            }).bounds(btnX, btnY, 18, 18).build();
            Screens.getWidgets(screen).add(toggle);

            ScreenEvents.remove(screen).register(s -> {
                cancelHeld(client, inv);
                open = false;
                searchBox = null;
            });

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, h, v) -> {
                if (!open || !insidePanel(inv, mx, my)) return true;
                scroll = Math.max(0, scroll - (int) Math.signum(v));
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

                if (open && insidePanel(inv, mx, my)) {
                    handlePanelClick(client, inv, mx, my, shift);
                    return false;
                }

                if (open && insideTrash(inv, mx, my) && !inv.getMenu().getCarried().isEmpty()) {
                    // deleting an item you already had picked up the normal vanilla way
                    inv.getMenu().setCarried(ItemStack.EMPTY);
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

            ScreenEvents.afterExtract(screen).register((s, graphics, mx, my, delta) -> {
                if (open) renderPanel(graphics, inv, mx, my);
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

    private static int width(InventoryScreen inv) {
        return accessor(inv).getImageWidth();
    }

    private static int height(InventoryScreen inv) {
        return accessor(inv).getImageHeight();
    }

    private static Slot hoveredSlot(InventoryScreen inv) {
        return accessor(inv).getHoveredSlot();
    }

    private int panelX(InventoryScreen inv) {
        return left(inv) + width(inv) + 4;
    }

    private boolean insidePanel(InventoryScreen inv, double mx, double my) {
        int x = panelX(inv), y = top(inv);
        return mx >= x && mx < x + PANEL_WIDTH && my >= y && my < y + height(inv) - 20;
    }

    private boolean insideTrash(InventoryScreen inv, double mx, double my) {
        int x = panelX(inv) + 4;
        int y = top(inv) + height(inv) - 20;
        return mx >= x && mx < x + 16 && my >= y && my < y + 16;
    }

    private int gridIndexAt(InventoryScreen inv, double mx, double my) {
        int x = panelX(inv) + 6;
        int y = top(inv) + 22;
        int col = (int) ((mx - x) / CELL);
        int row = (int) ((my - y) / CELL);
        if (col < 0 || col >= COLS || row < 0 || my < y) return -1;
        return scroll * COLS + row * COLS + col;
    }

    private void renderPanel(GuiGraphicsExtractor graphics, InventoryScreen inv, int mouseX, int mouseY) {
        int x = panelX(inv), y = top(inv);
        graphics.fill(x, y, x + PANEL_WIDTH, y + height(inv), 0xC0101010);

        int gridX = x + 6, gridY = y + 22;
        int rows = (height(inv) - 44) / CELL;

        for (int i = 0; i < rows * COLS; i++) {
            int itemIndex = scroll * COLS + i;
            if (itemIndex >= filtered.size()) break;
            int cx = gridX + (i % COLS) * CELL;
            int cy = gridY + (i / COLS) * CELL;
            ItemStack stack = new ItemStack(filtered.get(itemIndex));
            graphics.item(stack, cx, cy);
            if (mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                graphics.setTooltipForNextFrame(Minecraft.getInstance().font, stack, mouseX, mouseY);
            }
        }

        // trash icon - a red X, like the delete slot in vanilla creative mode
        int tx = x + 4, ty = y + height(inv) - 20;
        graphics.fill(tx - 1, ty - 1, tx + 17, ty + 17, 0x80111111);
        var font = Minecraft.getInstance().font;
        String cross = "X";
        int textWidth = font.width(cross);
        graphics.text(font, cross, tx + 8 - textWidth / 2, ty + 4, 0xFFFF5555, true);
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

    // ---------- give / place (singleplayer direct, real server via command) ----------

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
