/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.*;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.CompletionCapabilities;
import io.modelcontextprotocol.util.JDK8Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertWith;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class WebFluxSseIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransportProvider mcpServerTransportProvider;

	ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	@BeforeEach
	public void before() {

		this.mcpServerTransportProvider = new WebFluxSseServerTransportProvider.Builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpServerTransportProvider.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		clientBuilders.put("httpclient",
				McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
					.sseEndpoint(CUSTOM_SSE_ENDPOINT)
					.build()));
		clientBuilders.put("webflux",
				McpClient
					.sync(WebFluxSseClientTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
						.sseEndpoint(CUSTOM_SSE_ENDPOINT)
						.build()));

	}

	@AfterEach
	public void after() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithoutSamplingCapabilities(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema),
				(exchange, request) -> exchange.createMessage(mock(CreateMessageRequest.class))
					.thenReturn(mock(CallToolResult.class)));

		McpAsyncServer server = McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").tools(tool).build();

		try (McpSyncClient client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
			.build();) {

			assertThat(client.initialize()).isNotNull();

			try {
				client.callTool(new McpSchema.CallToolRequest("tool1", JDK8Utils.mapOf()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class)
					.hasMessage("Client must be configured with sampling capabilities");
			}
		}
		server.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageSuccess(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		CallToolResult callResponse = new McpSchema.CallToolResult(JDK8Utils.listOf(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

			McpSchema.CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(JDK8Utils.listOf(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(JDK8Utils.listOf())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					return exchange.createMessage(createMessageRequest)
						.doOnNext(samplingResult::set)
						.thenReturn(callResponse);
				});

		McpAsyncServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", JDK8Utils.mapOf()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);

			assertWith(samplingResult.get(), result -> {
				assertThat(result).isNotNull();
				assertThat(result.role()).isEqualTo(Role.USER);
				assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
				assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
				assertThat(result.model()).isEqualTo("MockModelName");
				assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
			});
		}
		mcpServer.closeGracefully().block();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithRequestTimeoutSuccess(String clientType) throws InterruptedException {

		// Client
		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(JDK8Utils.listOf(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

			McpSchema.CreateMessageRequest craeteMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(JDK8Utils.listOf(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(JDK8Utils.listOf())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					return exchange.createMessage(craeteMessageRequest)
						.doOnNext(samplingResult::set)
						.thenReturn(callResponse);
				});

		McpAsyncServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.requestTimeout(Duration.ofSeconds(4))
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", JDK8Utils.mapOf()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);

			assertWith(samplingResult.get(), result -> {
				assertThat(result).isNotNull();
				assertThat(result.role()).isEqualTo(Role.USER);
				assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
				assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
				assertThat(result.model()).isEqualTo("MockModelName");
				assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
			});
		}

		mcpServer.closeGracefully().block();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithRequestTimeoutFail(String clientType) throws InterruptedException {

		// Client
		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(JDK8Utils.listOf(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

			McpSchema.CreateMessageRequest craeteMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(JDK8Utils.listOf(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.build();

					return exchange.createMessage(craeteMessageRequest).thenReturn(callResponse);
				});

		McpAsyncServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.requestTimeout(Duration.ofSeconds(1))
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", JDK8Utils.mapOf()));
			}).withMessageContaining("within 1000ms");

		}

		mcpServer.closeGracefully().block();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsSuccess(String clientType) {
		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = JDK8Utils.listOf(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});

			// Remove a root
			mcpClient.removeRoot(roots.get(0).uri());

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(JDK8Utils.listOf(roots.get(1)));
			});

			// Add a new root
			McpSchema.Root root3 = new Root("uri3://", "root3");
			mcpClient.addRoot(root3);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(JDK8Utils.listOf(roots.get(1), root3));
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithoutCapability(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				});

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider).rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		// Create client without roots capability
		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			// Attempt to list roots should fail
			try {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", JDK8Utils.mapOf()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class).hasMessage("Roots not supported");
			}
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsNotificationWithEmptyRootsList(String clientType) {
		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(JDK8Utils.listOf()) // Empty roots list
			.build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithMultipleHandlers(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = JDK8Utils.listOf(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef1.get()).containsAll(roots);
				assertThat(rootsRef2.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsServerCloseWithActiveSubscription(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = JDK8Utils.listOf(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	String emptyJsonSchema = "{\n\"$schema\": \"http://json-schema.org/draft-07/schema#\",\n\"type\": \"object\",\n\"properties\": {}\n}\n";

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolCallSuccess(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpSchema.CallToolResult callResponse = new McpSchema.CallToolResult(JDK8Utils.listOf(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					String response = new RestTemplate().getForObject(
							"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md",
							String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", JDK8Utils.mapOf()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolListChangeHandlingSuccess(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpSchema.CallToolResult callResponse = new McpSchema.CallToolResult(JDK8Utils.listOf(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					String response = new RestTemplate().getForObject(
							"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md",
							String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			String response =  new RestTemplate().getForObject(
					"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md",
					String.class);
			assertThat(response).isNotBlank();
			rootsRef.set(toolsUpdate);
		}).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			mcpServer.notifyToolsListChanged();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(JDK8Utils.listOf(tool1.tool()));
			});

			// Remove a tool
			mcpServer.removeTool("tool1");

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});

			// Add a new tool
			McpServerFeatures.SyncToolSpecification tool2 = new McpServerFeatures.SyncToolSpecification(
					new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema),
					(exchange, request) -> callResponse);

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(JDK8Utils.listOf(tool2.tool()));
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testInitialize(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testLoggingNotification(String clientType) {
		// Create a list to store received logging notifications
		List<McpSchema.LoggingMessageNotification> receivedNotifications = new ArrayList<>();

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("logging-test", "Test logging notifications", emptyJsonSchema),
				(exchange, request) -> {

					// Create and send notifications with different levels

				//@formatter:off
					return exchange // This should be filtered out (DEBUG < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.DEBUG)
								.logger("test-logger")
								.data("Debug message")
								.build())
					.then(exchange // This should be sent (NOTICE >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.NOTICE)
								.logger("test-logger")
								.data("Notice message")
								.build()))
					.then(exchange // This should be sent (ERROR > NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.ERROR)
							.logger("test-logger")
							.data("Error message")
							.build()))
					.then(exchange // This should be filtered out (INFO < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.INFO)
								.logger("test-logger")
								.data("Another info message")
								.build()))
					.then(exchange // This should be sent (ERROR >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.ERROR)
								.logger("test-logger")
								.data("Another error message")
								.build()))
					.thenReturn(new CallToolResult("Logging test completed", false));
					//@formatter:on
				});

		McpAsyncServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().tools(true).build())
			.tools(tool)
			.build();

		try (
				// Create client with logging notification handler
				McpSyncClient mcpClient = clientBuilder.loggingConsumer(notification -> {
					receivedNotifications.add(notification);
				}).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Set minimum logging level to NOTICE
			mcpClient.setLoggingLevel(McpSchema.LoggingLevel.NOTICE);

			// Call the tool that sends logging notifications
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("logging-test", JDK8Utils.mapOf()));
			assertThat(result).isNotNull();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Logging test completed");

			// Wait for notifications to be processed
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {

				// Should have received 3 notifications (1 NOTICE and 2 ERROR)
				assertThat(receivedNotifications).hasSize(3);

				Map<String, McpSchema.LoggingMessageNotification> notificationMap = receivedNotifications.stream()
					.collect(Collectors.toMap(n -> n.data(), n -> n));

				// First notification should be NOTICE level
				assertThat(notificationMap.get("Notice message").level()).isEqualTo(McpSchema.LoggingLevel.NOTICE);
				assertThat(notificationMap.get("Notice message").logger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Notice message").data()).isEqualTo("Notice message");

				// Second notification should be ERROR level
				assertThat(notificationMap.get("Error message").level()).isEqualTo(McpSchema.LoggingLevel.ERROR);
				assertThat(notificationMap.get("Error message").logger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Error message").data()).isEqualTo("Error message");

				// Third notification should be ERROR level
				assertThat(notificationMap.get("Another error message").level())
					.isEqualTo(McpSchema.LoggingLevel.ERROR);
				assertThat(notificationMap.get("Another error message").logger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Another error message").data()).isEqualTo("Another error message");
			});
		}
		mcpServer.close();
	}

	// ---------------------------------------
	// Completion Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : Completion call")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCompletionShouldReturnExpectedSuggestions(String clientType) {
		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<String> expectedValues = JDK8Utils.listOf("python", "pytorch", "pyside");
		McpSchema.CompleteResult completionResponse = new McpSchema.CompleteResult(new CompleteResult.CompleteCompletion(expectedValues, 10, // total
				true // hasMore
		));

		AtomicReference<CompleteRequest> samplingRequest = new AtomicReference<>();
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (mcpSyncServerExchange,
				request) -> {
			samplingRequest.set(request);
			return completionResponse;
		};

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().completions().build())
			.prompts(new McpServerFeatures.SyncPromptSpecification(
					new Prompt("code_review", "this is code review prompt",
							JDK8Utils.listOf(new PromptArgument("language", "string", false))),
					(mcpSyncServerExchange, getPromptRequest) -> null))
			.completions(new McpServerFeatures.SyncCompletionSpecification(
					new McpSchema.PromptReference("ref/prompt", "code_review"), completionHandler))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CompleteRequest request = new CompleteRequest(new PromptReference("ref/prompt", "code_review"),
					new CompleteRequest.CompleteArgument("language", "py"));

			CompleteResult result = mcpClient.completeCompletion(request);

			assertThat(result).isNotNull();

			assertThat(samplingRequest.get().argument().name()).isEqualTo("language");
			assertThat(samplingRequest.get().argument().value()).isEqualTo("py");
			assertThat(samplingRequest.get().ref().type()).isEqualTo("ref/prompt");
		}

		mcpServer.close();
	}

}