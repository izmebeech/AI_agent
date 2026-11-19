import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.List;
import java.util.Map;

class McpClient implements AutoCloseable {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Process process;
    private final BufferedReader in;
    private final BufferedWriter out;
    private int id = 0;

    McpClient(List<String> cmd, Map<String, String> env) throws Exception {
        var pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        process = pb.start();
        in = process.inputReader();
        out = process.outputWriter();
        call("initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "my-agent", "version", "0")));
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
    }

    JsonNode listTools() throws Exception {
        return call("tools/list", Map.of()).get("tools");
    }

    String callTool(String name, Map<String, Object> args) throws Exception {
        var result = call("tools/call", Map.of("name", name, "arguments", args));
        var sb = new StringBuilder();
        for (var c : result.path("content")) sb.append(c.path("text").asString(""));
        return sb.toString();
    }

    private JsonNode call(String method, Object params) throws Exception {
        int mine = ++id;
        send(Map.of("jsonrpc", "2.0", "id", mine, "method", method, "params", params));
        for (String line; (line = in.readLine()) != null; ) {
            var n = mapper.readTree(line);
            if (n.has("id") && !n.has("method") && n.get("id").asInt() == mine) {
                if (n.has("error")) throw new IllegalStateException(n.get("error").toString());
                return n.get("result");
            }
        }
        throw new IllegalStateException("MCP-servern stängdes");
    }

    private void send(Object msg) throws Exception {
        out.write(mapper.writeValueAsString(msg));
        out.newLine();
        out.flush();
    }

    @Override public void close() { process.destroy(); }
}
