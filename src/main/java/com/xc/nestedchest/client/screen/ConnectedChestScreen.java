package com.xc.nestedchest.client.screen;

import com.xc.nestedchest.screen.ConnectedChestScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ConnectedChestScreen extends HandledScreen<ConnectedChestScreenHandler> {
	private static final Identifier TEXTURE = Identifier.ofVanilla("textures/gui/container/generic_54.png");
	private static final int SCROLLBAR_X = 174;
	private static final int SCROLLBAR_Y = 18;
	private static final int SCROLLBAR_HEIGHT = 108;
	private boolean scrolling;

	public ConnectedChestScreen(ConnectedChestScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.backgroundHeight = 222;
		this.playerInventoryTitleY = 128;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		drawMouseoverTooltip(context, mouseX, mouseY);
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(this.textRenderer, this.title, this.titleX, this.titleY, 0x404040, false);
		String count = handler.totalSlots() + " 格";
		context.drawText(this.textRenderer, count, this.backgroundWidth - this.textRenderer.getWidth(count) - 8, this.titleY, 0x404040, false);
		context.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		context.drawTexture(TEXTURE, this.x, this.y, 0, 0, this.backgroundWidth, 125);
		context.drawTexture(TEXTURE, this.x, this.y + 126, 0, 126, this.backgroundWidth, 96);
		drawScrollbar(context);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
			scrolling = true;
			setScrollFromMouse(mouseY);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (scrolling) {
			setScrollFromMouse(mouseY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		scrolling = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (handler.canScroll()) {
			handler.scrollBy(-verticalAmount);
			if (this.client != null && this.client.interactionManager != null) {
				this.client.interactionManager.clickButton(handler.syncId, Math.round(handler.scrollPosition() * 1000.0F));
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private void drawScrollbar(DrawContext context) {
		int trackX = this.x + SCROLLBAR_X;
		int trackY = this.y + SCROLLBAR_Y;
		context.fill(trackX, trackY, trackX + 4, trackY + SCROLLBAR_HEIGHT, 0xFF6F6F6F);
		int thumbHeight = handler.canScroll() ? Math.max(15, SCROLLBAR_HEIGHT * ConnectedChestScreenHandler.VISIBLE_ROWS / handler.rows()) : SCROLLBAR_HEIGHT;
		int thumbTravel = SCROLLBAR_HEIGHT - thumbHeight;
		int thumbY = trackY + Math.round(handler.scrollPosition() * thumbTravel);
		context.fill(trackX - 1, thumbY, trackX + 5, thumbY + thumbHeight, 0xFFE0E0E0);
		context.drawBorder(trackX - 1, thumbY, 6, thumbHeight, 0xFF505050);
	}

	private boolean isOverScrollbar(double mouseX, double mouseY) {
		return handler.canScroll()
				&& mouseX >= this.x + SCROLLBAR_X - 3
				&& mouseX < this.x + SCROLLBAR_X + 8
				&& mouseY >= this.y + SCROLLBAR_Y
				&& mouseY < this.y + SCROLLBAR_Y + SCROLLBAR_HEIGHT;
	}

	private void setScrollFromMouse(double mouseY) {
		double position = (mouseY - (this.y + SCROLLBAR_Y)) / SCROLLBAR_HEIGHT;
		float scroll = (float) Math.max(0.0D, Math.min(1.0D, position));
		handler.scrollTo(scroll);
		if (this.client != null && this.client.interactionManager != null) {
			this.client.interactionManager.clickButton(handler.syncId, Math.round(scroll * 1000.0F));
		}
	}
}
