package dev.doctor4t.wathe.client.gui.screen.ingame;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.client.inventory.InventoryButtonApi;
import dev.doctor4t.wathe.api.client.inventory.InventoryScreenType;
import dev.doctor4t.wathe.api.shop.ShopApi;
import dev.doctor4t.wathe.client.gui.StoreRenderer;
import dev.doctor4t.wathe.util.ShopEntry;
import dev.doctor4t.wathe.util.StoreBuyPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class LimitedInventoryScreen extends LimitedHandledScreen<PlayerScreenHandler> {
    public static final Identifier BACKGROUND_TEXTURE = Wathe.id("textures/gui/container/limited_inventory.png");
    public static final @NotNull Identifier ID = Wathe.id("textures/gui/game.png");
    private static final int SHOP_ITEM_SPACING = 38;
    private static final int SHOP_ITEM_X_OFFSET = 9;
    private static final int SHOP_ITEM_Y_OFFSET = 46;
    private static final int SHOP_SLOT_BACKGROUND_OFFSET = 7;
    private static final int SHOP_SLOT_BACKGROUND_SIZE = 30;
    private static final int SHOP_PRICE_TOOLTIP_X_OFFSET = -4;
    private static final int SHOP_PRICE_TOOLTIP_TOP_OFFSET = -9;
    private static final int SHOP_PRICE_TOOLTIP_LINE_HEIGHT = 10;
    private static final int SHOP_PRICE_TOOLTIP_MULTILINE_EXTRA_OFFSET = 2;
    public final ClientPlayerEntity player;

    public LimitedInventoryScreen(@NotNull ClientPlayerEntity player) {
        super(player.playerScreenHandler, player.getInventory(), Text.empty());
        this.player = player;
    }

    @Override
    protected void init() {
        super.init();
        /*
         * 商店显示统一从 ShopApi 解析。
         * 这里不再只判断 canUseKillerFeatures：工程师、初学者、技术员等非杀手职业
         * 只要注册了职业商店，也会自然显示自己的商品按钮。
         */
        List<ShopEntry> entries = ShopApi.getEntriesForPlayer(this.player);
        if (!entries.isEmpty()) {
            int x = this.width / 2 - entries.size() * SHOP_ITEM_SPACING / 2 + SHOP_ITEM_X_OFFSET;
            int y = this.y - SHOP_ITEM_Y_OFFSET;
            for (int i = 0; i < entries.size(); i++)
                this.addDrawableChild(new StoreItemWidget(this, x + SHOP_ITEM_SPACING * i, y, entries.get(i), i));
        }

        /*
         * 背包扩展按钮统一从 InventoryButtonApi 挂载。
         * 这样扩展 mod 不需要再 mixin LimitedInventoryScreen；动态头像列表、
         * 翻页按钮、文本输入阶段阻止关闭等生命周期都由 Wathe 统一调度。
         */
        InventoryButtonApi.initializeScreen(
                this,
                InventoryScreenType.LIMITED,
                this.player,
                this.textRenderer,
                widget -> this.addDrawableChild(widget),
                this.width,
                this.height,
                this.x,
                this.y,
                this.backgroundWidth,
                this.backgroundHeight
        );
    }

    @Override
    protected void drawBackground(@NotNull DrawContext context, float delta, int mouseX, int mouseY) {
        context.drawTexture(BACKGROUND_TEXTURE, this.x, this.y, 0, 0, this.backgroundWidth, this.backgroundHeight);

        context.getMatrices().push();
        context.getMatrices().translate(context.getScaledWindowWidth() / 2f, context.getScaledWindowHeight(), 0);
        float scale = 0.28f;
        context.getMatrices().scale(scale, scale, 1f);
        int height = 254;
        int width = 497;
        context.getMatrices().translate(0, -230, 0);
        int xOffset = 0;
        int yOffset = 0;
        context.drawTexturedQuad(ID, (int) (xOffset - width / 2f), (int) (xOffset + width / 2f), (int) (yOffset - height / 2f), (int) (yOffset + height / 2f), 0, 0, 1f, 0, 1f, 1f, 1f, 1f, 1f);
        context.getMatrices().pop();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        InventoryButtonApi.renderScreen(this, context, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(context, mouseX, mouseY);
        StoreRenderer.renderHud(this.textRenderer, this.player, context, delta);
    }

    @Override
    protected void handledScreenTick() {
        InventoryButtonApi.tickScreen(this);
    }

    public static class StoreItemWidget extends ButtonWidget {
        public final LimitedInventoryScreen screen;
        public final ShopEntry entry;

        public StoreItemWidget(LimitedInventoryScreen screen, int x, int y, @NotNull ShopEntry entry, int index) {
            super(x, y, 16, 16, entry.stack().getName(), (a) -> ClientPlayNetworking.send(new StoreBuyPayload(index)), DEFAULT_NARRATION_SUPPLIER);
            this.screen = screen;
            this.entry = entry;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            super.renderWidget(context, mouseX, mouseY, delta);
            context.drawGuiTexture(
                    entry.type().getTexture(),
                    this.getX() - SHOP_SLOT_BACKGROUND_OFFSET,
                    this.getY() - SHOP_SLOT_BACKGROUND_OFFSET,
                    SHOP_SLOT_BACKGROUND_SIZE,
                    SHOP_SLOT_BACKGROUND_SIZE
            );
//            context.drawGuiTexture(Wathe.id("gui/shop_slot"), this.getX() - 7, this.getY() - 7, 30, 30);
            context.drawItem(this.entry.stack(), this.getX(), this.getY());
            if (this.isHovered()) {
                this.screen.renderLimitedInventoryTooltip(context, this.entry.stack());
                drawShopSlotHighlight(context, this.getX(), this.getY(), 0);
            }
            this.renderPrice(context);
        }

        private void renderPrice(@NotNull DrawContext context) {
            List<Text> priceLines = this.entry.shopPrice().displayLines();
            if (priceLines.isEmpty()) {
                return;
            }

            int maxWidth = 0;
            int spaceWidth = Math.max(1, this.screen.textRenderer.getWidth(" "));

            for (Text line : priceLines) {
                maxWidth = Math.max(maxWidth, this.screen.textRenderer.getWidth(line));
            }

            /*
             * 价格框继续走 DrawContext#drawTooltip。
             *
             * 这样背景、边框、悬浮动画都会回到原版 tooltip 的渲染路径，
             * 也能继续吃到像 Modern UI 这类模组对 tooltip 的兼容改写。
             *
             * 单行价格沿用旧版 drawTooltip 的位置；多行价格则额外上移。
             * 原版 OrderedTextTooltipComponent 每行高度是 10，并且单行 tooltip 内部会少算 2 像素高度。
             * 因此多行时按这个差值补偿，可以让背景框底边保持在单行价格附近，只向上扩展，
             * 不会因为疯魔模式这类五行价格向下挡住商品。
             *
             * 同时把每一行左右补上对称空白，这样“或”字和价格数字都会以整行居中显示，
             * 而不是从左侧开始贴着背景框排。
             */
            List<Text> centeredPriceLines = new java.util.ArrayList<>(priceLines.size());
            for (Text line : priceLines) {
                int lineWidth = this.screen.textRenderer.getWidth(line);
                int remainingWidth = Math.max(0, maxWidth - lineWidth);
                int padCount = Math.round((float) remainingWidth / spaceWidth);
                int leftPad = padCount / 2;
                int rightPad = padCount - leftPad;

                String leftPadding = " ".repeat(leftPad);
                String rightPadding = " ".repeat(rightPad);
                centeredPriceLines.add(Text.literal(leftPadding).append(line).append(rightPadding));
            }

            int tooltipX = this.getX() + SHOP_PRICE_TOOLTIP_X_OFFSET - maxWidth / 2;
            int extraLineOffset = priceLines.size() <= 1
                    ? 0
                    : (priceLines.size() - 1) * SHOP_PRICE_TOOLTIP_LINE_HEIGHT + SHOP_PRICE_TOOLTIP_MULTILINE_EXTRA_OFFSET;
            int tooltipY = this.getY() + SHOP_PRICE_TOOLTIP_TOP_OFFSET - extraLineOffset;
            context.drawTooltip(this.screen.textRenderer, centeredPriceLines, tooltipX, tooltipY);
        }

        private void drawShopSlotHighlight(DrawContext context, int x, int y, int z) {
            int color = 0x90FFBF49;
//            context.fillGradient(RenderLayer.getGuiOverlay(), x, y, x + 16, y + 16, color, color, z);
            context.fillGradient(RenderLayer.getGuiOverlay(), x, y, x + 16, y + 14, color, color, z);
            context.fillGradient(RenderLayer.getGuiOverlay(), x, y + 14, x + 15, y + 15, color, color, z);
            context.fillGradient(RenderLayer.getGuiOverlay(), x, y + 15, x + 14, y + 16, color, color, z);
        }

        @Override
        public void drawMessage(DrawContext context, TextRenderer textRenderer, int color) {
        }
    }
}
