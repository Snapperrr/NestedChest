package com.xc.nestedchest.mixin;

import com.xc.nestedchest.client.NestedChestOverlay;
import com.xc.nestedchest.client.NestedChestScreenBridge;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> implements NestedChestScreenBridge {
	@Shadow
	@Final
	protected T handler;

	@Shadow
	protected int x;

	@Shadow
	protected int y;

	@Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
	private void nestedchest$hideVanillaTooltip(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.blocksVanillaTooltip(mouseX, mouseY)) {
			NestedChestOverlay.render(this.handler, context, mouseX, mouseY);
			ci.cancel();
		}
	}

	@Inject(method = "drawMouseoverTooltip", at = @At("TAIL"))
	private void nestedchest$render(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
		if (NestedChestOverlay.supports(this.handler)) {
			NestedChestOverlay.render(this.handler, context, mouseX, mouseY);
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void nestedchest$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.mouseClicked(this.handler, mouseX, mouseY, button)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void nestedchest$mouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.mouseReleased(mouseX, mouseY, button)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void nestedchest$mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.mouseDragged(mouseX, mouseY, button)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void nestedchest$keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.keyPressed(this.handler, keyCode, scanCode, modifiers)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void nestedchest$removed(CallbackInfo ci) {
		if (NestedChestOverlay.supports(this.handler)) {
			NestedChestOverlay.reset();
		}
	}

	@Override
	public int nestedchest$getRootX() {
		return this.x;
	}

	@Override
	public int nestedchest$getRootY() {
		return this.y;
	}
}
