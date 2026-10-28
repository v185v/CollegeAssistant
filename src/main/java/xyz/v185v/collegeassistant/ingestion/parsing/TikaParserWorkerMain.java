package xyz.v185v.collegeassistant.ingestion.parsing;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import tools.jackson.databind.ObjectMapper;

/** 子 JVM 入口：文件字节从标准输入到达，解析后的 JSON 从标准输出离开。 */
public final class TikaParserWorkerMain {

    private static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;

    private TikaParserWorkerMain() {
    }

    public static void main(String[] args) {
        try {
            String resourceName = decodeResourceName(args);
            byte[] input = System.in.readNBytes(MAX_INPUT_BYTES + 1);
            if (input.length > MAX_INPUT_BYTES) {
                throw new IllegalArgumentException("input too large");
            }
            ParsedDocument parsed = new TikaDocumentParser().parse(
                    new java.io.ByteArrayInputStream(input), resourceName);
            new ObjectMapper().writeValue(System.out, parsed);
            System.out.flush();
        } catch (Throwable exception) {
            System.err.print("Document parsing failed");
            System.err.flush();
            System.exit(2);
        }
    }

    private static String decodeResourceName(String[] args) {
        if (args == null || args.length != 1
                || !args[0].startsWith("--resource-name-base64=")) {
            throw new IllegalArgumentException("resource name missing");
        }
        String encoded = args[0].substring("--resource-name-base64=".length());
        String decoded = new String(
                Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        if (decoded.isBlank() || decoded.length() > 512 || decoded.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("resource name invalid");
        }
        return decoded;
    }
}
