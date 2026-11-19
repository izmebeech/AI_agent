import tools.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Sql {
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
                        "serverInfo", Map.of("name", "sql", "version", "0"));
                case "tools/list" -> result = Map.of("tools", List.of(Map.of(
                        "name", "query",
                        "description", "Run read-only MySQL SQL (SELECT/SHOW/DESCRIBE). Max 200 rows.",
                        "inputSchema", Map.of("type", "object",
                                "properties", Map.of("sql", Map.of("type", "string")),
                                "required", List.of("sql")))));
                case "tools/call" -> {
                    String text;
                    boolean error = false;
                    try (var db = DriverManager.getConnection(System.getenv("MYSQL_URL"),
                            System.getenv("MYSQL_USER"), System.getenv("MYSQL_PASSWORD"))) {
                        db.setReadOnly(true);
                        try (var st = db.createStatement()) {
                            st.setMaxRows(200);
                            st.setQueryTimeout(15);
                            try (var rs = st.executeQuery(req.at("/params/arguments/sql").asString())) {
                                var md = rs.getMetaData();
                                var rows = new ArrayList<Map<String, String>>();
                                while (rs.next()) {
                                    var row = new LinkedHashMap<String, String>();
                                    for (int i = 1; i <= md.getColumnCount(); i++)
                                        row.put(md.getColumnLabel(i), rs.getString(i));
                                    rows.add(row);
                                }
                                text = mapper.writeValueAsString(rows);
                            }
                        }
                    } catch (Exception e) {
                        text = e.toString();
                        error = true;
                    }
                    result = Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error);
                }
                default -> { }
            }
            System.out.println(mapper.writeValueAsString(
                    Map.of("jsonrpc", "2.0", "id", req.get("id"), "result", result)));
        }
    }
}
