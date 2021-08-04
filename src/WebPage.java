public class WebPage {
    private WebUrl url;
    private String text;

    public WebPage(WebUrl url) {
        this.url=url;
    }


    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public WebUrl getUrl() {
        return url;
    }

    public void setUrl(WebUrl url) {
        this.url = url;
    }
}
