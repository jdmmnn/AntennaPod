package de.danoeh.antennapod.playback.sonos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class Fixtures {
    private Fixtures() {
    }

    static String load(String name) throws IOException {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Missing fixture " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
