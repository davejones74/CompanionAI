package davejones74.campanionai;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

public class Server {
    public static void main(String[] args) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8080);
        tomcat.getConnector();

        String docBase = File.createTempFile("campanionai", "").getAbsoluteFile().getParent();
        Context ctx = tomcat.addContext("", docBase);

        Wrapper wrapper = Tomcat.addServlet(ctx, "modelServlet", new ModelServlet());
        wrapper.setMultipartConfigElement(new jakarta.servlet.MultipartConfigElement(
                new File(docBase).getAbsolutePath(), 10 * 1024 * 1024, 12 * 1024 * 1024, 0));
        wrapper.addMapping("/");
        wrapper.addMapping("/api/chat");
        wrapper.addMapping("/api/chat/stream");
        wrapper.addMapping("/upload");

        tomcat.start();
        System.out.println("CompanionAI running at http://localhost:8080/");
        tomcat.getServer().await();
    }
}