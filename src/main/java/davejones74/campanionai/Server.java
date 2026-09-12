package davejones74.campanionai;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

public class Server {
    public static void main(String[] args) throws Exception {
        String host = System.getProperty("campanionai.host", "0.0.0.0");
        int port = Integer.getInteger("campanionai.port", 8080);
        String authToken = System.getProperty("campanionai.authToken");

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.getConnector().setProperty("address", host);

        String docBase = File.createTempFile("campanionai", "").getAbsoluteFile().getParent();
        Context ctx = tomcat.addContext("", docBase);

        Wrapper wrapper = Tomcat.addServlet(ctx, "modelServlet", new ModelServlet());
        wrapper.setMultipartConfigElement(new jakarta.servlet.MultipartConfigElement(
                new File(docBase).getAbsolutePath(), 10 * 1024 * 1024, 12 * 1024 * 1024, 0));
        wrapper.addMapping("/");
        wrapper.addMapping("/api/chat");
        wrapper.addMapping("/api/chat/stream");
        wrapper.addMapping("/api/stats");
        wrapper.addMapping("/api/auth");
        wrapper.addMapping("/api/auth/logout");
        wrapper.addMapping("/api/shutdown");
        wrapper.addMapping("/upload");

        ctx.getServletContext().setAttribute("campanionai.tomcat", tomcat);

        if (authToken != null && !authToken.isBlank()) {
            FilterDef def = new FilterDef();
            def.setFilterName("authFilter");
            def.setFilterClass(AuthFilter.class.getName());
            def.addInitParameter("token", authToken);
            ctx.addFilterDef(def);
            FilterMap map = new FilterMap();
            map.setFilterName("authFilter");
            map.addURLPattern("/*");
            ctx.addFilterMap(map);
        }

        tomcat.start();
        System.out.println("CompanionAI running at http://" + host + ":" + port + "/");
        tomcat.getServer().await();
    }
}