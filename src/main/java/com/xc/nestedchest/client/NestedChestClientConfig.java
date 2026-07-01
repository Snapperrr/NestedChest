package com.xc.nestedchest.client;

import com.mojang.logging.LogUtils;
import com.xc.nestedchest.NestedChestMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class NestedChestClientConfig {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String CONFIG_FILE_NAME = "nestedchest-client.properties";
	private static final String BACKGROUND_DIR_NAME = "backgrounds";
	private static final String BACKGROUND_MODE_KEY = "background_mode";
	private static final String BACKGROUND_IMAGE_KEY = "background_image";
	private static final long IMAGE_RESCAN_INTERVAL_MS = 1000L;

	private static BackgroundMode backgroundMode = BackgroundMode.FIT;
	private static String configuredBackgroundImage = "";
	private static LoadedBackground loadedBackground;
	private static Path loadedPath;
	private static long loadedModifiedTime;
	private static long lastImageScanMs;
	private static final Map<Path, Long> failedImages = new HashMap<>();

	private NestedChestClientConfig() {
	}

	public static void initialize() {
		ensureDirectories();
		load();
	}

	public static BackgroundMode backgroundMode() {
		return backgroundMode;
	}

	public static void cycleBackgroundMode() {
		backgroundMode = backgroundMode.next();
		save();
		lastImageScanMs = 0L;
	}

	public static LoadedBackground background() {
		long now = System.currentTimeMillis();
		if (now - lastImageScanMs >= IMAGE_RESCAN_INTERVAL_MS) {
			lastImageScanMs = now;
			refreshBackground(false);
		}
		return loadedBackground;
	}

	public static Path backgroundDirectory() {
		return configDirectory().resolve(NestedChestMod.MOD_ID).resolve(BACKGROUND_DIR_NAME);
	}

	private static Path configDirectory() {
		return MinecraftClient.getInstance().runDirectory.toPath().resolve("config");
	}

	private static Path configFile() {
		return configDirectory().resolve(CONFIG_FILE_NAME);
	}

	private static void ensureDirectories() {
		try {
			Files.createDirectories(backgroundDirectory());
		} catch (IOException e) {
			LOGGER.warn("Unable to create nested chest background directory", e);
		}
	}

	private static void load() {
		Properties properties = new Properties();
		Path file = configFile();
		if (Files.exists(file)) {
			try (InputStream input = Files.newInputStream(file)) {
				properties.load(input);
			} catch (IOException e) {
				LOGGER.warn("Unable to load nested chest client config", e);
			}
		}
		backgroundMode = BackgroundMode.fromId(properties.getProperty(BACKGROUND_MODE_KEY, BackgroundMode.FIT.id));
		configuredBackgroundImage = properties.getProperty(BACKGROUND_IMAGE_KEY, "").trim();
		save();
		lastImageScanMs = 0L;
	}

	private static void save() {
		ensureDirectories();
		Properties properties = new Properties();
		properties.setProperty(BACKGROUND_MODE_KEY, backgroundMode.id);
		properties.setProperty(BACKGROUND_IMAGE_KEY, configuredBackgroundImage);
		try (OutputStream output = Files.newOutputStream(configFile())) {
			properties.store(output, "Nested Chest client settings");
		} catch (IOException e) {
			LOGGER.warn("Unable to save nested chest client config", e);
		}
	}

	private static void refreshBackground(boolean force) {
		List<Path> images = findBackgroundImages();
		if (images.isEmpty()) {
			clearBackground();
			return;
		}

		for (Path image : images) {
			long fileTime = fileFreshnessTime(image);
			if (!force && image.equals(loadedPath) && fileTime == loadedModifiedTime) {
				return;
			}

			Long failedModifiedTime = failedImages.get(image);
			if (!force && failedModifiedTime != null && failedModifiedTime == fileTime) {
				continue;
			}

			try {
				NativeImage nativeImage = readBackgroundImage(image);
				int width = nativeImage.getWidth();
				int height = nativeImage.getHeight();
				NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);
				Identifier id = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("nestedchest_background", texture);
				LoadedBackground oldBackground = loadedBackground;
				loadedBackground = new LoadedBackground(id, width, height);
				loadedPath = image;
				loadedModifiedTime = fileTime;
				destroyBackground(oldBackground);
				failedImages.clear();
				if (!image.getFileName().toString().equals(configuredBackgroundImage)) {
					configuredBackgroundImage = image.getFileName().toString();
					save();
				}
				LOGGER.info("Loaded nested chest background image {} ({}x{})", image.getFileName(), width, height);
				return;
			} catch (IOException e) {
				LOGGER.warn("Unable to load nested chest background image {}", image, e);
				failedImages.put(image, fileTime);
			}
		}

		clearBackground();
	}

	private static NativeImage readBackgroundImage(Path image) throws IOException {
		ImageKind kind = imageKind(image);
		return switch (kind) {
			case PNG -> {
				try (InputStream input = Files.newInputStream(image)) {
					yield NativeImage.read(input);
				}
			}
			case JPEG -> readJpegAsNativeImage(image);
			case UNKNOWN -> throw new IOException("Unsupported or invalid image signature");
		};
	}

	private static NativeImage readJpegAsNativeImage(Path image) throws IOException {
		BufferedImage buffered = ImageIO.read(image.toFile());
		if (buffered == null) {
			throw new IOException("Unable to decode JPEG image");
		}
		NativeImage nativeImage = new NativeImage(buffered.getWidth(), buffered.getHeight(), false);
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				int argb = buffered.getRGB(x, y);
				int alpha = (argb >>> 24) & 0xFF;
				int red = (argb >>> 16) & 0xFF;
				int green = (argb >>> 8) & 0xFF;
				int blue = argb & 0xFF;
				nativeImage.setColor(x, y, alpha << 24 | blue << 16 | green << 8 | red);
			}
		}
		return nativeImage;
	}

	private static List<Path> findBackgroundImages() {
		ensureDirectories();
		List<Path> images = new ArrayList<>();
		try (var files = Files.list(backgroundDirectory())) {
			files
					.filter(Files::isRegularFile)
					.filter(NestedChestClientConfig::isSupportedImage)
					.map(path -> path.toAbsolutePath().normalize())
					.sorted(Comparator
							.comparingLong(NestedChestClientConfig::fileFreshnessTime).reversed()
							.thenComparing(path -> configuredBackgroundImage.equals(path.getFileName().toString()) ? 0 : 1)
							.thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
					.forEach(images::add);
		} catch (IOException e) {
			LOGGER.warn("Unable to scan nested chest background directory", e);
		}
		return images;
	}

	private static boolean isSupportedImage(Path path) {
		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg");
	}

	private static ImageKind imageKind(Path path) throws IOException {
		byte[] header = new byte[8];
		int read;
		try (InputStream input = Files.newInputStream(path)) {
			read = input.read(header);
		}
		if (read >= 8
				&& (header[0] & 0xFF) == 0x89
				&& header[1] == 0x50
				&& header[2] == 0x4E
				&& header[3] == 0x47
				&& header[4] == 0x0D
				&& header[5] == 0x0A
				&& header[6] == 0x1A
				&& header[7] == 0x0A) {
			return ImageKind.PNG;
		}
		if (read >= 3
				&& (header[0] & 0xFF) == 0xFF
				&& (header[1] & 0xFF) == 0xD8
				&& (header[2] & 0xFF) == 0xFF) {
			return ImageKind.JPEG;
		}
		return ImageKind.UNKNOWN;
	}

	private static long modifiedTime(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return -1L;
		}
	}

	private static long fileFreshnessTime(Path path) {
		long modifiedTime = modifiedTime(path);
		try {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
			return Math.max(modifiedTime, attributes.creationTime().toMillis());
		} catch (IOException e) {
			return modifiedTime;
		}
	}

	private static void clearBackground() {
		destroyBackground(loadedBackground);
		loadedBackground = null;
		loadedPath = null;
		loadedModifiedTime = 0L;
	}

	private static void destroyBackground(LoadedBackground background) {
		if (background != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(background.texture());
		}
	}

	private enum ImageKind {
		PNG,
		JPEG,
		UNKNOWN
	}

	public enum BackgroundMode {
		FIT("fit", "适"),
		STRETCH("stretch", "拉"),
		FILL("fill", "填");

		private final String id;
		private final String shortLabel;

		BackgroundMode(String id, String shortLabel) {
			this.id = id;
			this.shortLabel = shortLabel;
		}

		public String shortLabel() {
			return shortLabel;
		}

		private BackgroundMode next() {
			BackgroundMode[] values = values();
			return values[(ordinal() + 1) % values.length];
		}

		private static BackgroundMode fromId(String id) {
			for (BackgroundMode mode : values()) {
				if (mode.id.equalsIgnoreCase(id)) {
					return mode;
				}
			}
			return FIT;
		}
	}

	public record LoadedBackground(Identifier texture, int width, int height) {
	}
}
