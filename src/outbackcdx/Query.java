package outbackcdx;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Predicate;

public class Query {
    private static final String DEFAULT_FIELDS = "urlkey,timestamp,url,mime,status,digest,redirecturl,robotflags,length,offset,filename";
    private static final String DEFAULT_FIELDS_CDX14 = DEFAULT_FIELDS + ",originalLength,originalOffset,originalFilename";
    private static final boolean CDX_PLUS_WORKAROUND = "1".equals(System.getenv("CDX_PLUS_WORKAROUND"));

    public static final long MIN_TIMESTAMP = 0L;
    public static final long MAX_TIMESTAMP = 99999999999999L;

    String accessPoint;
    MatchType matchType;
    Sort sort;
    String url;
    String method;
    String requestBody;
    String urlkey;
    String closest;
    String[] fields;
    boolean omitSelfRedirects;
    boolean allFields;
    boolean outputJson;
    long limit;
    Predicate<Capture> predicate;
    long from = MIN_TIMESTAMP;
    long to = MAX_TIMESTAMP;
    String collapseToLastSpec;

    public Query(MultiMap<String, String> params, Iterable<FilterPlugin> filterPlugins) {
        this(params, filterPlugins, new QueryConfig());
    }

    public Query(MultiMap<String, String> params, Iterable<FilterPlugin> filterPlugins, QueryConfig queryConfig) {
        accessPoint = params.get("accesspoint");
        url = params.get("url");
        method = params.get("method");
        requestBody = params.get("requestBody");
        urlkey = params.get("urlkey");
        matchType = MatchType.valueOf(params.getOrDefault("matchType", "default").toUpperCase());
        sort = Sort.valueOf(params.getOrDefault("sort", "default").toUpperCase());
        closest = params.get("closest");
        if (params.containsKey("from")) {
            from = timestamp14Long(params.get("from"), '0');
        }
        if (params.containsKey("to")) {
            to = timestamp14Long(params.get("to"), '9');
        }
        omitSelfRedirects = Boolean.parseBoolean(params.getOrDefault("omitSelfRedirects", String.valueOf(queryConfig.omitSelfRedirects)));

        predicate = capture -> true;
        if (params.getAll("filter") != null) {
            for (String filterSpec: params.getAll("filter")) {
                Filter filter = Filter.fromSpec(filterSpec);
                addPredicate(filter);
            }
        }

        if (filterPlugins != null) {
            for (FilterPlugin filterPlugin : filterPlugins) {
                addPredicate(filterPlugin.newFilter(params));
            }
        }

        // collapse / collapseToFirst has to be the last filter applied
        String collapseToFirstSpec = params.getOrDefault("collapseToFirst", params.get("collapse"));
        if (collapseToFirstSpec != null) {
            Filter filter = Filter.collapseToFirst(collapseToFirstSpec);
            addPredicate(filter);
        } else if (params.containsKey("collapseToLast")) {
            // collapseToLast can't be implemented as a predicate 
            collapseToLastSpec = params.get("collapseToLast");
        }

        allFields = !params.containsKey("fl");
        String fl = params.getOrDefault("fl", FeatureFlags.cdx14() ? DEFAULT_FIELDS_CDX14 : DEFAULT_FIELDS);
        fields = fl.split(",");

        String limitParam = params.get("limit");
        limit = limitParam == null ? Long.MAX_VALUE : Long.parseLong(limitParam);

        outputJson = "json".equals(params.get("output"));
    }

    /**
     * Pads timestamp with {@code padDigit} if shorter than 14 digits, or truncates
     * to 14 digits if longer than 14 digits, and converts to long.
     * <p>
     * For example:
     * <ul>
     * <li>"2019" -> 20190000000000l
     * <li>"20190128123456789" -> 20190128123456l
     * </ul>
     *
     * @throws NumberFormatException if the string does not contain a parsable long.
     */
    protected long timestamp14Long(String timestamp, char padDigit) {
        StringBuilder buf = new StringBuilder(timestamp);
        while (buf.length() < 14) {
            buf.append(padDigit);
        }
        buf.setLength(14);
        return Long.parseLong(buf.toString());
    }

    public void addPredicate(Predicate<Capture> predicate) {
        this.predicate = this.predicate.and(predicate);
    }

    void expandWildcards() {
        if (matchType == MatchType.DEFAULT) {
            if (url != null && url.endsWith("*")) {
                matchType = MatchType.PREFIX;
                url = url.substring(0, url.length() - 1);
            } else if (url != null && url.startsWith("*.")) {
                matchType = MatchType.DOMAIN;
                url = url.substring(2);
            } else {
                matchType = MatchType.EXACT;
            }
        }
    }

    void validate() {
        if ((url == null && urlkey == null) || (url != null && urlkey != null)) {
            throw new IllegalArgumentException("exactly one of 'url' or 'urlkey' is required");
        }
        if (sort == Sort.CLOSEST) {
            if (matchType != MatchType.EXACT) {
                throw new IllegalArgumentException("sort=closest is currently only implemented for exact matches");
            }
            if (closest == null) {
                throw new IllegalArgumentException("closest={timestamp} is mandatory when using sort=closest");
            }
        } else if (sort == Sort.REVERSE) {
            if (matchType != MatchType.EXACT) {
                throw new IllegalArgumentException("sort=reverse is currently only implemented for exact matches");
            }
        }
        if (from != MIN_TIMESTAMP || to != MAX_TIMESTAMP) {
            if (matchType != MatchType.EXACT) {
                throw new IllegalArgumentException("from={timestamp} and to={timestamp} are currently only implemented for exact matches");
            }
            if (sort == Sort.CLOSEST) {
                throw new IllegalArgumentException("from={timestamp} and to={timestamp} are currently not implemented for sort=closest queries");
            }
        }
    }

    String buildUrlKey(UrlCanonicalizer canonicalizer) {
        String urlToCanonicalize;
        if (method != null && !method.equalsIgnoreCase("GET")) {
            try {
                StringBuilder builder = new StringBuilder(url);
                builder.append(url.contains("?") ? "&" : "?");
                builder.append("__wb_method=").append(URLEncoder.encode(method.toUpperCase(Locale.ROOT), StandardCharsets.UTF_8.name()));
                if (requestBody != null) {
                    builder.append("&").append(requestBody);
                }
                urlToCanonicalize = builder.toString();
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        } else {
            urlToCanonicalize = url;
        }
        return canonicalizer.surtCanonicalize(urlToCanonicalize);
    }

    CloseableIterator<Capture> execute(Index index) {
        compatibilityHacks();
        expandWildcards();
        validate();

        if (urlkey == null) {
            urlkey = buildUrlKey(index.canonicalizer);
        }

        CloseableIterator<Capture> captures = index.execute(this);
        if (collapseToLastSpec != null) {
            captures = Filter.collapseToLast(captures, collapseToLastSpec);
        }

        if (CDX_PLUS_WORKAROUND && !captures.hasNext() && url != null && (url.contains("%20") || url.contains(" "))) {
            /*
             * XXX: NLA has a bunch of bad WARC files that contain + instead of %20 in the URLs. This is a dirty
             * workaround until we can fix them. If we found no results try again with + in place of %20.
             */
            captures.close();
            urlkey = null;
            url = url.replace("%20", "+").replace(" ", "+");
            captures = execute(index);
        }

        return captures;
    }

    private void compatibilityHacks() {
        /*
         * Cope pywb 2.0 sending nonsensical closest queries like ?url=foo&closest=&sort=closest.
         */
        if (sort == Sort.CLOSEST && (closest == null || closest.isEmpty())) {
            sort = Sort.DEFAULT;
        }
    }

    enum MatchType {
        DEFAULT, EXACT, PREFIX, HOST, DOMAIN, RANGE
    }

    enum Sort {
        DEFAULT, CLOSEST, REVERSE
    }
}
