import org.openqa.selenium.*;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Used to scrape a given url
 * Its main method is the use(driver)
 */
public class Crawler implements WebCrawler<String> {

    private final Controller controller;
    private WebUrl url;
    private List<WebUrl> urlsList;
    public Crawler(Controller c, WebUrl url){
        controller=c;
        this.url=url;
    }

    /**
     * Extracts the link present in the given webelement
     * Only checks for href html links
     * @param d element to check in
     * @return a list of weburl initialized with the url and the depth
     */
    public List<WebUrl> extractLinks(WebElement d){
        ConcurrentHashMap<String, String> urls=new ConcurrentHashMap<>();
        List<WebElement> links=d.findElements(By.tagName("a"));
        links.parallelStream().forEach(link -> {
            try{
                String url=link.getAttribute("href");
                if(url!=null && (url=Controller.removeSchemaAndWWW(url))!=null){
                    url=url.replaceAll("\\/$", "");
                    urls.put(url, url);
                }
            }catch (StaleElementReferenceException e){
                e.printStackTrace();
            }
        });
        return urls.keySet().stream()
                .map(u -> new WebUrl(u, this.url.getDepth()+1))
                .collect(Collectors.toList());
    }

    /**
     * Extract the text contained in the given webdriver page
     * @param driver
     * @return text
     */
    public String extractText(WebDriver driver){
        try {
            try {
                if (driver instanceof JavascriptExecutor) {
                    //((JavascriptExecutor) driver).executeScript("return document.getElementsByClassName('footer')[0].remove();");
                    return (String) ((JavascriptExecutor) driver).executeScript("return document.getElementsByTagName('BODY')[0].innerText");
                }
                //body=driver.findElement(By.tagName("body"));
            } catch (Exception e) {
                e.printStackTrace();
            }

            return driver.findElement(By.tagName("body")).getText();
        }catch(Exception e){
            e.printStackTrace();
        }
       return "";
    }

    /**
     * "Uses" the given driver to perform the crawling
     * It waits 5seconds to let js loads
     * Also perform the extracted url enqueue by using the controller interface
     * @param driver the driver used to crawl
     * @return entry with the url as the key and the text of the page as the value
     */
    @Override
    public Map.Entry<WebUrl, String> use(WebDriver driver) {
        try {
            try {
                driver.get("http://www." + url.getDomain());
            }catch(WebDriverException e){
                System.out.println("trying https: "+ url.getDomain());
                driver.get("https://www." + url.getDomain());
                System.out.println("https ok");
            }
            //driver.get(url.getDomain());
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            //to manage redirects
            var currUrl=driver.getCurrentUrl();
            currUrl=Controller.removeSchemaAndWWW(currUrl);
            if(currUrl!=null) {
                currUrl = currUrl.replaceAll("\\/$", "");
                url = new WebUrl(currUrl, url.getDepth());
            }

            var body = driver.findElement(By.tagName("body"));
            urlsList = extractLinks(body);

            controller.enqueue(urlsList);

            return new AbstractMap.SimpleEntry<>(url, extractText(driver));
        }catch (Exception e){
            System.out.println(url.getDomain());
           e.printStackTrace();
        }
        return new AbstractMap.SimpleEntry<>(url, "");
    }

    public List<WebUrl> getLinks() {
            return urlsList;
    }
}
