package pe.utec.minia.service;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.utec.minia.config.MiniaProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedAudioRequestTest {

    @Test
    void tomcatDechunksHttpRequestBeforeAudioReader(@TempDir Path baseDir) throws Exception {
        AudioService audioService = new AudioService(new MiniaProperties(
                null,
                null,
                new MiniaProperties.Audio(16_000, 1, 30, 100, 300),
                null,
                null
        ));
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        Connector connector = tomcat.getConnector();
        connector.setProperty("address", "127.0.0.1");

        Context context = tomcat.addContext("", baseDir.toString());
        Tomcat.addServlet(context, "audio", new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                byte[] pcm = audioService.readPcm16(request.getInputStream());
                response.setContentLength(pcm.length);
                response.getOutputStream().write(pcm);
            }
        });
        context.addServletMappingDecoded("/turno", "audio");

        try {
            tomcat.start();
            byte[] expectedPcm = new byte[9_600];
            for (int i = 0; i < expectedPcm.length; i++) {
                expectedPcm[i] = (byte) (i * 17);
            }

            assertEchoResponse(postChunked(connector.getLocalPort(), expectedPcm), expectedPcm);
            assertEchoResponse(postWithContentLength(connector.getLocalPort(), expectedPcm), expectedPcm);
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private byte[] postChunked(int port, byte[] pcm) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream output = socket.getOutputStream();
            output.write((
                    "POST /turno HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Content-Type: audio/L16;rate=16000;channels=1\r\n"
                            + "Transfer-Encoding: chunked\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
            ).getBytes(StandardCharsets.US_ASCII));

            int[] chunkSizes = {1, 7, 3_200, 2, 4_093};
            int offset = 0;
            int chunkIndex = 0;
            while (offset < pcm.length) {
                int length = Math.min(chunkSizes[chunkIndex++ % chunkSizes.length], pcm.length - offset);
                output.write(Integer.toHexString(length).getBytes(StandardCharsets.US_ASCII));
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                output.write(pcm, offset, length);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                offset += length;
            }
            output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();

            response.writeBytes(socket.getInputStream().readAllBytes());
        }
        return response.toByteArray();
    }

    private byte[] postWithContentLength(int port, byte[] pcm) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream output = socket.getOutputStream();
            output.write((
                    "POST /turno HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Content-Type: audio/L16;rate=16000;channels=1\r\n"
                            + "Content-Length: " + pcm.length + "\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
            ).getBytes(StandardCharsets.US_ASCII));
            output.write(pcm);
            output.flush();

            response.writeBytes(socket.getInputStream().readAllBytes());
        }
        return response.toByteArray();
    }

    private void assertEchoResponse(byte[] response, byte[] expectedPcm) {
        int headerEnd = findHeaderEnd(response);
        assertTrue(headerEnd >= 0, "La respuesta HTTP debe incluir cabeceras completas.");
        String headers = new String(response, 0, headerEnd, StandardCharsets.US_ASCII);
        assertTrue(headers.startsWith("HTTP/1.1 200"), headers);
        assertArrayEquals(expectedPcm, Arrays.copyOfRange(response, headerEnd + 4, response.length));
    }

    private int findHeaderEnd(byte[] response) {
        for (int i = 0; i + 3 < response.length; i++) {
            if (response[i] == '\r' && response[i + 1] == '\n'
                    && response[i + 2] == '\r' && response[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
