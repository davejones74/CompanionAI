package davejones74.campanionai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class AuthFilter implements Filter {
    static final String COOKIE = "companionai_auth";
    static final String TOKEN_HEADER = "Authorization";

    private final ObjectMapper json = new ObjectMapper();
    private String expected;

    @Override
    public void init(FilterConfig config) {
        String token = config.getInitParameter("token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("AuthFilter requires the 'token' init parameter");
        }
        this.expected = sha256(token);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getRequestURI();

        if (path.equals("/api/auth/logout")) {
            clearCookie(resp);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write("{\"ok\":true}");
            return;
        }
        if (path.equals("/api/auth")) {
            if (!req.getMethod().equalsIgnoreCase("POST")) {
                resp.setStatus(405);
                return;
            }
            JsonNode body = json.readTree(req.getInputStream());
            String candidate = body.path("token").asText("");
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    sha256(candidate).getBytes(StandardCharsets.UTF_8))) {
                Cookie cookie = new Cookie(COOKIE, expected);
                cookie.setHttpOnly(true);
                cookie.setPath("/");
                cookie.setMaxAge(60 * 60 * 24 * 7);
                cookie.setAttribute("SameSite", "Lax");
                resp.addCookie(cookie);
                resp.setContentType("application/json; charset=UTF-8");
                resp.getWriter().write("{\"ok\":true}");
            } else {
                resp.setStatus(401);
                resp.setContentType("application/json; charset=UTF-8");
                resp.getWriter().write("{\"ok\":false,\"message\":\"Incorrect token.\"}");
            }
            return;
        }

        if (authenticated(req)) {
            chain.doFilter(request, response);
            return;
        }

        if (wantsHtml(req)) {
            resp.setContentType("text/html; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-store");
            resp.getWriter().write(loginPage());
        } else {
            resp.setStatus(401);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write("{\"ok\":false,\"message\":\"Unauthorized\"}");
        }
    }

    private static boolean authenticated(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName())) return true;
            }
        }
        String auth = req.getHeader(TOKEN_HEADER);
        return auth != null && auth.startsWith("Bearer ");
    }

    private static boolean wantsHtml(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("text/html");
    }

    private static void clearCookie(HttpServletResponse resp) {
        Cookie cookie = new Cookie(COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        resp.addCookie(cookie);
    }

    private static String loginPage() {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>CompanionAI - Sign in</title>
        <style>
          :root { --primary: #3d5afe; }
          * { box-sizing: border-box; }
          body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
                 font-family:-apple-system,"Segoe UI",Roboto,Arial,sans-serif; background:#eef1f6; color:#1f2733; }
          .card { background:#fff; padding:36px; border-radius:16px; box-shadow:0 8px 30px rgba(0,0,0,.12); width:340px; }
          h1 { margin:0 0 6px; font-size:20px; }
          p { margin:0 0 20px; color:#6b7686; font-size:13.5px; }
          input { width:100%; padding:12px; font-size:15px; border:1px solid #dce1ea; border-radius:10px; margin-bottom:14px; }
          input:focus { outline:2px solid var(--primary); border-color:transparent; }
          button { width:100%; padding:12px; font-size:15px; background:var(--primary); color:#fff;
                   border:0; border-radius:10px; cursor:pointer; }
          button:hover { filter:brightness(1.08); }
          .err { color:#c62828; font-size:13px; min-height:18px; margin-top:10px; }
        </style>
        </head>
        <body>
        <div class="card">
          <h1>CompanionAI</h1>
          <p>Enter the access token to continue.</p>
          <input type="password" id="token" placeholder="Access token" autofocus>
          <button id="go">Sign in</button>
          <div class="err" id="err"></div>
        </div>
        <script>
          const inputEl = document.getElementById('token');
          const errEl = document.getElementById('err');
          async function signIn() {
            errEl.textContent = '';
            const token = inputEl.value.trim();
            if (!token) { errEl.textContent = 'Enter a token.'; return; }
            try {
              const res = await fetch('/api/auth', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token })
              });
              const data = await res.json();
              if (!res.ok || !data.ok) throw new Error(data.message || 'Failed');
              window.location.href = '/';
            } catch (e) {
              errEl.textContent = e.message;
            }
          }
          document.getElementById('go').addEventListener('click', signIn);
          inputEl.addEventListener('keydown', function (e) { if (e.key === 'Enter') signIn(); });
        </script>
        </body>
        </html>
        """;
    }
}