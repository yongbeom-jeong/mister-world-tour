package com.misterworldtour.travel;

import com.misterworldtour.common.MwtProperties;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 직원이 올린 상품 사진 처리 (Django 판 travel/images.py): 최대 1600px JPEG 로 줄여 다시 저장한다.
 * 다시 그려 저장하므로 위치 정보 등 메타데이터는 남지 않는다.
 * (Django 판은 휴대폰 사진의 회전 정보(EXIF)를 보정하지만 이 판의 ImageIO 는 하지 않는다)
 */
@Component
public class ProductImages {

    public static final int MAX_SIDE = 1600;
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    private final MwtProperties properties;

    ProductImages(MwtProperties properties) {
        this.properties = properties;
    }

    /** 올린 사진을 검사·변환해 media 폴더에 저장하고 media 기준 경로를 돌려준다. */
    public String store(byte[] uploaded) {
        if (uploaded.length > MAX_BYTES) {
            throw new InvalidImageException("사진은 5MB 이하로 올려 주세요.");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(uploaded));
        } catch (IOException | RuntimeException e) {
            source = null;
        }
        if (source == null) {
            throw new InvalidImageException("사진 파일을 읽을 수 없습니다. JPG·PNG 파일을 올려 주세요.");
        }
        BufferedImage resized = resize(source);
        LocalDate today = LocalDate.now();
        String relative = "products/%d/%02d/%s.jpg".formatted(today.getYear(), today.getMonthValue(),
                UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        Path target = Path.of(properties.mediaRoot()).resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                writeJpeg(resized, out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return relative;
    }

    static BufferedImage resize(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage rgb = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB); // 투명 배경은 흰색으로
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.drawImage(source, 0, 0, nw, nh, null);
        g.dispose();
        return rgb;
    }

    private static void writeJpeg(BufferedImage image, OutputStream out) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.82f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
