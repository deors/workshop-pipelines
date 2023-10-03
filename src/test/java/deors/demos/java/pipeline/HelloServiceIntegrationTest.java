package deors.demos.java.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.safari.SafariOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelloServiceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(HelloServiceIntegrationTest.class);

    protected static boolean RUN_HTMLUNIT;

    protected static boolean RUN_IE;

    protected static boolean RUN_FIREFOX;

    protected static boolean RUN_CHROME;

    protected static boolean RUN_EDGE;

    protected static boolean RUN_SAFARI;

    protected static String SELENIUM_HUB_URL;

    protected static String TARGET_SERVER_URL;

    @BeforeAll
    public static void initEnvironment() {

        RUN_HTMLUNIT = getConfigurationProperty("RUN_HTMLUNIT", "test.run.htmlunit", true);

        logger.info("running the tests in HtmlUnit: " + RUN_HTMLUNIT);

        RUN_IE = getConfigurationProperty("RUN_IE", "test.run.ie", false);

        logger.info("running the tests in Internet Explorer: " + RUN_IE);

        RUN_FIREFOX = getConfigurationProperty("RUN_FIREFOX", "test.run.firefox", false);

        logger.info("running the tests in Firefox: " + RUN_FIREFOX);

        RUN_CHROME = getConfigurationProperty("RUN_CHROME", "test.run.chrome", false);

        logger.info("running the tests in Chrome: " + RUN_CHROME);

        RUN_EDGE = getConfigurationProperty("RUN_EDGE", "test.run.edge", false);

        logger.info("running the tests in Edge: " + RUN_EDGE);

        RUN_SAFARI = getConfigurationProperty("RUN_SAFARI", "test.run.safari", false);

        logger.info("running the tests in Safari: " + RUN_SAFARI);

        SELENIUM_HUB_URL = getConfigurationProperty(
            "SELENIUM_HUB_URL", "test.selenium.hub.url", "http://localhost:4444/wd/hub");

        logger.info("using Selenium hub at: " + SELENIUM_HUB_URL);

        TARGET_SERVER_URL = getConfigurationProperty(
            "TARGET_SERVER_URL", "test.target.server.url", "http://localhost:8080/");

        logger.info("using target server at: " + TARGET_SERVER_URL);
    }

    private static String getConfigurationProperty(String envKey, String sysKey, String defValue) {

        String retValue = defValue;
        String envValue = System.getenv(envKey);
        String sysValue = System.getProperty(sysKey);
        // system property prevails over environment variable
        if (sysValue != null) {
            retValue = sysValue;
        } else if (envValue != null) {
            retValue = envValue;
        }
        return retValue;
    }

    private static boolean getConfigurationProperty(String envKey, String sysKey, boolean defValue) {

        boolean retValue = defValue;
        String envValue = System.getenv(envKey);
        String sysValue = System.getProperty(sysKey);
        // system property prevails over environment variable
        if (sysValue != null) {
            retValue = Boolean.parseBoolean(sysValue);
        } else if (envValue != null) {
            retValue = Boolean.parseBoolean(envValue);
        }
        return retValue;
    }

    @Test
    public void testHtmlUnit()
        throws MalformedURLException, IOException {

        Assumptions.assumeTrue(RUN_HTMLUNIT);

        logger.info("executing test in htmlunit");

        WebDriver driver = null;

        try {
            driver = new HtmlUnitDriver(true);
            testAll(driver, TARGET_SERVER_URL);
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    @Test
    public void testIE()
        throws MalformedURLException, IOException {

        Assumptions.assumeTrue(RUN_IE);

        logger.info("executing test in internet explorer");

        WebDriver driver = null;
        try {
            Capabilities browser = new InternetExplorerOptions();
            driver = new RemoteWebDriver(new URL(SELENIUM_HUB_URL), browser);
            testAll(driver, TARGET_SERVER_URL);
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    @Test
    public void testFirefox()
        throws MalformedURLException, IOException {

        Assumptions.assumeTrue(RUN_FIREFOX);

        logger.info("executing test in firefox");

        WebDriver driver = null;
        try {
            Capabilities browser = new FirefoxOptions();
            driver = new RemoteWebDriver(new URL(SELENIUM_HUB_URL), browser);
            testAll(driver, TARGET_SERVER_URL);
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    @Test
    public void testChrome()
        throws MalformedURLException, IOException {

        Assumptions.assumeTrue(RUN_CHROME);

        logger.info("executing test in chrome");

        WebDriver driver = null;
        try {
            Capabilities browser = new ChromeOptions();
            driver = new RemoteWebDriver(new URL(SELENIUM_HUB_URL), browser);
            testAll(driver, TARGET_SERVER_URL);
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    @Test
    public void testEdge()
        throws MalformedURLException, IOException {

        Assumptions.assumeTrue(RUN_EDGE);

        logger.info("executing test in edge");

        WebDriver driver = null;
        try {
            Capabilities browser = new EdgeOptions();
            driver = new RemoteWebDriver(new URL(SELENIUM_HUB_URL), browser);
            testAll(driver, TARGET_SERVER_URL);
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    @Test
    public void testSafari()
        throws MalformedURLException, IOException {

        Assumptions.assumeTrue(RUN_SAFARI);

        logger.info("executing test in safari");

        WebDriver driver = null;
        try {
            Capabilities browser = new SafariOptions();
            driver = new RemoteWebDriver(new URL(SELENIUM_HUB_URL), browser);
            testAll(driver, TARGET_SERVER_URL);
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    private void testAll(WebDriver driver, String baseUrl) {
        testHelloGreeting(driver, baseUrl);
        testHelloWithNameGreeting(driver, baseUrl);
    }

    private void testHelloGreeting(WebDriver driver, String baseUrl) {

        WebElement body = (new WebDriverWait(driver, Duration.ofSeconds(10))).until(
            d -> {
                d.get(baseUrl + "hello");
                return d.findElement(By.xpath("/html/body"));
            });

        assertEquals("Hello!", body.getText(), "HelloGreeting service should respond with 'Hello!' greeting");
    }

    private void testHelloWithNameGreeting(WebDriver driver, String baseUrl) {

        WebElement body = (new WebDriverWait(driver, Duration.ofSeconds(10))).until(
            d -> {
                d.get(baseUrl + "hello/James");
                return d.findElement(By.xpath("/html/body"));
            });

        assertEquals("Hello!, James", body.getText(), "HelloGreeting service should respond with 'Hello!' greeting");
    }
}
