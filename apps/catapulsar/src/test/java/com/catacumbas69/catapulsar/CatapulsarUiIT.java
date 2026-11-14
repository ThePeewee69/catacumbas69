package com.catacumbas69.catapulsar;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.Tracing;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Smoke test of the static UI in the installed Google Chrome, headless, with the API mocked (TEST-4, orch.md
 * decision 93). The same flow runs twice: on a computer-sized screen, and on a phone (390 × 844, touch), where the
 * tree is in the ☰ drawer and the views are tabs at the bottom of the screen (decision 95).
 *
 * <p>Chrome is the one installed on the machine ({@code channel=chrome}): Playwright no longer ships a Chromium for
 * macOS 13, and Google's Chrome still runs there, as on GitHub's runners. Nothing is downloaded.
 *
 * <p>No server runs: every request of the page is answered by {@code context.route()}. The page and its assets
 * come from {@code static/} on the classpath, Alpine from the {@code org.webjars.npm:alpinejs} webjar of the version
 * {@code index.html} loads from cdnjs, and each {@code /api/**} call from a JSON file in
 * {@code src/test/resources/ui-smoke/}. A call with no fixture is answered 501 and fails the test, and so does any
 * console error or warning, uncaught exception or failed request. On failure a Playwright trace and a screenshot
 * are kept in {@code target/ui-smoke/} ({@code playwright show-trace}, or trace.playwright.dev), with the list of
 * calls without a fixture and console problems seen so far.
 */
class CatapulsarUiIT {

    /** The page's origin; never resolved, every request to it is routed. */
    private static final String ORIGIN = "http://catapulsar.test";

    /** Where the trace and the screenshot of a failed test are kept. */
    private static final Path ARTIFACTS = Path.of("target", "ui-smoke");

    /** The Alpine script tag of index.html, e.g. {@code .../ajax/libs/alpinejs/3.14.8/cdn.min.js}. */
    private static final Pattern ALPINE_SRC = Pattern.compile(
            "https://cdnjs\\.cloudflare\\.com/ajax/libs/alpinejs/([0-9][0-9.]*)/cdn\\.min\\.js");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png");

    private static final String SOURCE = "persistent://public/default/orders";
    private static final String DESTINATION = "persistent://public/default/orders-eu";

    private static Playwright playwright;
    private static Browser browser;
    private static String alpineUrl;
    private static byte[] alpine;

    private final ObjectMapper json = new ObjectMapper();

    /** The mocked API: "METHOD /path" or "METHOD /path?query" → fixture file. */
    private final Map<String, Fixture> api = new ConcurrentHashMap<>();
    /** Requests other than GET, in order: "METHOD /path" and their body. */
    private final List<Write> writes = new CopyOnWriteArrayList<>();
    /** /api calls that have no fixture. */
    private final List<String> unmocked = new CopyOnWriteArrayList<>();
    /** Console errors and warnings, uncaught exceptions, failed requests and requests outside the origin. */
    private final List<String> problems = new CopyOnWriteArrayList<>();

    private BrowserContext context;
    private Page page;
    /** Whether the test runs on a phone-sized screen (taps, drawer, bottom tabs). */
    private boolean phone;

    /** Keeps the trace and a screenshot of a failed test, before {@link #closeContext()} closes the page. */
    @RegisterExtension
    final AfterTestExecutionCallback keepTraceOnFailure = extension -> {
        if (context == null) {
            return;
        }
        String name = extension.getRequiredTestMethod().getName();
        if (extension.getExecutionException().isPresent()) {
            Files.createDirectories(ARTIFACTS);
            // A step that times out often does so because of a call without a fixture or a console error.
            List<String> report = new ArrayList<>();
            unmocked.forEach(call -> report.add("unmocked: " + call));
            report.addAll(problems);
            Files.write(ARTIFACTS.resolve(name + "-problems.txt"), report);
            report.forEach(System.err::println);
            page.screenshot(new Page.ScreenshotOptions().setPath(ARTIFACTS.resolve(name + ".png")).setFullPage(true));
            context.tracing().stop(new Tracing.StopOptions().setPath(ARTIFACTS.resolve(name + "-trace.zip")));
        } else {
            context.tracing().stop();
        }
    };

    @BeforeAll
    static void launchChrome() {
        String index = classpathText("static/index.html");
        Matcher src = ALPINE_SRC.matcher(index);
        Assertions.assertThat(src.find()).as("index.html loads Alpine from cdnjs").isTrue();
        alpineUrl = src.group();
        String webjar = "META-INF/resources/webjars/alpinejs/" + src.group(1) + "/dist/cdn.min.js";
        alpine = classpathBytes(webjar);
        Assertions.assertThat(alpine)
                .as("index.html loads Alpine %s: set alpinejs.version in pom.xml to it (no %s on the classpath)",
                        src.group(1), webjar)
                .isNotNull();

        // Without this, Playwright.create() would download its own browsers (none of them runs on macOS 13).
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true));
    }

    @AfterAll
    static void closeChrome() {
        if (playwright != null) {
            playwright.close();
        }
    }

    /**
     * Opens a new browser context and page with the routes, the listeners and the mocked API.
     *
     * @param options size of the screen, and whether it is a phone
     */
    private void openPage(Browser.NewContextOptions options) {
        context = browser.newContext(options);
        context.setDefaultTimeout(10_000);
        // No automatic refresh during the test: what the page asks for depends only on the steps.
        context.addInitScript("localStorage.setItem('autoRefresh', 'off');");
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        context.route("**/*", this::answer);

        page = context.newPage();
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type()) || "warning".equals(message.type())) {
                problems.add("console." + message.type() + ": " + message.text());
            }
        });
        page.onPageError(error -> problems.add("uncaught: " + error));
        page.onRequestFailed(request -> problems.add("failed: " + request.method() + " " + request.url()
                + " (" + request.failure() + ")"));

        mockApi();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void smoke_onAComputer_opensTopic_createsBridge_andShowsItsSubscriptionOnTheTopic() throws Exception {
        openPage(new Browser.NewContextOptions().setViewportSize(1280, 800));
        smoke(false);
    }

    /** The same on a phone (< 768 px): the tree is in the ☰ drawer and the views are tabs at the bottom. */
    @Test
    void smoke_onAPhone_opensTopic_createsBridge_andShowsItsSubscriptionOnTheTopic() throws Exception {
        openPage(new Browser.NewContextOptions().setViewportSize(390, 844).setDeviceScaleFactor(3)
                .setIsMobile(true).setHasTouch(true));
        smoke(true);
    }

    /**
     * Load, open a topic, open Bridges, create a bridge, go back to the topic (TEST-4).
     *
     * @param phone true on a phone: taps, the drawer for the tree and the bottom tabs for Bridges
     * @throws Exception if the body sent to the API is not JSON
     */
    private void smoke(boolean phone) throws Exception {
        this.phone = phone;
        // Load: the cluster overview and the sidebar tree.
        page.navigate(ORIGIN + "/");
        assertThat(page.getByTestId("overview-title")).hasText("Cluster");

        // Open a topic from the tree: its subscriptions, one of them already used by a bridge.
        tree("tree-tenant", "public");
        tree("tree-namespace", "default");
        tree("tree-topic", "orders");
        assertThat(page.getByTestId("sub-row")).hasCount(2);
        assertThat(subRow("cataponte-orders-archive").getByTestId("sub-bridge")).containsText("orders-archive");

        // Open Bridges, on the tab of bridges without an area.
        press(page.getByTestId(phone ? "nav-bridges" : "bridges-open"));
        press(item("bridge-tab", "Bridges"));
        assertThat(page.getByTestId("bridge-card")).hasCount(1);

        // Create a bridge from orders to orders-eu.
        press(page.getByTestId("bridge-new"));
        Locator form = page.getByTestId("bridge-form");
        assertThat(form).isVisible();
        page.getByTestId("bridge-form-name").fill("orders-to-eu");
        press(item("bridge-form-source-topic", SOURCE));
        press(item("bridge-form-dest-topic", DESTINATION));
        // What the API answers once the bridge exists.
        mock("GET /api/v1/bridges", "bridges-after-create.json");
        mock("GET /api/v1/topics/public/default/orders/stats", "topic-stats-orders-after-create.json");
        mock("GET /api/v1/topics/public/default/orders/subscriptions",
                "topic-subscriptions-orders-after-create.json");
        press(page.getByTestId("bridge-form-save"));

        assertThat(page.getByTestId("toast")).containsText("Bridge created");
        assertThat(form).isHidden();
        assertThat(page.getByTestId("bridge-card")).hasCount(2);
        assertThat(page.getByTestId("bridge-card").filter(new Locator.FilterOptions().setHasText("orders-to-eu")))
                .containsText(DESTINATION);

        Assertions.assertThat(writes).extracting(Write::request).containsExactly("POST /api/v1/bridges");
        JsonNode bridge = json.readTree(writes.get(0).body());
        Assertions.assertThat(bridge.path("name").asText()).isEqualTo("orders-to-eu");
        Assertions.assertThat(bridge.path("source").path("topic").asText()).isEqualTo(SOURCE);
        Assertions.assertThat(bridge.path("source").path("subscription").asText()).isEqualTo("cataponte-orders-to-eu");
        Assertions.assertThat(bridge.path("destinations").findValuesAsText("topic")).containsExactly(DESTINATION);

        // Back to the topic: Bridges closes (UI-8) and the new bridge's subscription is linked to it.
        tree("tree-topic", "orders");
        assertThat(page.getByTestId("bridge-card")).hasCount(0);
        if (phone) {
            assertThat(page.getByTestId("nav-overview")).hasAttribute("aria-current", "page");
        }
        assertThat(page.getByTestId("sub-row")).hasCount(3);
        assertThat(subRow("cataponte-orders-to-eu").getByTestId("sub-bridge")).containsText("orders-to-eu");

        assertNoProblems();
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    /**
     * Finds the element with a test id whose own text, or a descendant's, is exactly the given text.
     *
     * @param testId value of {@code data-testid}
     * @param text   exact text, e.g. a tree label or a topic in a picker
     * @return the locator (a click on a descendant reaches the element's handler)
     */
    private Locator item(String testId, String text) {
        return page.getByTestId(testId).getByText(text, new Locator.GetByTextOptions().setExact(true));
    }

    /** Clicks, or taps on a phone. */
    private void press(Locator target) {
        if (phone) {
            target.tap();
        } else {
            target.click();
        }
    }

    /** Chooses an item of the sidebar tree; on a phone, opens the ☰ drawer first (a choice closes it). */
    private void tree(String testId, String text) {
        if (phone) {
            press(page.getByTestId("drawer-open"));
        }
        press(item(testId, text));
    }

    private Locator subRow(String subscription) {
        return page.getByTestId("sub-row").filter(new Locator.FilterOptions()
                .setHas(page.getByText(subscription, new Page.GetByTextOptions().setExact(true))));
    }

    private void assertNoProblems() {
        Assertions.assertThat(unmocked).as("API calls without a fixture (add them to mockApi())").isEmpty();
        Assertions.assertThat(problems).as("console errors and warnings, uncaught exceptions, failed requests")
                .isEmpty();
    }

    // ── The mocked API ───────────────────────────────────────────────────────

    /** Every call the steps make, as the page makes it. */
    private void mockApi() {
        mock("GET /api/v1/settings/refresh-interval", "refresh-interval.json");
        mock("GET /api/v1/alerts/summary", "alerts-summary.json");
        mock("GET /api/v1/tenants", "tenants.json");
        mock("GET /api/v1/namespaces/public", "namespaces-public.json");
        mock("GET /api/v1/topics/public/default", "topics-public-default.json");
        mock("GET /api/v1/overview?period=24h", "overview-cluster.json");
        mock("GET /api/v1/overview/public?period=24h", "overview-public.json");
        mock("GET /api/v1/overview/public/default?period=24h", "overview-public-default.json");
        mock("GET /api/v1/limits/backlog", "limits-backlog.json");
        mock("GET /api/v1/topics/public/default/orders/stats", "topic-stats-orders.json");
        mock("GET /api/v1/topics/public/default/orders/subscriptions", "topic-subscriptions-orders.json");
        mock("GET /api/v1/settings/cataponte", "settings-cataponte.json");
        mock("GET /api/v1/bridges", "bridges.json");
        mock("GET /api/v1/bridges/status", "bridges-status.json");
        mock("GET /api/v1/bridges/traffic", "bridges-traffic.json");
        mock("GET /api/v1/areas", "areas.json");
        api.put("POST /api/v1/bridges", new Fixture(201, "bridge-created.json"));
    }

    private void mock(String request, String file) {
        api.put(request, new Fixture(200, file));
    }

    /** Answers every request of the page: static files, Alpine, the mocked API; anything else is aborted. */
    private void answer(Route route) {
        Request request = route.request();
        URI uri = URI.create(request.url());
        if (request.url().equals(alpineUrl)) {
            fulfill(route, 200, CONTENT_TYPES.get("js"), alpine);
        } else if (!request.url().startsWith(ORIGIN + "/")) {
            problems.add("outside the test origin: " + request.method() + " " + request.url());
            route.abort();
        } else if (uri.getPath().startsWith("/api/")) {
            answerApi(route, request, uri);
        } else {
            String path = "/".equals(uri.getPath()) ? "/index.html" : uri.getPath();
            byte[] file = classpathBytes("static" + path);
            String extension = path.substring(path.lastIndexOf('.') + 1);
            if (file == null) {
                fulfill(route, 404, "text/plain", ("no static" + path).getBytes(StandardCharsets.UTF_8));
            } else {
                fulfill(route, 200, CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"), file);
            }
        }
    }

    private void answerApi(Route route, Request request, URI uri) {
        String call = request.method() + " " + uri.getPath();
        String withQuery = uri.getRawQuery() == null ? call : call + "?" + uri.getRawQuery();
        if (!"GET".equals(request.method())) {
            writes.add(new Write(call, request.postData()));
        }
        Fixture fixture = api.getOrDefault(withQuery, api.get(call));
        if (fixture == null) {
            unmocked.add(withQuery);
            fulfill(route, 501, "application/problem+json", ("{\"title\":\"Not mocked\",\"status\":501,\"detail\":\""
                    + withQuery + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = classpathBytes("ui-smoke/" + fixture.file());
        Assertions.assertThat(body).as("fixture ui-smoke/%s", fixture.file()).isNotNull();
        fulfill(route, fixture.status(), "application/json", body);
    }

    private static void fulfill(Route route, int status, String contentType, byte[] body) {
        route.fulfill(new Route.FulfillOptions().setStatus(status).setContentType(contentType).setBodyBytes(body));
    }

    // ── Set-up helpers ───────────────────────────────────────────────────────

    private static String classpathText(String name) {
        byte[] bytes = classpathBytes(name);
        Assertions.assertThat(bytes).as("%s on the classpath", name).isNotNull();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Reads a classpath resource, or returns {@code null} if there is none. */
    private static byte[] classpathBytes(String name) {
        try (InputStream in = CatapulsarUiIT.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A mocked answer: HTTP status and fixture file under {@code ui-smoke/}. */
    private record Fixture(int status, String file) {
    }

    /** A request that changes something: "METHOD /path" and its JSON body. */
    private record Write(String request, String body) {
    }
}
