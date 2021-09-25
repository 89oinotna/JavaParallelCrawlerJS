import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Used to crawl starting from a url
 * It statically creates the driver needed for the scraping
 */
public class Controller implements Callable<List<WebPage>> {
   // private static final int driversNum=4;
    private static final int driversNum=5;
    private static final BlockingQueue<WebDriver> drivers=new LinkedBlockingQueue<>(driversNum);
    static {
        try {
            //System.setProperty("webdriver.chrome.driver", "./chromedriver.exe");
            ChromeOptions options = new ChromeOptions();
            options.setHeadless(true);
            options.addArguments("--lang=it");
            for (int i = 0; i < driversNum; i++) {
                WebDriver driver = new ChromeDriver(options);
                driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
                drivers.offer(driver);
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    public static void quit(){
        drivers.forEach(WebDriver::quit);
    }

    private final ExecutorService executor;
    //To keep track of all seen url and also to store results
    private final ConcurrentHashMap<String, WebPage> urls=new ConcurrentHashMap<>();

    //priority queue to crawl urls in depth order
    private final PriorityBlockingQueue<WebUrl> urlsQueue=new PriorityBlockingQueue<>();

    private final Integer maxDepth;
    private final Integer maxPages;
    private WebUrl startDomain;
    private String baseDomain;

    //Queue used by crawler to enqueue urls and by controller to retrieve them (a sort of m producer 1 consumer logic)
    private final ConcurrentLinkedQueue<WebUrl> urlCollectingList=new ConcurrentLinkedQueue<>();

    //Used to store the future that we are waiting for to complete
    private final List<CompletableFuture<Map.Entry<WebUrl, String>>> futureList=new ArrayList<>();

    public static String removeSchemaAndWWW(String url){
        Matcher matcher = pattern.matcher(url);
        try {
            if (matcher.find()) {
                return matcher.group(3);
            }
        }catch(IllegalStateException e){
            e.printStackTrace();
        }
        return null;
    }

    private Controller(String startDomain, Integer maxDepth, Integer maxPages, ExecutorService executor){

        this.startDomain=new WebUrl(removeSchemaAndWWW(startDomain), 0);
        urls.put(this.startDomain.getDomain(), new WebPage(this.startDomain));
        urlsQueue.add(this.startDomain);
        this.maxDepth=maxDepth;
        this.maxPages=maxPages;
        this.executor=executor;
        this.baseDomain=extractBaseDomain(this.startDomain.getDomain());
    }

    /**
     * Creates a completablefuture performing the crawling of the given url
     * using the controller executor
     * it takes also care of the webdriver dependency
     * @param url
     * @return
     */
    public CompletableFuture<Map.Entry<WebUrl, String>> crawl(WebUrl url) {
        return CompletableFuture.supplyAsync( () -> {
            //System.out.println("supplyAsync | I am running on : " + Thread.currentThread().getName());
            try {
                WebDriver d=drivers.take();
                Crawler crawler=new Crawler(this, url);
                Map.Entry<WebUrl, String> res = crawler.use(d);
                drivers.offer(d);
                //List<WebUrl> urls = crawler.getLinks();
                return res;
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }, executor);

    }

    /**
     * Starts a controller instance using the given url as starting domain and
     * the given executor is used to run the crawlers
     * default maxDepth is 3 and maxpages is 10
     * @param url
     * @param executor
     * @return
     */
    public static CompletableFuture<List<WebPage>> start(String url, ExecutorService executor){
        return start(url, executor, 3, 10);
    }

    /**
     * Starts a controller instance using the given url as starting domain and
     * the given executor is used to run the crawlers
     * @param url
     * @param executor
     * @param maxDepth max depth to go in the bfs
     * @param maxPages max number of pages to crawl
     * @return
     */
    public static CompletableFuture<List<WebPage>> start(String url, ExecutorService executor, int maxDepth, int maxPages){
        Controller c=new Controller(url, maxDepth, maxPages, executor);
        return CompletableFuture.supplyAsync(c::call);
    }



    //like an emitter
    @Override
    public List<WebPage> call() {
        int crawledLinks=0;
        int currentLevel=0;
        do{
            try {
                collectAndRemoveCompleted(futureList, urls);
                collectUrls(urlCollectingList, urls, urlsQueue);
                WebUrl url;

                url= urlsQueue.poll(5000, TimeUnit.MILLISECONDS);

                if(url!=null){
                    //This ensures that we proceed level by level maybe loosing a bit in parallelization
                    // but is a simple solution. (barrier like)
                    if(url.getDepth()>currentLevel) {
                        CompletableFuture.allOf(futureList.toArray(CompletableFuture[]::new)).join();
                        currentLevel=url.getDepth();
                    }
                    //there cant be any url with depth less than this url now or in the future
                    futureList.add(crawl(url));
                    crawledLinks++;
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }while(!Thread.interrupted() && crawledLinks<maxPages && !futureList.isEmpty());

        CompletableFuture.allOf(futureList.toArray(CompletableFuture[]::new)).join();
        collectAndRemoveCompleted(futureList, urls);
        return urls.values().stream().filter(page -> page.getText()!=null).collect(Collectors.toList());
    }

    //shrinks url (es. www.name.it/home /home must be removed)
    private static final String regexDomain="(\\w*:\\/\\/)?(www.)?(([a-z \\- 0-9 .]+).*)";
    private static final Pattern pattern = Pattern.compile(regexDomain);

    public static String extractBaseDomain(String url){
        Matcher matcher = pattern.matcher(url);
        try {
            if (matcher.find()) {
                return matcher.group(4);
            }
        }catch(IllegalStateException e){
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Filters the given list of urls removing the ones
     * that have a different base domain from the starting one
     * @param list to be filtered
     * @return the filtered list
     */
    public List<WebUrl> filterUrls(List<WebUrl> list){
        //link filtering
        return list.stream()
                .filter(Objects::nonNull)
                .filter(u -> {
                    String bd=extractBaseDomain(u.getDomain());
                    return bd!=null && baseDomain.contains(bd);
                })
                .collect(Collectors.toList());
    }

    /**
     * Collects and removes the results of the completed future in the given list inserting it in the
     * respective webpage associated to it's url in the urls map
     * @param futureList
     * @param urls
     */
    public void collectAndRemoveCompleted(List<CompletableFuture<Map.Entry<WebUrl, String>>> futureList,
                                          ConcurrentHashMap<String, WebPage> urls){
        ListIterator<CompletableFuture<Map.Entry<WebUrl, String>>> it= futureList.listIterator();
        while(it.hasNext()){
            var cf=it.next();
            if(cf.isDone()){
                try {
                    Map.Entry<WebUrl, String> res=cf.get();
                    //store results

                    var p=urls.get(res.getKey().getDomain());
                    if(urls.size() == 1 && p==null) { //the first time we need to check if there was a redirect

                            p=new WebPage(res.getKey());
                            urls.put(res.getKey().getDomain(), p);
                            urls.remove(startDomain.getDomain());
                            startDomain=res.getKey();
                            baseDomain=extractBaseDomain(this.startDomain.getDomain());
                            //TODO remove leading /..

                    }
                    if(p!=null) p.setText(res.getValue());
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
                //removes from the list
                it.remove();
            }
        }
    }

    /**
     * Run by the emitter/collector
     * removes urls from the shared urlCollectinglist and inserts them to be crawled (if not already)
     * @param urlCollectingList
     * @param urls
     * @param urlsQueue
     */
    public void collectUrls( ConcurrentLinkedQueue<WebUrl> urlCollectingList,
                             ConcurrentHashMap<String, WebPage> urls,
                             PriorityBlockingQueue<WebUrl> urlsQueue){
        List<WebUrl> queue;
        synchronized (urlCollectingList) {
            queue=new ArrayList<>(urlCollectingList);
            urlCollectingList.clear();
        }
        //filter urls
        for(WebUrl currentUrl:filterUrls(queue)) {
            //String noSchema=Optional.ofNullable(removeSchema(currentUrl.getDomain())).orElse(currentUrl.getDomain());
            var page = urls.get(currentUrl.getDomain());
            if (page != null) { //this means we have seen this url
                var pageUrl = page.getUrl();
                if (pageUrl!=null && pageUrl.getDepth() > currentUrl.getDepth()) {
                    //IF ALREADY SCRAPED is better to not change the depht otherwise
                    // we should update all linked urls
                    //if not we can update its depth
                   // synchronized (urlsQueue) {
                        if(urlsQueue.contains(page.getUrl())){
                            //remove from queue
                            urlsQueue.remove(page.getUrl());
                            //insert the new with the lower depth
                            urlsQueue.add(currentUrl);

                            page.setUrl(currentUrl);
                        }
                   // }
                }
            }else{
                //just insert
                urls.put(currentUrl.getDomain(), new WebPage(currentUrl));
                urlsQueue.put(currentUrl);
            }
        }
    }

    /**
     * Called by crawlers
     * Enqueue the given list of urls in the shared urlCollectingList
     * @param urlsList
     */
    public void enqueue(List<WebUrl> urlsList) {
         /*check if already in hashmap, if not insert in it and in urls queue, also check for maxdepth
       if it is already in the hashmap but with an higher depth we upadte it -> remove from queue and reinsert        */
        try {
            synchronized (urlCollectingList) {
                urlCollectingList.addAll(urlsList.stream().filter(u -> u.getDepth() <= maxDepth).collect(Collectors.toList()));
            }
        }catch (Exception e){
            e.printStackTrace();
        }

    }

    /*
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        E result;
        try {
            while ( (result = dequeue()) == null && nanos > 0)
                nanos = notEmpty.awaitNanos(nanos);
        } finally {
            lock.unlock();
        }
        return result;
    }
     */
    public static void main(String[] args){
        String domain="website.com";
        ExecutorService executor=Executors.newFixedThreadPool(4);
        Controller.start(domain, executor);
    }
}
