public class WebUrl implements Comparable<WebUrl>{
    private final String domain;
    private final Integer depth;

    public WebUrl(String domain){
        this(domain, 0);
    }

    public WebUrl(String domain, Integer depth) {
        this.domain = domain;
        this.depth = depth;
    }

    public String getDomain() {
        return domain;
    }

    public Integer getDepth() {
        return depth;
    }

    @Override
    public int compareTo(WebUrl o) {
        return depth.compareTo(o.depth) ;
    }


}
