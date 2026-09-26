package com.xucai.catalog.service;

import com.xucai.common.exceptions.BadRequestException;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.Locale;

/** Decode and re-encode raster uploads: no SVG, metadata or filename trust. */
public record ProductImageData(byte[] bytes, String extension, String contentType) {
    public static final int MAX_INPUT = 2 * 1024 * 1024;
    public static final int MAX_OUTPUT = 5 * 1024 * 1024;
    public static ProductImageData decode(byte[] input) {
        if (input == null || input.length == 0 || input.length > MAX_INPUT)
            throw new BadRequestException("请选择不超过2MB的JPEG或PNG图片");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException();
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) throw new IOException();
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 6000 || height > 6000 || (long) width * height > 16000000)
                    throw new BadRequestException("图片尺寸过大，请缩小至1600万像素以内，单边不超过6000像素");
                var image = reader.read(0);
                var out = new ByteArrayOutputStream();
                if (!ImageIO.write(image, format, out) || out.size() > MAX_OUTPUT) throw new IOException();
                return new ProductImageData(out.toByteArray(), format.equals("jpeg") ? "jpg" : "png", "image/" + format);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) {
            throw new BadRequestException("图片无法读取，请上传有效的JPEG或PNG图片");
        }
    }
}
