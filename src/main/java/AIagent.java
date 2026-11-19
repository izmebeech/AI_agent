import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AIagent {
    static final String HOST = "https://openrouter.ai";
    static final String MODEL = "google/gemini-3.8-flash"; // From the lecturer's guide; change if unavailable.
    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static final Path WORKSPACE = Path.of("workspace").toAbsolutePath().normalize();
    static final Map<String, McpClient> servers = new HashMap<>();
    static String instructions;

    static final List<Tool> TOOLS = new ArrayList<>(List.of(
            new Tool("function", "get_current_datetime", "Returns the current date and time in UTC",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()))
    ));

    public static void main(String[] args) throws Exception {
        if (System.getenv("OPENROUTER_API_KEY") == null || System.getenv("OPENROUTER_API_KEY").isBlank()) {
            IO.println("OPENROUTER_API_KEY saknas i Run Configuration.");
            return;
        }
        Files.createDirectories(WORKSPACE);
        registerBuiltinTools();
        instructions = "Svara kort. Max 2 meningar. Väderverktyget ger simulerat väder, inte en prognos."
                + loadSkills(WORKSPACE.resolve("skills"));

        // Run the MCP servers as Java subprocesses using IntelliJ's compiled classpath.
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        try {
            try {
                addServer("weather", new McpClient(List.of(java, "-cp", cp, "Weather"), Map.of()));
                IO.println("[MCP] Weather ansluten (simulerat väder).");
            } catch (Exception e) {
                IO.println("[MCP] Weather kunde inte starta: " + e.getMessage());
            }
            if (System.getenv("MYSQL_URL") != null && !System.getenv("MYSQL_URL").isBlank()) {
                try {
                    addServer("mysql", new McpClient(List.of(java, "-cp", cp, "Sql"), Map.of()));
                    IO.println("[MCP] MySQL ansluten (databasåtkomst kontrolleras vid anrop).");
                } catch (Exception e) {
                    IO.println("[MCP] MySQL kunde inte starta: " + e.getMessage());
                }
            }
            chat();
        } finally {
            for (McpClient client : servers.values()) client.close();
        }
    }

    static void chat() {
        var history = new ArrayList<InputItem>();
        IO.println("Chatten är igång! Skriv 'quit' för att avsluta.");
        while (true) {
            String in = IO.readln("You: ");
            if (in == null || "quit".equalsIgnoreCase(in.trim())) break;
            int mark = history.size();
            history.add(InputItem.message("user", in));
            try {
                boolean completed = false;
                for (int step = 0; step < 10; step++) {
                    Response r = send(new Request(MODEL, instructions, history, TOOLS));
                    if (r.output() == null) throw new IllegalStateException("API-svaret saknar output: " + r);
                    boolean called = false;
                    for (OutputItem item : r.output()) {
                        if (!"function_call".equals(item.type())) continue;
                        called = true;
                        IO.println("[tool] " + item.name() + " " + item.arguments());
                        history.add(InputItem.functionCall(item.id(), item.call_id(), item.name(), item.arguments()));
                        history.add(InputItem.functionCallOutput(item.call_id(), runTool(item.name(), item.arguments())));
                    }
                    if (called) continue;
                    String reply = text(r);
                    IO.println("Assistant: " + reply);
                    history.add(InputItem.message("assistant", reply));
                    completed = true;
                    break;
                }
                if (!completed) IO.println("[Stopp] Max 10 verktygsvarv nåddes.");
            } catch (Exception e) {
                IO.println("[Fel] " + e.getMessage());
                history.subList(mark, history.size()).clear();
            }
        }
    }

    static Response send(Request req) throws Exception {
        var http = HttpRequest.newBuilder(URI.create(HOST + "/api/v1/responses"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + System.getenv("OPENROUTER_API_KEY"))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(req)))
                .build();
        for (int attempt = 1; ; attempt++) {
            var res = CLIENT.send(http, HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s == 200) return MAPPER.readValue(res.body(), Response.class);
            if ((s == 429 || s >= 500) && attempt < 3) {
                Thread.sleep(2000L << (attempt - 1));
                continue;
            }
            throw new RuntimeException("HTTP " + s + ": " + res.body());
        }
    }

    static String text(Response r) {
        var sb = new StringBuilder();
        if (r.output() == null) return "";
        for (OutputItem item : r.output()) {
            if ("message".equals(item.type()) && item.content() != null) {
                for (OutputContentPart p : item.content()) {
                    if ("output_text".equals(p.type())) sb.append(p.text());
                }
            }
        }
        return sb.toString();
    }

    static String json(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static Tool tool(String name, String desc, Map<String, Object> props, String... required) {
        return new Tool("function", name, desc,
                Map.of("type", "object", "properties", props, "required", List.of(required)));
    }

    static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    static void registerBuiltinTools() {
        TOOLS.add(tool("view_file", "Read a file within the workspace", Map.of("path", str("Relative file path")), "path"));
        TOOLS.add(tool("list_directory", "List files within the workspace", Map.of("path", str("Relative directory path")), "path"));
        TOOLS.add(tool("create_file", "Create or overwrite a file within the workspace",
                Map.of("path", str("Relative file path"), "content", str("File contents")), "path", "content"));
    }

    static boolean confirm(String question) {
        String a = IO.readln(question + " (j/n): ");
        return a != null && (a.trim().equalsIgnoreCase("j") || a.trim().equalsIgnoreCase("ja"));
    }

    static Path safePath(Object raw) {
        Path p = WORKSPACE.resolve(raw.toString()).normalize();
        if (!p.startsWith(WORKSPACE)) throw new SecurityException("Sökvägen ligger utanför workspace");
        return p;
    }

    static String runTool(String name, String argumentsJson) {
        try { return json(execute(name, argumentsJson)); }
        catch (Exception e) { return json("Error: " + e); }
    }

    @SuppressWarnings("unchecked")
    static String execute(String name, String argumentsJson) throws Exception {
        String raw = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        Map<String, Object> arguments = MAPPER.readValue(raw, Map.class);
        switch (name) {
            case "get_current_datetime":
                return ZonedDateTime.now(ZoneOffset.UTC).toString();
            case "view_file": {
                Path p = safePath(arguments.get("path"));
                if (!confirm("Tillåt att " + p + " läses?")) return "User denied file access.";
                return Files.readString(p);
            }
            case "list_directory": {
                Path p = safePath(arguments.getOrDefault("path", "."));
                if (!confirm("Tillåt att lista " + p + "?")) return "User denied listing.";
                try (var stream = Files.list(p)) {
                    return stream.map(x -> x.getFileName() + (Files.isDirectory(x) ? "/" : ""))
                            .collect(Collectors.joining("\n"));
                }
            }
            case "create_file": {
                Path p = safePath(arguments.get("path"));
                if (!confirm("Tillåt att skapa/skriva över " + p + "?")) return "User denied writing.";
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, arguments.get("content").toString());
                return "File created";
            }
            default: {
                int i = name.indexOf('_');
                McpClient c = i > 0 ? servers.get(name.substring(0, i)) : null;
                if (c == null) return "Unknown tool: " + name;
                if (!confirm("Tillåt MCP-anrop " + name + " " + argumentsJson + "?")) return "User denied the call.";
                return c.callTool(name.substring(i + 1), arguments);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void addServer(String prefix, McpClient c) throws Exception {
        servers.put(prefix, c);
        for (var t : c.listTools()) {
            TOOLS.add(new Tool("function", prefix + "_" + t.get("name").asString(),
                    t.path("description").asString(""),
                    MAPPER.convertValue(t.get("inputSchema"), Map.class)));
        }
    }

    static String loadSkills(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return "";
        var sb = new StringBuilder("\n\nSkills. Matchar uppgiften en skill: läs dess SKILL.md med view_file och följ den.\n");
        try (var dirs = Files.list(dir)) {
            for (var d : (Iterable<Path>) dirs::iterator) {
                var f = d.resolve("SKILL.md");
                if (!Files.exists(f)) continue;
                String name = "", desc = "";
                for (String line : Files.readAllLines(f)) {
                    if (line.startsWith("name:")) name = line.substring(5).trim();
                    if (line.startsWith("description:")) desc = line.substring(12).trim();
                }
                sb.append("- ").append(name).append(" (").append(f).append("): ").append(desc).append("\n");
            }
        }
        return sb.toString();
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record Request(String model, String instructions, List<InputItem> input, List<Tool> tools) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record InputItem(String type, String role, List<ContentPart> content,
                 String id, String call_id, String name, String arguments, String output) {
    static InputItem message(String role, String text) {
        String t = "assistant".equals(role) ? "output_text" : "input_text";
        return new InputItem("message", role, List.of(new ContentPart(t, text)),
                null, null, null, null, null);
    }
    static InputItem functionCall(String id, String callId, String name, String args) {
        return new InputItem("function_call", null, null, id, callId, name, args, null);
    }
    static InputItem functionCallOutput(String callId, String output) {
        return new InputItem("function_call_output", null, null, null, callId, null, null, output);
    }
}
record ContentPart(String type, String text) {}
record Response(String id, List<OutputItem> output) {}
record OutputItem(String type, String id, List<OutputContentPart> content,
                  String call_id, String name, String arguments) {}
record OutputContentPart(String type, String text) {}
record Tool(String type, String name, String description, Map<String, Object> parameters) {}
