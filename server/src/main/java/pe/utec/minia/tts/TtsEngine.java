package pe.utec.minia.tts;

public interface TtsEngine {

    byte[] sintetizar(String texto);

    String nombre();
}
