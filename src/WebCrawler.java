import org.openqa.selenium.WebDriver;

import java.util.Map;

public interface WebCrawler<R> {
    Map.Entry<WebUrl, R> use(WebDriver webdriver);
}
