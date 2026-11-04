package com.killcoding.jsplus.tomcat;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

import java.io.IOException;
import java.io.Writer;

/**
 * Custom ErrorReportValve — replaces Tomcat's default error page HTML with
 * the JsPlus-Tomcat branded style (white background, centered, system font,
 * Instagram-blue retry link with RequestUrl query param).
 *
 * Registered in TomcatServer.setup() at the host level so every error produced
 * by the container itself (404/500/etc.) renders this style, regardless of
 * per-webapp web.xml.
 */
public class CustomErrorReportValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int statusCode = response.getStatus();
        if (statusCode < 400) {
            return;
        }

        String message = response.getMessage();
        if (message == null || message.isEmpty()) {
            message = reasonPhrase(statusCode);
        }

        String requestUrl = request.getRequestURL().toString();
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            requestUrl = requestUrl + "?" + request.getQueryString();
        }

        String escapedRequestUrl = escapeHtml(requestUrl);
        String escapedMessage = escapeHtml(message);
        String title = statusCode + " " + message;

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-HK\">\n<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        html.append("<title>").append(escapeHtml(title)).append(" - JsPlus-Tomcat</title>\n");
        html.append("<style>\n");
        html.append("  * { box-sizing: border-box; }\n");
        html.append("  body {\n");
        html.append("    margin: 0; padding: 0; min-height: 100vh;\n");
        html.append("    display: flex; align-items: center; justify-content: center;\n");
        html.append("    background: #fff;\n");
        html.append("    font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif;\n");
        html.append("    color: #262626;\n");
        html.append("  }\n");
        html.append("  .container { max-width: 600px; padding: 40px 20px; text-align: center; }\n");
        html.append("  .code { font-size: 48px; font-weight: 600; margin: 0 0 16px 0; color: #262626; }\n");
        html.append("  .title { font-size: 20px; font-weight: 400; margin: 0 0 12px 0; color: #262626; }\n");
        html.append("  .desc { font-size: 14px; line-height: 1.5; color: #8e8e8e; margin: 0 0 24px 0; }\n");
        html.append("  a { color: #0095f6; text-decoration: none; font-size: 14px; font-weight: 600; }\n");
        html.append("  a:hover { text-decoration: underline; }\n");
        html.append("</style>\n</head>\n<body>\n");
        html.append("<div class=\"container\">\n");
        html.append("  <p class=\"code\">").append(statusCode).append("</p>\n");
        html.append("  <p class=\"title\">").append(escapedMessage).append("</p>\n");
        html.append("  <p class=\"desc\">The server returned an error while processing your request.</p>\n");
        html.append("  <p><a href=\"").append(escapedRequestUrl).append("\">&larr; Try Again</a></p>\n");
        html.append("</div>\n");
        html.append("</body>\n</html>\n");

        try {
            response.setContentType("text/html;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            Writer writer = response.getWriter();
            writer.write(html.toString());
            writer.flush();
        } catch (IOException ioe) {
            getContainer().getLogger().warn("CustomErrorReportValve failed to write error page", ioe);
        }
    }

    private String reasonPhrase(int code) {
        switch (code) {
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default:  return "Error";
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
