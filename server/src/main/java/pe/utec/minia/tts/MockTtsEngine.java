package pe.utec.minia.tts;

import pe.utec.minia.audio.AudioUtils;
import pe.utec.minia.config.MiniaProperties;

public class MockTtsEngine implements TtsEngine {

    private final MiniaProperties properties;

    public MockTtsEngine(MiniaProperties properties) {
        this.properties = properties;
    }

    @Override
    public byte[] sintetizar(String texto) {
        int rate = properties.audio().rate();
        return AudioUtils.concat(
                AudioUtils.tone(660, 140, rate),
                AudioUtils.silence(80, rate),
                AudioUtils.tone(880, 180, rate),
                AudioUtils.silence(400, rate)
        );
    }

    @Override
    public String nombre() {
        return "mock-tone";
    }
}
