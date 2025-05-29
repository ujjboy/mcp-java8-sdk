/*
* Copyright 2025 - 2025 the original author or authors.
*/
package io.modelcontextprotocol.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * @author Christian Tzolov
 */
public class TomcatTestUtil {

	TomcatTestUtil() {
		// Prevent instantiation
	}

	@Accessors(fluent = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class TomcatServer {
		Tomcat tomcat;
		AnnotationConfigWebApplicationContext appContext;
	}

	public static TomcatServer createTomcatServer(String contextPath, int port, Class<?> componentClass) {

		// Set up Tomcat first
		Tomcat tomcat = new Tomcat();
		tomcat.setPort(port);

		// Set Tomcat base directory to java.io.tmpdir to avoid permission issues
		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		// Use the same directory for document base
		Context context = tomcat.addContext(contextPath, baseDir);

		// Create and configure Spring WebMvc context
		AnnotationConfigWebApplicationContext appContext = new AnnotationConfigWebApplicationContext();
		appContext.register(componentClass);
		appContext.setServletContext(context.getServletContext());
		appContext.refresh();

		// Create DispatcherServlet with our Spring context
		DispatcherServlet dispatcherServlet = new DispatcherServlet(appContext);

		// Add servlet to Tomcat and get the wrapper
		Wrapper wrapper = Tomcat.addServlet(context, "dispatcherServlet", dispatcherServlet);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addServletMappingDecoded("/*", "dispatcherServlet");

		try {
			// Configure and start the connector with async support
			Connector connector = tomcat.getConnector();
			connector.setAsyncTimeout(3000); // 3 seconds timeout for async requests
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		return new TomcatServer(tomcat, appContext);
	}

}
