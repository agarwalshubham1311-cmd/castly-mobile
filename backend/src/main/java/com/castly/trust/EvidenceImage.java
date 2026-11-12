package com.castly.trust;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class EvidenceImage {
  static final int MAX_BYTES = 5 * 1024 * 1024;

  private EvidenceImage() {}

  static byte[] sanitize(String encoded) {
    try {
      if (encoded.length() > 7_000_000) throw invalid();
      byte[] bytes = Base64.getDecoder().decode(encoded);
      if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
      try (var input =
          new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw invalid();
        var reader = readers.next();
        try {
          reader.setInput(input, true, true);
          String format = reader.getFormatName();
          if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw invalid();
          int width = reader.getWidth(0), height = reader.getHeight(0);
          if (width < 1 || height < 1 || (long) width * height > 12_000_000) throw invalid();
          var original = reader.read(0);
          double scale = Math.min(1, 1600d / Math.max(width, height));
          var clean =
              new BufferedImage(
                  Math.max(1, (int) (width * scale)),
                  Math.max(1, (int) (height * scale)),
                  BufferedImage.TYPE_INT_RGB);
          var graphics = clean.createGraphics();
          try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, clean.getWidth(), clean.getHeight());
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(original, 0, 0, clean.getWidth(), clean.getHeight(), null);
          } finally {
            graphics.dispose();
          }
          var output = new ByteArrayOutputStream();
          try (var destination = new javax.imageio.stream.MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(clean, "png", destination)) throw invalid();
            destination.flush();
          }
          if (output.size() > MAX_BYTES) throw invalid();
          return output.toByteArray();
        } finally {
          reader.dispose();
        }
      }
    } catch (ResponseStatusException error) {
      throw error;
    } catch (Exception invalid) {
      throw invalid();
    }
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Choose a JPEG or PNG image under 5 MiB and 12 megapixels. Sanitized images must fit 5"
            + " MiB.");
  }
}
