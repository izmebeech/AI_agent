import tools.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Weather {
    public static void main(String[] args) throws Exception {
        var mapper = JsonMapper.builder().build();
        var in = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = in.readLine()) != null; ) {
            var req = mapper.readTree(line);
            if (!req.has("id")) continue;
            Object result = Map.of();
            switch (req.path("method").asString()) {
                case "initialize" -> result = Map.of(
                        "protocolVersion", req.at("/params/protocolVersion").asString(),
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "weather", "version", "0"));
                case "tools/list" -> result = Map.of("tools", List.of(Map.of(
                        "name", "get_weather",
                        "description", "Returns SIMULATED random weather for a city; NOT a real forecast",
                        "inputSchema", Map.of("type", "object",
                                "properties", Map.of("city", Map.of("type", "string")),
                                "required", List.of("city")))));
                case "tools/call" -> {
                    String city = req.at("/params/arguments/city").asString("okänd stad");
                    String weather = List.of("sol", "regn", "snö").get(new Random().nextInt(3));
                    result = Map.of("content", List.of(Map.of("type", "text",
                            "text", city + ": " + weather + " (simulerat, ej verkligt väder)")));
                }
                default -> { }
            }
            System.out.println(mapper.writeValueAsString(
                    Map.of("jsonrpc", "2.0", "id", req.get("id"), "result", result)));
        }
    }
}
