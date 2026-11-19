package com.castly.trust;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class EvidenceImageTest {
  @Test
  void sanitizerStripsPrivateMetadataAndBoundsDimensions() throws Exception {
    var source = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
    var raw = new ByteArrayOutputStream();
    ImageIO.write(source, "png", raw);
    byte[] png = raw.toByteArray();
    var tagged = new ByteArrayOutputStream();
    tagged.write(png, 0, 33);
    byte[] type = "tEXt".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        value =
            "Comment\0private-location-marker"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    var data = new DataOutputStream(tagged);
    data.writeInt(value.length);
    data.write(type);
    data.write(value);
    var crc = new CRC32();
    crc.update(type);
    crc.update(value);
    data.writeInt((int) crc.getValue());
    tagged.write(png, 33, png.length - 33);
    byte[] clean = EvidenceImage.sanitize(Base64.getEncoder().encodeToString(tagged.toByteArray()));
    assertFalse(
        new String(clean, java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains("private-location-marker"));
    var image = ImageIO.read(new ByteArrayInputStream(clean));
    assertEquals(1600, image.getWidth());
    assertEquals(800, image.getHeight());
    assertTrue(clean.length <= EvidenceImage.MAX_BYTES);
  }

  @Test
  void sanitizerRejectsInvalidOversizedAndNonImageInputs() {
    for (String value :
        List.of(
            "not-base64",
            Base64.getEncoder().encodeToString("<script>private payload</script>".getBytes()),
            "A".repeat(7_000_001)))
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class,
          () -> EvidenceImage.sanitize(value));
  }
}
