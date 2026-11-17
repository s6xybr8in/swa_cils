package cils;

/** Small shared HTML wrapper; no frontend framework or build step. */
public final class DemoPages {
    private DemoPages() {}
    public static String label(String client) {
        return switch (client) {
            case "STUDENT_PORTAL" -> "학생포털";
            case "PROFESSOR_PORTAL" -> "교수포털";
            case "STAFF_PORTAL" -> "교직원포털";
            default -> throw new IllegalArgumentException("Unknown portal");
        };
    }
    public static String escape(Object value) {
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    public static String page(String title, String body) {
        return """
                <!doctype html><html lang='ko'><head><meta charset='UTF-8'>
                <meta name='viewport' content='width=device-width,initial-scale=1'>
                <title>%s</title><style>
                *{box-sizing:border-box}body{margin:0;background:#f5f7fa;color:#182337;
                font:16px/1.7 system-ui,sans-serif}main{max-width:900px;margin:48px auto;padding:32px;
                background:white;border:1px solid #dce3ec;border-radius:16px}h1{font-size:30px;line-height:1.4}
                h2{font-size:20px;margin-top:32px}a{color:#235bc5}button,.button{display:inline-block;
                border:0;border-radius:8px;background:#2458bc;color:white;padding:12px 20px;
                font:inherit;cursor:pointer;text-decoration:none}.eyebrow{color:#526580;font-size:12px;
                letter-spacing:.1em}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));
                gap:12px;margin:24px 0}.card{padding:20px;border:1px solid #dce3ec;border-radius:10px;
                text-decoration:none;font-weight:600}.card span{display:block;font-size:13px;font-weight:400}
                .status{padding:16px;background:#edf4ff;border-radius:8px}.error{background:#fff0ee;color:#942e26;
                padding:16px;border-radius:8px}.muted{color:#657388;font-size:14px}code{background:#edf1f6;
                padding:2px 5px;border-radius:4px}dl{display:grid;grid-template-columns:110px 1fr;gap:10px}
                dt{color:#657388}dd{margin:0;overflow-wrap:anywhere}nav{margin-bottom:24px}
                @media(max-width:600px){main{margin:12px;padding:20px}h1{font-size:25px}dl{grid-template-columns:1fr}}
                </style></head><body><main><nav><a href='http://localhost:9000'>CILS 홈</a></nav>%s</main></body></html>
                """.formatted(escape(title), body);
    }
}
